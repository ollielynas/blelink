package com.blelink.app

import java.io.ByteArrayOutputStream

/**
 * Wire protocol for sharing an arbitrary file (any mime type, e.g. an image, PDF, or doc)
 * into the group chat. Frame shapes mirror ChatProtocol exactly (opcode, id, u32 BE length
 * for START/END; opcode, id, u16 BE seq, payload for DATA) — only the opcodes and payload
 * shape differ, since a file also carries a small metadata header the phone never needs to
 * understand: it reassembles and relays the raw bytes without looking inside them.
 *
 * Payload layout (what travels inside START/DATA/END, before chunking):
 *   [headerLength: u16 BE][header: UTF-8 JSON {"n":name,"m":mimeType,"s":senderName}][file bytes...]
 * The header is length-prefixed rather than delimited so arbitrary binary file bytes can
 * never be misread as part of it.
 *
 * Browser -> phone (upload one file):
 *   FILE_SEND      = (opcode, msgId, totalLength: u32 BE)
 *   FILE_SEND_DATA = (opcode, msgId, seq: u16 BE, payload...)
 *   FILE_SEND_END  = (opcode, msgId, totalLength: u32 BE)
 *
 * Phone -> original sender only:
 *   FILE_ACK       = (opcode, msgId, status)
 *
 * Phone -> every other connected browser (relay, same payload so everyone's chat stays in sync):
 *   FILE_RECV      = (opcode, msgId, totalLength: u32 BE)
 *   FILE_RECV_DATA = (opcode, msgId, seq: u16 BE, payload...)
 *   FILE_RECV_END  = (opcode, msgId, totalLength: u32 BE)
 */
object FileProtocol {
    const val OP_FILE_SEND: Byte = 0x40
    const val OP_FILE_SEND_DATA: Byte = 0x41
    const val OP_FILE_SEND_END: Byte = 0x42

    const val OP_FILE_ACK: Byte = 0x85.toByte()

    const val OP_FILE_RECV: Byte = 0x95.toByte()
    const val OP_FILE_RECV_DATA: Byte = 0x96.toByte()
    const val OP_FILE_RECV_END: Byte = 0x97.toByte()

    const val STATUS_OK: Byte = 0x00
    const val STATUS_ERR_LENGTH_MISMATCH: Byte = 0x01
    const val STATUS_ERR_TOO_LARGE: Byte = 0x03

    // Generous enough for photos/PDFs shared casually at a party; small enough that a chunked
    // BLE transfer (a few hundred bytes/write at best) still finishes in a reasonable time.
    const val MAX_FILE_BYTES = 1_500_000

    data class FileStart(val msgId: Byte, val totalLength: Int)
    data class FileData(val msgId: Byte, val seq: Int, val payload: ByteArray)
    data class FileEnd(val msgId: Byte, val totalLength: Int)

    fun parseStart(value: ByteArray): FileStart? {
        if (value.size < 6 || value[0] != OP_FILE_SEND) return null
        return FileStart(value[1], readU32BE(value, 2))
    }

    fun parseData(value: ByteArray): FileData? {
        if (value.size < 4 || value[0] != OP_FILE_SEND_DATA) return null
        return FileData(value[1], readU16BE(value, 2), value.copyOfRange(4, value.size))
    }

    fun parseEnd(value: ByteArray): FileEnd? {
        if (value.size < 6 || value[0] != OP_FILE_SEND_END) return null
        return FileEnd(value[1], readU32BE(value, 2))
    }

    fun buildAck(msgId: Byte, status: Byte): ByteArray = byteArrayOf(OP_FILE_ACK, msgId, status)

    private fun readU32BE(value: ByteArray, offset: Int): Int {
        return ((value[offset].toInt() and 0xFF) shl 24) or
            ((value[offset + 1].toInt() and 0xFF) shl 16) or
            ((value[offset + 2].toInt() and 0xFF) shl 8) or
            (value[offset + 3].toInt() and 0xFF)
    }

    private fun readU16BE(value: ByteArray, offset: Int): Int {
        return ((value[offset].toInt() and 0xFF) shl 8) or (value[offset + 1].toInt() and 0xFF)
    }
}

/** Per-device in-flight file reassembly buffer (mirrors ChatReceiveState/PhotoReceiveState). */
class FileReceiveState(val msgId: Byte, val expectedLength: Int) {
    val buffer = ByteArrayOutputStream(expectedLength.coerceAtMost(FileProtocol.MAX_FILE_BYTES))

    fun append(payload: ByteArray) {
        buffer.write(payload)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}
