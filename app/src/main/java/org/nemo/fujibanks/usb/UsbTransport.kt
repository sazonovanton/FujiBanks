package org.nemo.fujibanks.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * PTP over USB bulk transfers.
 *
 * Blocking by design — every call must run off the main thread. FujiCamera
 * wraps these in coroutines on Dispatchers.IO.
 */
class UsbTransport(private val log: (String) -> Unit = { Log.d(TAG, it) }) {

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var transactionId = 0

    val isConnected: Boolean get() = connection != null

    fun connect(manager: UsbManager, device: UsbDevice) {
        // Fuji exposes PTP on the still-image interface. Fall back to interface 0
        // if the class isn't advertised (some bodies report vendor-specific).
        val intf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
            ?: device.getInterface(0)

        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        if (inEp == null || outEp == null) {
            throw IllegalStateException("No bulk endpoints on interface ${intf.id}")
        }

        val conn = manager.openDevice(device)
            ?: throw IllegalStateException("openDevice returned null — permission not granted?")

        // forceClaim: Android's own MTP/PTP stack may already hold the interface.
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IllegalStateException("claimInterface failed")
        }

        connection = conn
        usbInterface = intf
        epIn = inEp
        epOut = outEp
        transactionId = 0
        log(
            "USB connected: ${device.productName ?: device.deviceName} " +
                "(%04x:%04x), interface %d class %d, in=0x%02x/%d out=0x%02x/%d".format(
                    device.vendorId, device.productId,
                    intf.id, intf.interfaceClass,
                    inEp.address, inEp.maxPacketSize,
                    outEp.address, outEp.maxPacketSize,
                )
        )
    }

    fun disconnect() {
        val conn = connection ?: return
        usbInterface?.let { conn.releaseInterface(it) }
        conn.close()
        connection = null
        usbInterface = null
        epIn = null
        epOut = null
        transactionId = 0
        log("USB disconnected")
    }

    /**
     * Release and re-claim the interface. Clears stale PTP state on the camera
     * side after an aborted session, and resets our transaction counter with it.
     */
    fun reset(manager: UsbManager, device: UsbDevice) {
        disconnect()
        connect(manager, device)
    }

    private fun nextTransactionId(): Int = ++transactionId

    private fun requireConn(): UsbDeviceConnection =
        connection ?: throw IllegalStateException("Not connected")

    /**
     * Write a full container, chunked to keep individual bulk transfers small.
     *
     * [onChunk] is called after each chunk goes out, with bytes sent and total.
     * A 30 MB RAF is close to two thousand 16 KB transfers, and the only
     * alternative was an indeterminate bar that looks the same as a hung camera.
     */
    private fun send(container: PtpContainer, onChunk: ((Long, Long) -> Unit)? = null) {
        val conn = requireConn()
        val out = epOut ?: throw IllegalStateException("No OUT endpoint")
        val bytes = packContainer(container)
        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(MAX_BULK_TRANSFER, bytes.size - offset)
            val written = conn.bulkTransfer(out, bytes, offset, len, WRITE_TIMEOUT_MS)
            if (written < 0) throw IllegalStateException("USB write failed at offset $offset")
            offset += written
            onChunk?.invoke(offset.toLong(), bytes.size.toLong())
        }

        // PTP requires a zero-length packet when the payload ends exactly on a
        // packet boundary, otherwise the camera keeps waiting for more.
        val packetSize = out.maxPacketSize
        if (packetSize > 0 && bytes.isNotEmpty() && bytes.size % packetSize == 0) {
            conn.bulkTransfer(out, ByteArray(0), 0, 0, WRITE_TIMEOUT_MS)
        }
    }

    /**
     * Read one container. The header carries the total length, so a short first
     * read just means more bulk packets are pending.
     *
     * Reads are capped at [MAX_BULK_TRANSFER]: bulkTransfer goes through usbfs,
     * which rejects anything larger and surfaces it as a timeout.
     */
    private fun receive(timeoutMs: Int, onChunk: ((Long, Long) -> Unit)? = null): PtpContainer {
        val conn = requireConn()
        val input = epIn ?: throw IllegalStateException("No IN endpoint")
        val readSize = readChunkSize(input)
        val packet = ByteArray(readSize)

        // A zero-length packet is a valid terminator, not data — read past it.
        var first = 0
        var attempts = 0
        while (first == 0) {
            first = conn.bulkTransfer(input, packet, packet.size, timeoutMs)
            if (first < 0) throw IllegalStateException("USB read failed (timeout or stall)")
            if (++attempts > MAX_EMPTY_READS) {
                throw IllegalStateException("USB read returned only empty packets")
            }
        }

        val total = containerLength(packet, first)
        if (total <= first) return unpackContainer(packet, first)

        if (total > MAX_RESPONSE) throw IllegalStateException("Response too large: $total bytes")
        val full = ByteArray(total)
        System.arraycopy(packet, 0, full, 0, first)
        var have = first
        while (have < total) {
            val n = conn.bulkTransfer(input, packet, packet.size, timeoutMs)
            if (n < 0) throw IllegalStateException("USB read continuation failed at $have/$total")
            if (n == 0) continue
            val copy = minOf(n, total - have)
            System.arraycopy(packet, 0, full, have, copy)
            have += copy
            onChunk?.invoke(have.toLong(), total.toLong())
        }
        return unpackContainer(full, total)
    }

    /** A whole number of packets, within the usbfs ceiling. */
    private fun readChunkSize(endpoint: UsbEndpoint): Int {
        val packetSize = endpoint.maxPacketSize.takeIf { it > 0 } ?: DEFAULT_PACKET_SIZE
        return (MAX_BULK_TRANSFER / packetSize).coerceAtLeast(1) * packetSize
    }

    data class Result(val code: Int, val params: List<Int>, val data: ByteArray) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /** COMMAND, then an optional DATA container from the camera, then RESPONSE. */
    fun sendCommand(
        opcode: Int,
        params: List<Int> = emptyList(),
        timeoutMs: Int = READ_TIMEOUT_MS,
        /** Progress of the incoming data phase, for the operations big enough to need it. */
        onChunk: ((Long, Long) -> Unit)? = null,
    ): Result {
        val txn = nextTransactionId()
        send(PtpContainer(ContainerType.COMMAND, opcode, txn, params))

        var resp = receive(timeoutMs, onChunk)
        var data = ByteArray(0)
        if (resp.type == ContainerType.DATA) {
            data = resp.data
            resp = receive(timeoutMs)
        }
        if (resp.type != ContainerType.RESPONSE) {
            throw IllegalStateException("Expected RESPONSE, got type 0x%x".format(resp.type))
        }
        return Result(resp.code, resp.params, data)
    }

    /** COMMAND, then our DATA container, then RESPONSE. */
    fun sendDataCommand(
        opcode: Int,
        params: List<Int>,
        payload: ByteArray,
        timeoutMs: Int = READ_TIMEOUT_MS,
        /** Progress of the outgoing data phase; the command phase is one packet. */
        onChunk: ((Long, Long) -> Unit)? = null,
    ): Result {
        val txn = nextTransactionId()
        send(PtpContainer(ContainerType.COMMAND, opcode, txn, params))
        send(PtpContainer(ContainerType.DATA, opcode, txn, emptyList(), payload), onChunk)

        val resp = receive(timeoutMs)
        if (resp.type != ContainerType.RESPONSE) {
            throw IllegalStateException("Expected RESPONSE, got type 0x%x".format(resp.type))
        }
        return Result(resp.code, resp.params, ByteArray(0))
    }

    companion object {
        private const val TAG = "UsbTransport"
        /**
         * usbfs caps a single bulk transfer at 16 KB. Asking for more does not
         * fail loudly — it times out, which reads as a dead camera.
         */
        private const val MAX_BULK_TRANSFER = 16 * 1024
        private const val DEFAULT_PACKET_SIZE = 512
        private const val MAX_EMPTY_READS = 8
        private const val WRITE_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_RESPONSE = 100 * 1024 * 1024

        /** Fujifilm USB vendor id. Product id differs per body, so we never match on it. */
        const val FUJI_VENDOR_ID = 0x04CB

        fun isFujiCamera(device: UsbDevice): Boolean = device.vendorId == FUJI_VENDOR_ID
    }
}
