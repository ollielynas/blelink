package com.blelink.app

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.util.UUID

/**
 * The LAN counterpart to the BLE GATT server: serves the guest web app over plain HTTP on the
 * local network, and relays the same framed protocol bytes over a WebSocket instead of GATT
 * characteristic writes/notifies. MainActivity owns all `Peer`/state-map bookkeeping — this
 * class only reports connection lifecycle and raw bytes up via the callbacks passed to it,
 * mirroring the shape of BluetoothGattServerCallback so both transports feed the same handlers.
 */
class LanServer(
    port: Int,
    private val htmlProvider: () -> String,
    private val onPeerOpen: (Peer.Lan) -> Unit,
    private val onPeerMessage: (peerId: String, data: ByteArray) -> Unit,
    private val onPeerClose: (peerId: String) -> Unit
) : NanoWSD(port) {

    override fun serve(session: IHTTPSession): NanoHTTPD.Response {
        if (session.method == NanoHTTPD.Method.GET && (session.uri == "/" || session.uri == "/index.html")) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html; charset=utf-8", htmlProvider())
        }
        return super.serve(session)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val peerId = "lan-" + UUID.randomUUID()
        return object : WebSocket(handshake) {
            override fun onOpen() {
                onPeerOpen(Peer.Lan(this, peerId))
            }

            override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
                onPeerClose(peerId)
            }

            override fun onMessage(message: NanoWSD.WebSocketFrame) {
                onPeerMessage(peerId, message.binaryPayload)
            }

            override fun onPong(pong: NanoWSD.WebSocketFrame) {}

            override fun onException(exception: IOException) {}
        }
    }
}
