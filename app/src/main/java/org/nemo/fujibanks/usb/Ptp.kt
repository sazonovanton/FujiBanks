package org.nemo.fujibanks.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP (ISO 15740) wire format — containers, opcodes, response codes.
 *
 * Every PTP message is a 12-byte header followed by either up to five uint32
 * parameters (COMMAND / RESPONSE) or a raw payload (DATA):
 *
 *   [0-3]  uint32 LE  total length
 *   [4-5]  uint16 LE  container type
 *   [6-7]  uint16 LE  operation / response code
 *   [8-11] uint32 LE  transaction id
 *   [12+]  params or payload
 */

object PtpOp {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_STORAGE_INFO = 0x1005
    const val GET_NUM_OBJECTS = 0x1006
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A
    const val DELETE_OBJECT = 0x100B
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016
}

object PtpResp {
    const val OK = 0x2001
    const val GENERAL_ERROR = 0x2002
    const val SESSION_NOT_OPEN = 0x2003
    const val INVALID_TRANSACTION_ID = 0x2004
    const val OPERATION_NOT_SUPPORTED = 0x2005
    const val PARAMETER_NOT_SUPPORTED = 0x2006
    const val INCOMPLETE_TRANSFER = 0x2007
    const val INVALID_STORAGE_ID = 0x2008
    const val INVALID_OBJECT_HANDLE = 0x2009
    const val DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val INVALID_DEVICE_PROP_VALUE = 0x200B
    const val INVALID_PARAMETER = 0x201D
    const val SESSION_ALREADY_OPEN = 0x201E

    fun name(code: Int): String = when (code) {
        OK -> "OK"
        GENERAL_ERROR -> "GeneralError"
        SESSION_NOT_OPEN -> "SessionNotOpen"
        INVALID_TRANSACTION_ID -> "InvalidTransactionID"
        OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        PARAMETER_NOT_SUPPORTED -> "ParameterNotSupported"
        INCOMPLETE_TRANSFER -> "IncompleteTransfer"
        INVALID_STORAGE_ID -> "InvalidStorageID"
        INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
        DEVICE_PROP_NOT_SUPPORTED -> "DevicePropNotSupported"
        INVALID_DEVICE_PROP_VALUE -> "InvalidDevicePropValue"
        INVALID_PARAMETER -> "InvalidParameter"
        SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        else -> "0x%04X".format(code)
    }
}

object ContainerType {
    const val COMMAND = 1
    const val DATA = 2
    const val RESPONSE = 3
    const val EVENT = 4
}

/** PTP data type codes, as used by GetDevicePropDesc. */
object PtpType {
    const val INT8 = 0x0001
    const val UINT8 = 0x0002
    const val INT16 = 0x0003
    const val UINT16 = 0x0004
    const val INT32 = 0x0005
    const val UINT32 = 0x0006
    const val STRING = 0xFFFF
}

data class PtpContainer(
    val type: Int,
    val code: Int,
    val transactionId: Int,
    val params: List<Int> = emptyList(),
    val data: ByteArray = ByteArray(0),
) {
    // data class equals/hashCode would compare the ByteArray by identity; we never
    // compare containers, so keep the generated ones out of the way.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

const val PTP_HEADER_SIZE = 12

fun packContainer(c: PtpContainer): ByteArray {
    val params = c.params.take(5)
    val total = PTP_HEADER_SIZE + params.size * 4 + c.data.size
    val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    buf.putInt(total)
    buf.putShort(c.type.toShort())
    buf.putShort(c.code.toShort())
    buf.putInt(c.transactionId)
    params.forEach { buf.putInt(it) }
    buf.put(c.data)
    return buf.array()
}

fun unpackContainer(raw: ByteArray, length: Int = raw.size): PtpContainer {
    require(length >= PTP_HEADER_SIZE) { "Container too short: $length bytes" }
    val buf = ByteBuffer.wrap(raw, 0, length).order(ByteOrder.LITTLE_ENDIAN)
    buf.int // total length, already known
    val type = buf.short.toInt() and 0xFFFF
    val code = buf.short.toInt() and 0xFFFF
    val txn = buf.int

    var params = emptyList<Int>()
    var data = ByteArray(0)
    when (type) {
        ContainerType.DATA -> {
            data = ByteArray(length - PTP_HEADER_SIZE)
            buf.get(data)
        }
        ContainerType.RESPONSE, ContainerType.EVENT -> {
            val list = mutableListOf<Int>()
            while (buf.remaining() >= 4 && list.size < 5) list.add(buf.int)
            params = list
        }
    }
    return PtpContainer(type, code, txn, params, data)
}

/** Total length from a container's first four bytes, or 0 if too short to tell. */
fun containerLength(raw: ByteArray, available: Int): Int {
    if (available < 4) return 0
    return ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
}

/** A PTP operation that came back with a non-OK response code. */
class PtpException(val code: Int, val op: String) :
    Exception("$op failed: ${PtpResp.name(code)}")
