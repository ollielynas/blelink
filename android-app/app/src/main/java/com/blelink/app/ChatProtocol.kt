package com.blelink.app

import java.io.ByteArrayOutputStream

/**
 * Wire protocol for relaying short text chat messages between browsers connected
 * to the same phone. Frame shapes mirror PhotoProtocol exactly (opcode, id,
 * u32 BE length for START/END; opcode, id, u16 BE seq, payload for DATA) so both
 * sides can reuse the same chunked send/receive plumbing — only the opcodes and
 * message-size cap differ.
 *
 * Browser -> phone (upload one message):
 *   CHAT_SEND      = (opcode, msgId, totalLength: u32 BE)
 *   CHAT_SEND_DATA = (opcode, msgId, seq: u16 BE, payload...)
 *   CHAT_SEND_END  = (opcode, msgId, totalLength: u32 BE)
 *
 * Phone -> original sender only:
 *   CHAT_ACK       = (opcode, msgId, status)
 *
 * Phone -> every other connected browser (relay):
 *   CHAT_RECV      = (opcode, msgId, totalLength: u32 BE)
 *   CHAT_RECV_DATA = (opcode, msgId, seq: u16 BE, payload...)
 *   CHAT_RECV_END  = (opcode, msgId, totalLength: u32 BE)
 */
object ChatProtocol {
    const val OP_CHAT_SEND: Byte = 0x10
    const val OP_CHAT_SEND_DATA: Byte = 0x11
    const val OP_CHAT_SEND_END: Byte = 0x12

    const val OP_CHAT_ACK: Byte = 0x83.toByte()

    const val OP_CHAT_RECV: Byte = 0x92.toByte()
    const val OP_CHAT_RECV_DATA: Byte = 0x93.toByte()
    const val OP_CHAT_RECV_END: Byte = 0x94.toByte()

    const val STATUS_OK: Byte = 0x00
    const val STATUS_ERR_LENGTH_MISMATCH: Byte = 0x01
    const val STATUS_ERR_TOO_LARGE: Byte = 0x03

    const val MAX_CHAT_BYTES = 800

    data class ChatStart(val msgId: Byte, val totalLength: Int)
    data class ChatData(val msgId: Byte, val seq: Int, val payload: ByteArray)
    data class ChatEnd(val msgId: Byte, val totalLength: Int)

    fun parseStart(value: ByteArray): ChatStart? {
        if (value.size < 6 || value[0] != OP_CHAT_SEND) return null
        return ChatStart(value[1], readU32BE(value, 2))
    }

    fun parseData(value: ByteArray): ChatData? {
        if (value.size < 4 || value[0] != OP_CHAT_SEND_DATA) return null
        return ChatData(value[1], readU16BE(value, 2), value.copyOfRange(4, value.size))
    }

    fun parseEnd(value: ByteArray): ChatEnd? {
        if (value.size < 6 || value[0] != OP_CHAT_SEND_END) return null
        return ChatEnd(value[1], readU32BE(value, 2))
    }

    fun buildAck(msgId: Byte, status: Byte): ByteArray = byteArrayOf(OP_CHAT_ACK, msgId, status)

    fun buildRecvStart(msgId: Byte, totalLength: Int): ByteArray = buildLenFrame(OP_CHAT_RECV, msgId, totalLength)
    fun buildRecvEnd(msgId: Byte, totalLength: Int): ByteArray = buildLenFrame(OP_CHAT_RECV_END, msgId, totalLength)

    fun buildRecvData(msgId: Byte, seq: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(4 + payload.size)
        frame[0] = OP_CHAT_RECV_DATA
        frame[1] = msgId
        writeU16BE(frame, 2, seq)
        payload.copyInto(frame, 4)
        return frame
    }

    private fun buildLenFrame(opcode: Byte, msgId: Byte, totalLength: Int): ByteArray {
        val frame = ByteArray(6)
        frame[0] = opcode
        frame[1] = msgId
        writeU32BE(frame, 2, totalLength)
        return frame
    }

    private fun readU32BE(value: ByteArray, offset: Int): Int {
        return ((value[offset].toInt() and 0xFF) shl 24) or
            ((value[offset + 1].toInt() and 0xFF) shl 16) or
            ((value[offset + 2].toInt() and 0xFF) shl 8) or
            (value[offset + 3].toInt() and 0xFF)
    }

    private fun readU16BE(value: ByteArray, offset: Int): Int {
        return ((value[offset].toInt() and 0xFF) shl 8) or (value[offset + 1].toInt() and 0xFF)
    }

    private fun writeU32BE(value: ByteArray, offset: Int, v: Int) {
        value[offset] = ((v shr 24) and 0xFF).toByte()
        value[offset + 1] = ((v shr 16) and 0xFF).toByte()
        value[offset + 2] = ((v shr 8) and 0xFF).toByte()
        value[offset + 3] = (v and 0xFF).toByte()
    }

    private fun writeU16BE(value: ByteArray, offset: Int, v: Int) {
        value[offset] = ((v shr 8) and 0xFF).toByte()
        value[offset + 1] = (v and 0xFF).toByte()
    }
}

/** Per-device in-flight chat message reassembly buffer (mirrors PhotoReceiveState). */
class ChatReceiveState(val msgId: Byte, val expectedLength: Int) {
    val buffer = ByteArrayOutputStream(expectedLength.coerceAtMost(ChatProtocol.MAX_CHAT_BYTES))

    fun append(payload: ByteArray) {
        buffer.write(payload)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}
