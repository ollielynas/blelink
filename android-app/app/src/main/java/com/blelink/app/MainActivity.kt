package com.blelink.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.blelink.app.databinding.ActivityMainBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Acts as a BLE peripheral exposing a UART-style GATT service, *and* as a plain HTTP+WebSocket
 * server on the local network (see LanServer) — two independent transports guests can use to
 * reach the exact same relay. Both feed the same protocol-level handlers below via the `Peer`
 * abstraction (see Peer.kt): callers never need to know or care which transport a given guest
 * came in on.
 *  - RX characteristic / WebSocket messages: guest -> phone frames (see *Protocol.kt).
 *  - TX characteristic / WebSocket sends: phone -> guest frames.
 * Web Bluetooth can only act as a GATT client/central, never a peripheral, which is why the
 * phone must be the peripheral side for BLE. Multiple guests can be connected at once on either
 * transport; BLE's own hardware connection ceiling is the natural throttle there, so no explicit
 * connection-limit logic is needed.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // write (central -> phone)
        val TX_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // notify (phone -> central)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val WEB_URL = "https://ollielynas.com/blelink/"

        const val BLE_DEVICE_NAME = "BleLink"

        const val LAN_PORT = 8085

        // LAN has no MTU concept the way BLE does; sendFramedToPeer uses this as a generous
        // stand-in so the exact same chunked START/DATA/END framing (and reassembly code) works
        // unchanged for both transports — it just produces far fewer, bigger chunks over LAN.
        const val LAN_VIRTUAL_MTU = 16_000
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var photoAdapter: PhotoAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var lanServer: LanServer? = null
    private var lastLanIp: String? = null

    // Connected guests, regardless of transport (see Peer.kt), keyed by a transport-appropriate
    // id: the BLE device address, or a generated "lan-<uuid>" for a WebSocket connection.
    private val connectedPeers = ConcurrentHashMap<String, Peer>()
    private val photoReceiveStates = ConcurrentHashMap<String, PhotoReceiveState>()
    private val chatReceiveStates = ConcurrentHashMap<String, ChatReceiveState>()
    private val fileReceiveStates = ConcurrentHashMap<String, FileReceiveState>()
    private val musicQueueReceiveStates = ConcurrentHashMap<String, MusicReceiveState>()
    private var chatMsgIdCounter = 0
    private var fileMsgIdCounter = 0
    private var musicMsgIdCounter = 0

    // YouTube video ids are always exactly this shape; validated before ever reaching the
    // WebView's JS so a malicious "video id" can't break out of the evaluateJavascript() string.
    private val youtubeIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

    private val decodeExecutor = Executors.newSingleThreadExecutor()
    // Serializes chat relaying so a single guest never receives interleaved
    // DATA frames from two different chat messages on the same connection.
    private val chatExecutor = Executors.newSingleThreadExecutor()
    // Separate from chatExecutor so a large file relay never blocks plain text chat messages
    // (or vice versa) from going out promptly.
    private val fileExecutor = Executors.newSingleThreadExecutor()
    // Every LAN WebSocket send hops through here — see sendToPeer — since it's a blocking
    // socket write and callers include the main thread (the music sync loop).
    private val lanSendExecutor = Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startBleServer()
            } else {
                setStatus("Bluetooth permissions denied — cannot start")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        photoAdapter = PhotoAdapter()
        val gridLayoutManager = GridLayoutManager(this, 3)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (photoAdapter.getItemViewType(position) == PhotoAdapter.VIEW_TYPE_HERO) 3 else 1
            }
        }
        binding.photoRecyclerView.layoutManager = gridLayoutManager
        binding.photoRecyclerView.adapter = photoAdapter

        setUpYoutubePlayer()
        binding.openClientModeBtn.setOnClickListener { openClientMode() }

        showQrInto(binding.qrImage, WEB_URL)
        startLanServer()
        startLanIpRefreshLoop()
        ContextCompat.startForegroundService(this, Intent(this, HostingForegroundService::class.java))
        requestPermissionsAndStart()
    }

    /**
     * Opens this phone's own LAN mode page in a Custom Tab — the guest web app, talking to
     * this phone over `localhost` instead of Bluetooth. LAN mode never touches Web Bluetooth,
     * so unlike the old Bluetooth-only client mode, this actually works: a phone can reach its
     * own local HTTP server just fine, it just can never be a Web Bluetooth client to its own
     * BLE peripheral. Runs as a separate process from this app's own servers, so hosting is
     * unaffected while it's open. No particular browser is required — any of them support
     * WebSocket.
     */
    private fun openClientMode() {
        val uri = Uri.parse("http://localhost:$LAN_PORT/")
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpYoutubePlayer() {
        val webView = binding.youtubePlayerWebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        // Queued videos should autoplay one after another without requiring a tap each time.
        webView.settings.mediaPlaybackRequiresUserGesture = false

        // Loading straight from file:// gives the IFrame API a "Video player configuration
        // error" (YouTube doesn't like embedding from an opaque file:// origin). Loading the
        // same HTML with a real https base URL instead gives it a normal-looking third-party
        // embedding origin, which is what the player actually expects.
        val html = assets.open("youtube_player.html").bufferedReader().use { it.readText() }
        webView.loadDataWithBaseURL("https://ollielynas.com", html, "text/html", "utf-8", null)

        startMusicSyncLoop()
    }

    /**
     * Every few seconds, asks the WebView what's currently playing and relays that (video id +
     * position + playing/paused) to every connected guest, so one who taps "Join Audio" can
     * play the same video in their own tab and stay roughly — not sample-accurately — in step.
     * Transport latency and per-guest buffering make tighter sync impractical.
     */
    private fun startMusicSyncLoop() {
        val intervalMs = 2_000L
        val loop = object : Runnable {
            override fun run() {
                binding.youtubePlayerWebView.evaluateJavascript("JSON.stringify(getPlaybackState())") { rawJson ->
                    broadcastMusicSync(rawJson)
                }
                mainHandler.postDelayed(this, intervalMs)
            }
        }
        mainHandler.postDelayed(loop, intervalMs)
    }

    private fun broadcastMusicSync(rawJson: String?) {
        if (rawJson.isNullOrEmpty() || rawJson == "null") return
        if (connectedPeers.isEmpty()) return
        // evaluateJavascript's callback hands back a JSON-encoded *string* (quotes escaped);
        // unwrap it once to get the actual JSON object payload it contains.
        val json = try {
            JSONObject(org.json.JSONTokener(rawJson).nextValue() as String)
        } catch (e: Exception) {
            return
        }
        val videoId = json.optString("videoId", "")
        if (!youtubeIdPattern.matches(videoId)) return

        val payload = JSONObject()
        payload.put("v", videoId)
        payload.put("p", json.optDouble("position", 0.0))
        payload.put("pl", json.optBoolean("playing", false))
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)

        val syncId = nextMusicMsgId()
        for (peer in connectedPeers.values) {
            if (!isPeerReady(peer)) continue
            sendFramedToPeer(
                peer,
                MusicProtocol.OP_MUSIC_SYNC, MusicProtocol.OP_MUSIC_SYNC_DATA, MusicProtocol.OP_MUSIC_SYNC_END,
                syncId, bytes
            )
        }
    }

    private fun showQrInto(imageView: ImageView, content: String) {
        try {
            val size = 512
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            imageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            appendLog("QR generation failed: ${e.message}")
        }
    }

    /** First non-loopback IPv4 address of any active network interface — Wi-Fi, hotspot, USB
     *  tethering, whatever's actually up — used to build the LAN join URL / QR code. */
    private fun getLocalLanIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    private fun startLanIpRefreshLoop() {
        val intervalMs = 5_000L
        val loop = object : Runnable {
            override fun run() {
                updateLanQrIfChanged()
                mainHandler.postDelayed(this, intervalMs)
            }
        }
        updateLanQrIfChanged()
        mainHandler.postDelayed(loop, intervalMs)
    }

    private fun updateLanQrIfChanged() {
        val ip = getLocalLanIp()
        if (ip == lastLanIp) return
        lastLanIp = ip
        runOnUiThread {
            if (ip == null) {
                binding.lanQrImage.visibility = android.view.View.GONE
                binding.lanQrUnavailableText.visibility = android.view.View.VISIBLE
            } else {
                binding.lanQrUnavailableText.visibility = android.view.View.GONE
                binding.lanQrImage.visibility = android.view.View.VISIBLE
                showQrInto(binding.lanQrImage, "http://$ip:$LAN_PORT/")
            }
        }
    }

    private fun startLanServer() {
        val server = LanServer(
            port = LAN_PORT,
            htmlProvider = { assets.open("web_client.html").bufferedReader().use { it.readText() } },
            onPeerOpen = { peer -> onPeerConnected(peer) },
            onPeerMessage = { peerId, data ->
                connectedPeers[peerId]?.let { peer -> handleIncomingFrame(peer, data) }
            },
            onPeerClose = { peerId -> onPeerDisconnected(peerId) }
        )
        try {
            // NanoHTTPD.SOCKET_READ_TIMEOUT (5s) is meant for a normal short-lived HTTP
            // request/response — applied here it was closing every WebSocket connection the
            // moment it sat idle for 5 seconds between messages (which is normal; the ping
            // loop alone only fires every 10s), causing constant "could not reach the phone"
            // disconnects. 0 means no read timeout — Java's standard "block indefinitely".
            server.start(0, false)
            lanServer = server
        } catch (e: IOException) {
            appendLog("LAN server failed to start: ${e.message}")
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += listOf(
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            permissions += android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        // Without this the hosting notification is silently suppressed (though the foreground
        // service itself still starts and still protects the process from being killed).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += android.Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startBleServer()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleServer() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth is off — please enable it and reopen the app")
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            setStatus("This device does not support BLE peripheral / advertising mode")
            return
        }

        // Give the peripheral a recognizable name so it's easy to pick out of the
        // browser's Web Bluetooth device chooser, which otherwise lists devices by MAC.
        if (adapter.name != BLE_DEVICE_NAME) {
            adapter.name = BLE_DEVICE_NAME
        }

        gattServer = bluetoothManager.openGattServer(this, gattServerCallback)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val rxChar = BluetoothGattCharacteristic(
            RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val txChar = BluetoothGattCharacteristic(
            TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        txChar.addDescriptor(cccd)
        txCharacteristic = txChar

        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)
        gattServer?.addService(service)

        advertiser = adapter.bluetoothLeAdvertiser
        startAdvertising()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            updateStatusFromConnectionCount()
        }

        override fun onStartFailure(errorCode: Int) {
            setStatus("Failed to start advertising (error $errorCode)")
        }
    }

    private fun updateStatusFromConnectionCount() {
        val count = connectedPeers.size
        val connected = count > 0
        setStatus(
            if (!connected) "Waiting for a guest to connect"
            else "Connected: $count guest(s)"
        )
        runOnUiThread {
            val pillBg = binding.statusPill.background.mutate() as GradientDrawable
            val dotBg = binding.statusDot.background.mutate() as GradientDrawable
            val pillColorRes = if (connected) R.color.status_connected_bg else R.color.status_idle_bg
            val textColorRes = if (connected) R.color.status_connected_text else R.color.status_idle_text
            pillBg.setColor(ContextCompat.getColor(this, pillColorRes))
            dotBg.setColor(ContextCompat.getColor(this, textColorRes))
            binding.statusText.setTextColor(ContextCompat.getColor(this, textColorRes))
        }
    }

    private fun isPeerReady(peer: Peer): Boolean = when (peer) {
        is Peer.Ble -> peer.notificationsEnabled
        is Peer.Lan -> true // no CCCD dance over a WebSocket — it's bidirectional as soon as it's open
    }

    private fun onPeerConnected(peer: Peer.Lan) {
        connectedPeers[peer.id] = peer
        updateStatusFromConnectionCount()
    }

    /** Shared disconnect cleanup for both transports. */
    private fun onPeerDisconnected(peerId: String) {
        connectedPeers.remove(peerId)
        photoReceiveStates.remove(peerId)
        chatReceiveStates.remove(peerId)
        fileReceiveStates.remove(peerId)
        musicQueueReceiveStates.remove(peerId)
        updateStatusFromConnectionCount()
    }

    /** Shared opcode dispatch for both transports — a BLE GATT write and a LAN WebSocket
     *  message both end up here once resolved to a Peer. */
    private fun handleIncomingFrame(peer: Peer, value: ByteArray) {
        if (value.isEmpty()) return
        when (value[0]) {
            PhotoProtocol.OP_PHOTO_START -> handlePhotoStart(peer, value)
            PhotoProtocol.OP_PHOTO_DATA -> handlePhotoData(peer, value)
            PhotoProtocol.OP_PHOTO_END -> handlePhotoEnd(peer, value)
            ChatProtocol.OP_CHAT_SEND -> handleChatStart(peer, value)
            ChatProtocol.OP_CHAT_SEND_DATA -> handleChatData(peer, value)
            ChatProtocol.OP_CHAT_SEND_END -> handleChatEnd(peer, value)
            FileProtocol.OP_FILE_SEND -> handleFileStart(peer, value)
            FileProtocol.OP_FILE_SEND_DATA -> handleFileData(peer, value)
            FileProtocol.OP_FILE_SEND_END -> handleFileEnd(peer, value)
            MusicProtocol.OP_MUSIC_QUEUE -> handleMusicQueueStart(peer, value)
            MusicProtocol.OP_MUSIC_QUEUE_DATA -> handleMusicQueueData(peer, value)
            MusicProtocol.OP_MUSIC_QUEUE_END -> handleMusicQueueEnd(peer, value)
            MusicProtocol.OP_MUSIC_PING -> handleMusicPing(peer, value)
        }
    }

    @SuppressLint("MissingPermission")
    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedPeers[device.address] = Peer.Ble(device)
                    updateStatusFromConnectionCount()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onPeerDisconnected(device.address)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            (connectedPeers[device.address] as? Peer.Ble)?.mtu = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == RX_CHAR_UUID && value.isNotEmpty()) {
                connectedPeers[device.address]?.let { peer -> handleIncomingFrame(peer, value) }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                (connectedPeers[device.address] as? Peer.Ble)?.notificationsEnabled =
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    private fun handlePhotoStart(peer: Peer, value: ByteArray) {
        val start = PhotoProtocol.parseStart(value) ?: return
        if (start.totalLength > PhotoProtocol.MAX_PHOTO_BYTES) {
            appendLog("photo too large from ${peer.id}: ${start.totalLength} bytes")
            sendAck(peer, start.transferId, PhotoProtocol.STATUS_ERR_TOO_LARGE)
            return
        }
        // A new START discards any stale in-flight buffer for this peer (e.g. a reloaded page).
        photoReceiveStates[peer.id] = PhotoReceiveState(start.transferId, start.totalLength)
        appendLog("photo start from ${peer.id}: ${start.totalLength} bytes")
    }

    private fun handlePhotoData(peer: Peer, value: ByteArray) {
        val data = PhotoProtocol.parseData(value) ?: return
        val state = photoReceiveStates[peer.id] ?: return
        if (data.transferId != state.transferId) return
        state.append(data.payload)
    }

    private fun handlePhotoEnd(peer: Peer, value: ByteArray) {
        val end = PhotoProtocol.parseEnd(value) ?: return
        val state = photoReceiveStates[peer.id] ?: return
        if (end.transferId != state.transferId) return

        if (state.buffer.size() != end.totalLength) {
            appendLog("length mismatch from ${peer.id}: got ${state.buffer.size()}, expected ${end.totalLength}")
            sendAck(peer, end.transferId, PhotoProtocol.STATUS_ERR_LENGTH_MISMATCH)
            photoReceiveStates.remove(peer.id)
            return
        }

        val bytes = state.toByteArray()
        photoReceiveStates.remove(peer.id)

        decodeExecutor.execute {
            val bitmap = try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
            if (bitmap == null) {
                appendLog("decode failed from ${peer.id}")
                sendAck(peer, end.transferId, PhotoProtocol.STATUS_ERR_DECODE_FAILED)
                return@execute
            }
            runOnUiThread {
                photoAdapter.addPhoto(bitmap)
                binding.emptyGalleryText.visibility = android.view.View.GONE
                binding.photoRecyclerView.smoothScrollToPosition(0)
            }
            appendLog("photo received from ${peer.id}")
            sendAck(peer, end.transferId, PhotoProtocol.STATUS_OK)
        }
    }

    private fun handleChatStart(peer: Peer, value: ByteArray) {
        val start = ChatProtocol.parseStart(value) ?: return
        if (start.totalLength > ChatProtocol.MAX_CHAT_BYTES) {
            sendChatAck(peer, start.msgId, ChatProtocol.STATUS_ERR_TOO_LARGE)
            return
        }
        chatReceiveStates[peer.id] = ChatReceiveState(start.msgId, start.totalLength)
    }

    private fun handleChatData(peer: Peer, value: ByteArray) {
        val data = ChatProtocol.parseData(value) ?: return
        val state = chatReceiveStates[peer.id] ?: return
        if (data.msgId != state.msgId) return
        state.append(data.payload)
    }

    private fun handleChatEnd(peer: Peer, value: ByteArray) {
        val end = ChatProtocol.parseEnd(value) ?: return
        val state = chatReceiveStates[peer.id] ?: return
        if (end.msgId != state.msgId) return

        if (state.buffer.size() != end.totalLength) {
            sendChatAck(peer, end.msgId, ChatProtocol.STATUS_ERR_LENGTH_MISMATCH)
            chatReceiveStates.remove(peer.id)
            return
        }

        val bytes = state.toByteArray()
        chatReceiveStates.remove(peer.id)
        val text = String(bytes, Charsets.UTF_8)
        val senderId = peer.id

        sendChatAck(peer, end.msgId, ChatProtocol.STATUS_OK)
        appendLog("chat relay: $text")

        chatExecutor.execute {
            broadcastChat(excludeId = senderId, text = text)
        }
    }

    /** Relays a chat message to every connected guest except the original sender, on either transport. */
    private fun broadcastChat(excludeId: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val msgId = nextChatMsgId()
        for ((id, peer) in connectedPeers) {
            if (id == excludeId) continue
            if (!isPeerReady(peer)) continue
            sendFramedToPeer(
                peer,
                ChatProtocol.OP_CHAT_RECV, ChatProtocol.OP_CHAT_RECV_DATA, ChatProtocol.OP_CHAT_RECV_END,
                msgId, bytes
            )
        }
    }

    private fun handleFileStart(peer: Peer, value: ByteArray) {
        val start = FileProtocol.parseStart(value) ?: return
        if (start.totalLength > FileProtocol.MAX_FILE_BYTES) {
            sendFileAck(peer, start.msgId, FileProtocol.STATUS_ERR_TOO_LARGE)
            return
        }
        fileReceiveStates[peer.id] = FileReceiveState(start.msgId, start.totalLength)
    }

    private fun handleFileData(peer: Peer, value: ByteArray) {
        val data = FileProtocol.parseData(value) ?: return
        val state = fileReceiveStates[peer.id] ?: return
        if (data.msgId != state.msgId) return
        state.append(data.payload)
    }

    private fun handleFileEnd(peer: Peer, value: ByteArray) {
        val end = FileProtocol.parseEnd(value) ?: return
        val state = fileReceiveStates[peer.id] ?: return
        if (end.msgId != state.msgId) return

        if (state.buffer.size() != end.totalLength) {
            sendFileAck(peer, end.msgId, FileProtocol.STATUS_ERR_LENGTH_MISMATCH)
            fileReceiveStates.remove(peer.id)
            return
        }

        val bytes = state.toByteArray()
        fileReceiveStates.remove(peer.id)
        val senderId = peer.id

        sendFileAck(peer, end.msgId, FileProtocol.STATUS_OK)
        appendLog("file relay: ${bytes.size} bytes")

        fileExecutor.execute {
            broadcastFile(excludeId = senderId, bytes = bytes)
        }
    }

    /** Relays a shared file to every connected guest except the original sender, on either transport. */
    private fun broadcastFile(excludeId: String, bytes: ByteArray) {
        val msgId = nextFileMsgId()
        for ((id, peer) in connectedPeers) {
            if (id == excludeId) continue
            if (!isPeerReady(peer)) continue
            sendFramedToPeer(
                peer,
                FileProtocol.OP_FILE_RECV, FileProtocol.OP_FILE_RECV_DATA, FileProtocol.OP_FILE_RECV_END,
                msgId, bytes
            )
        }
    }

    /**
     * Chunked send of one id+bytes payload to a single peer as opStart/opData/opEnd frames,
     * sized to the peer's effective MTU (BLE's actual negotiated MTU, or a large fixed virtual
     * MTU for LAN — see LAN_VIRTUAL_MTU). Shared by chat relay, file relay, and music search
     * results, on both transports.
     */
    private fun sendFramedToPeer(
        peer: Peer,
        opStart: Byte,
        opData: Byte,
        opEnd: Byte,
        id: Byte,
        bytes: ByteArray
    ) {
        val mtu = when (peer) {
            is Peer.Ble -> peer.mtu
            is Peer.Lan -> LAN_VIRTUAL_MTU
        }
        val dataHeaderSize = 4 // opcode + id + seq(u16)
        val payloadSize = (mtu - 3 - dataHeaderSize).coerceAtLeast(1)

        sendToPeer(peer, buildLenFrame(opStart, id, bytes.size))
        var offset = 0
        var seq = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, minOf(offset + payloadSize, bytes.size))
            val frame = ByteArray(4 + chunk.size)
            frame[0] = opData
            frame[1] = id
            frame[2] = ((seq shr 8) and 0xFF).toByte()
            frame[3] = (seq and 0xFF).toByte()
            chunk.copyInto(frame, 4)
            sendToPeer(peer, frame)
            offset += chunk.size
            seq++
        }
        sendToPeer(peer, buildLenFrame(opEnd, id, bytes.size))
    }

    private fun buildLenFrame(opcode: Byte, id: Byte, totalLength: Int): ByteArray {
        val frame = ByteArray(6)
        frame[0] = opcode
        frame[1] = id
        frame[2] = ((totalLength shr 24) and 0xFF).toByte()
        frame[3] = ((totalLength shr 16) and 0xFF).toByte()
        frame[4] = ((totalLength shr 8) and 0xFF).toByte()
        frame[5] = (totalLength and 0xFF).toByte()
        return frame
    }

    @Synchronized
    private fun nextChatMsgId(): Byte {
        chatMsgIdCounter = (chatMsgIdCounter + 1) % 256
        return chatMsgIdCounter.toByte()
    }

    @Synchronized
    private fun nextFileMsgId(): Byte {
        fileMsgIdCounter = (fileMsgIdCounter + 1) % 256
        return fileMsgIdCounter.toByte()
    }

    @Synchronized
    private fun nextMusicMsgId(): Byte {
        musicMsgIdCounter = (musicMsgIdCounter + 1) % 256
        return musicMsgIdCounter.toByte()
    }

    private fun handleMusicQueueStart(peer: Peer, value: ByteArray) {
        val start = MusicProtocol.parseQueueStart(value) ?: return
        if (start.totalLength > MusicProtocol.MAX_QUEUE_PAYLOAD_BYTES) return
        musicQueueReceiveStates[peer.id] =
            MusicReceiveState(start.msgId, start.totalLength, MusicProtocol.MAX_QUEUE_PAYLOAD_BYTES)
    }

    private fun handleMusicQueueData(peer: Peer, value: ByteArray) {
        val data = MusicProtocol.parseQueueData(value) ?: return
        val state = musicQueueReceiveStates[peer.id] ?: return
        if (data.msgId != state.msgId) return
        state.append(data.payload)
    }

    private fun handleMusicQueueEnd(peer: Peer, value: ByteArray) {
        val end = MusicProtocol.parseQueueEnd(value) ?: return
        val state = musicQueueReceiveStates[peer.id] ?: return
        if (end.msgId != state.msgId) return
        musicQueueReceiveStates.remove(peer.id)
        if (state.buffer.size() != end.totalLength) return

        val payloadBytes = state.toByteArray()
        val videoId = try {
            JSONObject(String(payloadBytes, Charsets.UTF_8)).optString("v", "")
        } catch (e: Exception) {
            ""
        }
        if (!youtubeIdPattern.matches(videoId)) {
            sendMusicQueueAck(peer, end.msgId, MusicProtocol.QUEUE_STATUS_INVALID_ID)
            return
        }

        runOnUiThread {
            binding.youtubePlayerWebView.evaluateJavascript("addToQueue('$videoId')", null)
        }
        appendLog("queued video $videoId from ${peer.id}")
        sendMusicQueueAck(peer, end.msgId, MusicProtocol.QUEUE_STATUS_OK)

        // Relay the same title/channel payload to every connected guest (including the
        // sender) so everyone's "recently queued" list stays in sync.
        val broadcastId = nextMusicMsgId()
        for (recipient in connectedPeers.values) {
            if (!isPeerReady(recipient)) continue
            sendFramedToPeer(
                recipient,
                MusicProtocol.OP_MUSIC_QUEUE_RECV, MusicProtocol.OP_MUSIC_QUEUE_RECV_DATA, MusicProtocol.OP_MUSIC_QUEUE_RECV_END,
                broadcastId, payloadBytes
            )
        }
    }

    private fun sendMusicQueueAck(peer: Peer, msgId: Byte, status: Byte) {
        sendToPeer(peer, MusicProtocol.buildQueueAck(msgId, status))
    }

    /** Echoes a sync-latency ping straight back with no processing delay, so the round-trip
     *  time a guest measures reflects transport latency, not app work. */
    private fun handleMusicPing(peer: Peer, value: ByteArray) {
        if (value.size < 2) return
        val pingId = value[1]
        sendToPeer(peer, byteArrayOf(MusicProtocol.OP_MUSIC_PONG, pingId))
    }

    private fun sendAck(peer: Peer, transferId: Byte, status: Byte) {
        sendToPeer(peer, PhotoProtocol.buildAck(transferId, status))
    }

    private fun sendChatAck(peer: Peer, msgId: Byte, status: Byte) {
        sendToPeer(peer, ChatProtocol.buildAck(msgId, status))
    }

    private fun sendFileAck(peer: Peer, msgId: Byte, status: Byte) {
        sendToPeer(peer, FileProtocol.buildAck(msgId, status))
    }

    @SuppressLint("MissingPermission")
    private fun sendToPeer(peer: Peer, payload: ByteArray) {
        when (peer) {
            is Peer.Ble -> {
                if (!peer.notificationsEnabled) return
                val server = gattServer ?: return
                val characteristic = txCharacteristic ?: return

                // BluetoothGattCharacteristic is a single shared mutable object; on API 33+ use
                // the overload that takes the value directly (no shared-state mutation, safe
                // with multiple concurrent centrals). On older APIs, synchronize the legacy
                // set+notify pair.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(peer.device, characteristic, false, payload)
                } else {
                    synchronized(characteristic) {
                        characteristic.value = payload
                        server.notifyCharacteristicChanged(peer.device, characteristic, false)
                    }
                }
            }
            is Peer.Lan -> {
                // socket.send() does a blocking write, and this function gets called from the
                // main thread too (e.g. the music sync loop, driven by the WebView's JS bridge
                // callback) — Android crashes with NetworkOnMainThreadException if that happens
                // directly, so always hop onto a background executor for the actual write.
                lanSendExecutor.execute {
                    try {
                        peer.socket.send(payload)
                    } catch (e: IOException) {
                        // Guest likely disconnected; the WebSocket's onClose will clean the peer up shortly.
                    }
                }
            }
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.statusText.text = text }
    }

    private fun appendLog(line: String) {
        android.util.Log.d("BleLink", line)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        lanServer?.stop()
        stopService(Intent(this, HostingForegroundService::class.java))
        decodeExecutor.shutdown()
        chatExecutor.shutdown()
        fileExecutor.shutdown()
        lanSendExecutor.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
