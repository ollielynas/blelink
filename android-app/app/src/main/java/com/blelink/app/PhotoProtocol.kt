package com.blelink.app

import java.io.ByteArrayOutputStream

/**
 * Wire protocol for photo transfer over the existing UART-style RX/TX
 * characteristics (replaces the earlier plain-text chat protocol).
 *
 * Browser -> phone (writes to RX):
 *   PHOTO_START = (opcode, transferId, totalLength: u32 BE)
 *   PHOTO_DATA  = (opcode, transferId, seq: u16 BE, payload...)
 *   PHOTO_END   = (opcode, transferId, totalLength: u32 BE)
 *
 * Phone -> browser (notify on TX):
 *   PHOTO_ACK   = (opcode, transferId, status)
 */
object PhotoProtocol {
    const val OP_PHOTO_START: Byte = 0x01
    const val OP_PHOTO_DATA: Byte = 0x02
    const val OP_PHOTO_END: Byte = 0x03

    const val OP_PHOTO_ACK: Byte = 0x81.toByte()

    const val STATUS_OK: Byte = 0x00
    const val STATUS_ERR_LENGTH_MISMATCH: Byte = 0x01
    const val STATUS_ERR_DECODE_FAILED: Byte = 0x02
    const val STATUS_ERR_TOO_LARGE: Byte = 0x03
    const val STATUS_ERR_TIMEOUT: Byte = 0x04

    const val MAX_PHOTO_BYTES = 300_000

    data class PhotoStart(val transferId: Byte, val totalLength: Int)
    data class PhotoData(val transferId: Byte, val seq: Int, val payload: ByteArray)
    data class PhotoEnd(val transferId: Byte, val totalLength: Int)

    fun parseStart(value: ByteArray): PhotoStart? {
        if (value.size < 6 || value[0] != OP_PHOTO_START) return null
        val transferId = value[1]
        val totalLength = readU32BE(value, 2)
        return PhotoStart(transferId, totalLength)
    }

    fun parseData(value: ByteArray): PhotoData? {
        if (value.size < 4 || value[0] != OP_PHOTO_DATA) return null
        val transferId = value[1]
        val seq = readU16BE(value, 2)
        val payload = value.copyOfRange(4, value.size)
        return PhotoData(transferId, seq, payload)
    }

    fun parseEnd(value: ByteArray): PhotoEnd? {
        if (value.size < 6 || value[0] != OP_PHOTO_END) return null
        val transferId = value[1]
        val totalLength = readU32BE(value, 2)
        return PhotoEnd(transferId, totalLength)
    }

    fun buildAck(transferId: Byte, status: Byte): ByteArray {
        return byteArrayOf(OP_PHOTO_ACK, transferId, status)
    }

    private fun readU32BE(value: ByteArray, offset: Int): Int {
        return ((value[offset].toInt() and 0xFF) shl 24) or
            ((value[offset + 1].toInt() and 0xFF) shl 16) or
            ((value[offset + 2].toInt() and 0xFF) shl 8) or
            (value[offset + 3].toInt() and 0xFF)
    }

    private fun readU16BE(value: ByteArray, offset: Int): Int {
        return ((value[offset].toInt() and 0xFF) shl 8) or
            (value[offset + 1].toInt() and 0xFF)
    }
}

/** Per-device in-flight photo reassembly buffer. */
class PhotoReceiveState(val transferId: Byte, val expectedLength: Int) {
    val buffer = ByteArrayOutputStream(expectedLength.coerceAtMost(PhotoProtocol.MAX_PHOTO_BYTES))

    fun append(payload: ByteArray) {
        buffer.write(payload)
    }

    fun isComplete(): Boolean = buffer.size() >= expectedLength

    fun toByteArray(): ByteArray = buffer.toByteArray()
}
