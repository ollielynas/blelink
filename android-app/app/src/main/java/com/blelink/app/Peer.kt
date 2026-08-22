package com.blelink.app

import android.bluetooth.BluetoothDevice
import fi.iki.elonen.NanoWSD

/**
 * A connected guest, regardless of which transport they came in on. The wire protocol
 * (opcodes, START/DATA/END framing, per-peer reassembly buffers) doesn't care whether bytes
 * arrived over a BLE GATT write or a LAN WebSocket message — only sending and per-connection
 * identity differ, which is what this type exists to abstract over.
 */
sealed class Peer(val id: String) {
    class Ble(val device: BluetoothDevice, var notificationsEnabled: Boolean = false, var mtu: Int = 23) :
        Peer(device.address)

    // LAN has no MTU concept; sendFramedToPeer uses a generous fixed virtual MTU instead so the
    // exact same chunked framing (and reassembly code) works unchanged for both transports.
    class Lan(val socket: NanoWSD.WebSocket, id: String) : Peer(id)
}
