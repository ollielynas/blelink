package com.blelink.app

import java.io.ByteArrayOutputStream

/**
 * Wire protocol for queueing a YouTube video to play on the phone, layered on the same
 * RX/TX characteristics as photos and chat. Search happens entirely client-side in the
 * browser (a direct call to the YouTube Data API); what crosses BLE is a small JSON blob
 * with the chosen video's id, title, and channel — id drives playback, title/channel are
 * opaque display text relayed back out so every guest's page can show a shared queue list.
 * Frame shapes mirror ChatProtocol (opcode, id, u32 BE length for START/END; opcode, id,
 * u16 BE seq, payload for DATA).
 *
 * Browser -> phone (queue a chosen video; payload is JSON {"v":id,"t":title,"c":channel}):
 *   MUSIC_QUEUE      = (opcode, msgId, totalLength: u32 BE)
 *   MUSIC_QUEUE_DATA = (opcode, msgId, seq: u16 BE, payload...)
 *   MUSIC_QUEUE_END  = (opcode, msgId, totalLength: u32 BE)
 *
 * Phone -> requesting browser only:
 *   MUSIC_QUEUE_ACK = (opcode, msgId, status)
 *
 * Phone -> every connected browser (relay, same JSON payload, so everyone's queue list stays in sync):
 *   MUSIC_QUEUE_RECV      = (opcode, msgId, totalLength: u32 BE)
 *   MUSIC_QUEUE_RECV_DATA = (opcode, msgId, seq: u16 BE, payload...)
 *   MUSIC_QUEUE_RECV_END  = (opcode, msgId, totalLength: u32 BE)
 *
 * Phone -> every connected browser (periodic broadcast, unprompted, so guests who tap "Join
 * Audio" can play the same video in their own tab and stay roughly in step with the phone;
 * payload is JSON {"v":videoId,"p":positionSeconds,"pl":isPlaying}):
 *   MUSIC_SYNC      = (opcode, msgId, totalLength: u32 BE)
 *   MUSIC_SYNC_DATA = (opcode, msgId, seq: u16 BE, payload...)
 *   MUSIC_SYNC_END  = (opcode, msgId, totalLength: u32 BE)
 *
 * Browser -> phone -> browser (single-frame, no chunking needed): a browser periodically
 * pings while audio sync is on, the phone echoes it back immediately with no processing
 * delay, and the browser uses the round-trip time to estimate one-way transport latency —
 * a measured number to nudge sync position by, instead of a guessed constant.
 *   MUSIC_PING = (opcode, pingId)   browser -> phone
 *   MUSIC_PONG = (opcode, pingId)   phone -> browser, echoed back unchanged
 */
object MusicProtocol {
    const val OP_MUSIC_QUEUE: Byte = 0x33
    const val OP_MUSIC_QUEUE_DATA: Byte = 0x34
    const val OP_MUSIC_QUEUE_END: Byte = 0x35

    const val OP_MUSIC_QUEUE_ACK: Byte = 0x84.toByte()

    const val OP_MUSIC_QUEUE_RECV: Byte = 0xB0.toByte()
    const val OP_MUSIC_QUEUE_RECV_DATA: Byte = 0xB1.toByte()
    const val OP_MUSIC_QUEUE_RECV_END: Byte = 0xB2.toByte()

    const val OP_MUSIC_SYNC: Byte = 0xB3.toByte()
    const val OP_MUSIC_SYNC_DATA: Byte = 0xB4.toByte()
    const val OP_MUSIC_SYNC_END: Byte = 0xB5.toByte()

    const val OP_MUSIC_PING: Byte = 0xB6.toByte()
    const val OP_MUSIC_PONG: Byte = 0xB7.toByte()

    const val QUEUE_STATUS_OK: Byte = 0x00
    const val QUEUE_STATUS_INVALID_ID: Byte = 0x01

    // JSON {"v":id,"t":title,"c":channel} — title/channel can run a bit long, so give this
    // more room than a bare 11-char video id would need.
    const val MAX_QUEUE_PAYLOAD_BYTES = 500

    data class LenFrame(val msgId: Byte, val totalLength: Int)
    data class DataFrame(val msgId: Byte, val seq: Int, val payload: ByteArray)

    fun parseQueueStart(value: ByteArray) = parseLen(value, OP_MUSIC_QUEUE)
    fun parseQueueData(value: ByteArray) = parseData(value, OP_MUSIC_QUEUE_DATA)
    fun parseQueueEnd(value: ByteArray) = parseLen(value, OP_MUSIC_QUEUE_END)

    fun buildQueueAck(msgId: Byte, status: Byte): ByteArray = byteArrayOf(OP_MUSIC_QUEUE_ACK, msgId, status)

    private fun parseLen(value: ByteArray, expectedOpcode: Byte): LenFrame? {
        if (value.size < 6 || value[0] != expectedOpcode) return null
        return LenFrame(value[1], readU32BE(value, 2))
    }

    private fun parseData(value: ByteArray, expectedOpcode: Byte): DataFrame? {
        if (value.size < 4 || value[0] != expectedOpcode) return null
        return DataFrame(value[1], readU16BE(value, 2), value.copyOfRange(4, value.size))
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
}

/** Per-device in-flight reassembly buffer for a queue upload. */
class MusicReceiveState(val msgId: Byte, val expectedLength: Int, maxBytes: Int) {
    val buffer = ByteArrayOutputStream(expectedLength.coerceAtMost(maxBytes))

    fun append(payload: ByteArray) {
        buffer.write(payload)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}
