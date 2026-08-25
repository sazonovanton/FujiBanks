package org.nemo.fujibanks.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cursor-based reader for PTP datasets (DeviceInfo, PropDesc, ObjectInfo). */
class PtpReader(data: ByteArray) {
    private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    val remaining: Int get() = buf.remaining()

    fun u8(): Int = buf.get().toInt() and 0xFF
    fun i8(): Int = buf.get().toInt()
    fun u16(): Int = buf.short.toInt() and 0xFFFF
    fun i16(): Int = buf.short.toInt()
    fun u32(): Long = buf.int.toLong() and 0xFFFFFFFFL
    fun i32(): Int = buf.int

    /** PTP string: uint8 char count (including the null), then UCS-2LE chars. */
    fun str(): String {
        val n = u8()
        if (n == 0) return ""
        val sb = StringBuilder(n)
        repeat(n) {
            val ch = u16()
            if (ch != 0) sb.append(ch.toChar())
        }
        return sb.toString()
    }

    /** PTP array: uint32 count, then that many uint16. */
    fun u16Array(): List<Int> {
        val n = u32().toInt()
        return List(n) { u16() }
    }

    /** PTP array: uint32 count, then that many uint32. */
    fun u32Array(): List<Long> {
        val n = u32().toInt()
        return List(n) { u32() }
    }

    /** Read one value by PTP data type code. Returns Int or String. */
    fun valueByType(dataType: Int): Any = when (dataType) {
        PtpType.INT8 -> i8()
        PtpType.UINT8 -> u8()
        PtpType.INT16 -> i16()
        PtpType.UINT16 -> u16()
        PtpType.INT32 -> i32()
        PtpType.UINT32 -> u32().toInt()
        PtpType.STRING -> str()
        else -> i32()
    }

    fun skip(bytes: Int) {
        buf.position(buf.position() + bytes)
    }
}

// -- Pack helpers -----------------------------------------------------------

fun packU16(value: Int): ByteArray =
    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

fun packI16(value: Int): ByteArray = packU16(value)

fun packU32(value: Int): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

/** PTP string: length byte (chars + null), UCS-2LE chars, null terminator. */
fun packPtpString(s: String): ByteArray {
    if (s.isEmpty()) return byteArrayOf(0)
    val buf = ByteBuffer.allocate(1 + (s.length + 1) * 2).order(ByteOrder.LITTLE_ENDIAN)
    buf.put((s.length + 1).toByte())
    s.forEach { buf.putShort(it.code.toShort()) }
    buf.putShort(0)
    return buf.array()
}

fun parsePtpString(data: ByteArray): String =
    if (data.isEmpty()) "" else PtpReader(data).str()

fun ByteArray.toHex(limit: Int = 16): String =
    take(limit).joinToString(" ") { "%02x".format(it) }

// -- Datasets ---------------------------------------------------------------

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
    val vendorExtensionDesc: String,
    val operations: List<Int>,
    val properties: List<Int>,
) {
    fun supports(propId: Int) = properties.isEmpty() || propId in properties
}

fun parseDeviceInfo(data: ByteArray): DeviceInfo {
    val r = PtpReader(data)
    r.u16()                       // StandardVersion
    r.u32()                       // VendorExtensionID
    r.u16()                       // VendorExtensionVersion
    val vendorDesc = r.str()      // VendorExtensionDesc
    r.u16()                       // FunctionalMode
    val operations = r.u16Array()
    r.u16Array()                  // EventsSupported
    val properties = r.u16Array()
    r.u16Array()                  // CaptureFormats
    r.u16Array()                  // ImageFormats
    val manufacturer = r.str()
    val model = r.str()
    val deviceVersion = r.str()
    val serial = r.str()
    return DeviceInfo(manufacturer, model, deviceVersion, serial, vendorDesc, operations, properties)
}

/**
 * Describes one device property: its type, whether it can be written, and the
 * range or enumeration of values the camera accepts. This is the authoritative
 * source for slider bounds — never hardcode them.
 */
data class PropDesc(
    val propId: Int,
    val dataType: Int,
    val writable: Boolean,
    val defaultValue: Any?,
    val currentValue: Any?,
    /** RANGE form: min, max, step. */
    val range: Triple<Int, Int, Int>? = null,
    /** ENUM form: allowed values. */
    val allowed: List<Any>? = null,
)

fun parsePropDesc(data: ByteArray): PropDesc {
    val r = PtpReader(data)
    val propId = r.u16()
    val dataType = r.u16()
    val writable = r.u8() == 1
    val default = r.valueByType(dataType)
    val current = r.valueByType(dataType)

    var range: Triple<Int, Int, Int>? = null
    var allowed: List<Any>? = null
    if (r.remaining > 0) {
        when (r.u8()) {
            0x01 -> range = Triple(
                r.valueByType(dataType) as? Int ?: 0,
                r.valueByType(dataType) as? Int ?: 0,
                r.valueByType(dataType) as? Int ?: 1,
            )
            0x02 -> {
                val n = r.u16()
                allowed = List(n) { r.valueByType(dataType) }
            }
        }
    }
    return PropDesc(propId, dataType, writable, default, current, range, allowed)
}

data class ObjectInfo(
    val handle: Int,
    val storageId: Long,
    val formatCode: Int,
    val compressedSize: Long,
    val thumbFormat: Int,
    val thumbCompressedSize: Long,
    val imagePixWidth: Long,
    val imagePixHeight: Long,
    val filename: String,
    val captureDate: String,
) {
    val isJpeg: Boolean get() = formatCode == 0x3801
    /** Fuji reports RAF as an undefined-format object. */
    val isRaw: Boolean get() = filename.endsWith(".RAF", ignoreCase = true)
}

fun parseObjectInfo(handle: Int, data: ByteArray): ObjectInfo {
    val r = PtpReader(data)
    val storageId = r.u32()
    val format = r.u16()
    r.u16()                       // ProtectionStatus
    val compressedSize = r.u32()
    val thumbFormat = r.u16()
    val thumbCompressedSize = r.u32()
    r.u32()                       // ThumbPixWidth
    r.u32()                       // ThumbPixHeight
    val width = r.u32()
    val height = r.u32()
    r.u32()                       // ImageBitDepth
    r.u32()                       // ParentObject
    r.u16()                       // AssociationType
    r.u32()                       // AssociationDesc
    r.u32()                       // SequenceNumber
    val filename = r.str()
    val captureDate = r.str()
    return ObjectInfo(
        handle, storageId, format, compressedSize, thumbFormat, thumbCompressedSize,
        width, height, filename, captureDate,
    )
}
