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
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.blelink.app.databinding.ActivityMainBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Acts as a BLE peripheral exposing a UART-style GATT service:
 *  - RX characteristic: central (e.g. a browser via Web Bluetooth) writes to this;
 *    the phone receives photo-transfer frames here (see PhotoProtocol).
 *  - TX characteristic: phone notifies the central on this with PHOTO_ACK frames.
 * Web Bluetooth can only act as a GATT client/central, never a peripheral,
 * which is why the phone must be the peripheral side. Multiple browsers can
 * be connected at once; BLE's own hardware connection ceiling is the natural
 * throttle, so no explicit connection-limit logic is needed here.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // write (central -> phone)
        val TX_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // notify (phone -> central)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val WEB_URL = "https://ollielynas.com/blelink/"

        const val BLE_DEVICE_NAME = "BleLink"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var photoAdapter: PhotoAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // Per-device state, keyed by device address, to support multiple concurrent centrals.
    private data class DeviceState(
        val device: BluetoothDevice,
        var notificationsEnabled: Boolean = false,
        var mtu: Int = 23
    )

    private val connectedDevices = ConcurrentHashMap<String, DeviceState>()
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
    // Serializes chat relaying so a single browser never receives interleaved
    // DATA frames from two different chat messages on the same characteristic.
    private val chatExecutor = Executors.newSingleThreadExecutor()
    // Separate from chatExecutor so a large file relay never blocks plain text chat messages
    // (or vice versa) from going out promptly.
    private val fileExecutor = Executors.newSingleThreadExecutor()

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

        showQrCode()
        requestPermissionsAndStart()
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
     * position + playing/paused) to every connected browser, so a guest who taps "Join Audio"
     * can play the same video in their own tab and stay roughly — not sample-accurately —
     * in step. BLE latency and per-guest buffering make tighter sync impractical.
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
        if (connectedDevices.isEmpty()) return
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
        for (recipient in connectedDevices.values) {
            if (!recipient.notificationsEnabled) continue
            sendFramedToDevice(
                recipient.device, recipient.mtu,
                MusicProtocol.OP_MUSIC_SYNC, MusicProtocol.OP_MUSIC_SYNC_DATA, MusicProtocol.OP_MUSIC_SYNC_END,
                syncId, bytes
            )
        }
    }

    private fun showQrCode() {
        try {
            val size = 512
            val matrix = QRCodeWriter().encode(WEB_URL, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            binding.qrImage.setImageBitmap(bitmap)
        } catch (e: Exception) {
            appendLog("QR generation failed: ${e.message}")
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
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
        val count = connectedDevices.size
        val connected = count > 0
        setStatus(
            if (!connected) "Waiting for a browser to connect"
            else "Connected: $count device(s)"
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

    @SuppressLint("MissingPermission")
    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices[device.address] = DeviceState(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device.address)
                    photoReceiveStates.remove(device.address)
                    chatReceiveStates.remove(device.address)
                    fileReceiveStates.remove(device.address)
                    musicQueueReceiveStates.remove(device.address)
                }
            }
            updateStatusFromConnectionCount()
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            connectedDevices[device.address]?.mtu = mtu
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
                when (value[0]) {
                    PhotoProtocol.OP_PHOTO_START -> handlePhotoStart(device, value)
                    PhotoProtocol.OP_PHOTO_DATA -> handlePhotoData(device, value)
                    PhotoProtocol.OP_PHOTO_END -> handlePhotoEnd(device, value)
                    ChatProtocol.OP_CHAT_SEND -> handleChatStart(device, value)
                    ChatProtocol.OP_CHAT_SEND_DATA -> handleChatData(device, value)
                    ChatProtocol.OP_CHAT_SEND_END -> handleChatEnd(device, value)
                    FileProtocol.OP_FILE_SEND -> handleFileStart(device, value)
                    FileProtocol.OP_FILE_SEND_DATA -> handleFileData(device, value)
                    FileProtocol.OP_FILE_SEND_END -> handleFileEnd(device, value)
                    MusicProtocol.OP_MUSIC_QUEUE -> handleMusicQueueStart(device, value)
                    MusicProtocol.OP_MUSIC_QUEUE_DATA -> handleMusicQueueData(device, value)
                    MusicProtocol.OP_MUSIC_QUEUE_END -> handleMusicQueueEnd(device, value)
                    MusicProtocol.OP_MUSIC_PING -> handleMusicPing(device, value)
                }
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
                connectedDevices[device.address]?.notificationsEnabled =
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    private fun handlePhotoStart(device: BluetoothDevice, value: ByteArray) {
        val start = PhotoProtocol.parseStart(value) ?: return
        if (start.totalLength > PhotoProtocol.MAX_PHOTO_BYTES) {
            appendLog("photo too large from ${device.address}: ${start.totalLength} bytes")
            sendAck(device, start.transferId, PhotoProtocol.STATUS_ERR_TOO_LARGE)
            return
        }
        // A new START discards any stale in-flight buffer for this device (e.g. a reloaded page).
        photoReceiveStates[device.address] = PhotoReceiveState(start.transferId, start.totalLength)
        appendLog("photo start from ${device.address}: ${start.totalLength} bytes")
    }

    private fun handlePhotoData(device: BluetoothDevice, value: ByteArray) {
        val data = PhotoProtocol.parseData(value) ?: return
        val state = photoReceiveStates[device.address] ?: return
        if (data.transferId != state.transferId) return
        state.append(data.payload)
    }

    private fun handlePhotoEnd(device: BluetoothDevice, value: ByteArray) {
        val end = PhotoProtocol.parseEnd(value) ?: return
        val state = photoReceiveStates[device.address] ?: return
        if (end.transferId != state.transferId) return

        if (state.buffer.size() != end.totalLength) {
            appendLog("length mismatch from ${device.address}: got ${state.buffer.size()}, expected ${end.totalLength}")
            sendAck(device, end.transferId, PhotoProtocol.STATUS_ERR_LENGTH_MISMATCH)
            photoReceiveStates.remove(device.address)
            return
        }

        val bytes = state.toByteArray()
        photoReceiveStates.remove(device.address)

        decodeExecutor.execute {
            val bitmap = try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
            if (bitmap == null) {
                appendLog("decode failed from ${device.address}")
                sendAck(device, end.transferId, PhotoProtocol.STATUS_ERR_DECODE_FAILED)
                return@execute
            }
            runOnUiThread {
                photoAdapter.addPhoto(bitmap)
                binding.emptyGalleryText.visibility = android.view.View.GONE
                binding.photoRecyclerView.smoothScrollToPosition(0)
            }
            appendLog("photo received from ${device.address}")
            sendAck(device, end.transferId, PhotoProtocol.STATUS_OK)
        }
    }

    private fun handleChatStart(device: BluetoothDevice, value: ByteArray) {
        val start = ChatProtocol.parseStart(value) ?: return
        if (start.totalLength > ChatProtocol.MAX_CHAT_BYTES) {
            sendChatAck(device, start.msgId, ChatProtocol.STATUS_ERR_TOO_LARGE)
            return
        }
        chatReceiveStates[device.address] = ChatReceiveState(start.msgId, start.totalLength)
    }

    private fun handleChatData(device: BluetoothDevice, value: ByteArray) {
        val data = ChatProtocol.parseData(value) ?: return
        val state = chatReceiveStates[device.address] ?: return
        if (data.msgId != state.msgId) return
        state.append(data.payload)
    }

    private fun handleChatEnd(device: BluetoothDevice, value: ByteArray) {
        val end = ChatProtocol.parseEnd(value) ?: return
        val state = chatReceiveStates[device.address] ?: return
        if (end.msgId != state.msgId) return

        if (state.buffer.size() != end.totalLength) {
            sendChatAck(device, end.msgId, ChatProtocol.STATUS_ERR_LENGTH_MISMATCH)
            chatReceiveStates.remove(device.address)
            return
        }

        val bytes = state.toByteArray()
        chatReceiveStates.remove(device.address)
        val text = String(bytes, Charsets.UTF_8)
        val senderAddress = device.address

        sendChatAck(device, end.msgId, ChatProtocol.STATUS_OK)
        appendLog("chat relay: $text")

        chatExecutor.execute {
            broadcastChat(excludeAddress = senderAddress, text = text)
        }
    }

    /** Relays a chat message to every connected device except the original sender. */
    private fun broadcastChat(excludeAddress: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val msgId = nextChatMsgId()
        for ((address, state) in connectedDevices) {
            if (address == excludeAddress) continue
            if (!state.notificationsEnabled) continue
            sendFramedToDevice(
                state.device, state.mtu,
                ChatProtocol.OP_CHAT_RECV, ChatProtocol.OP_CHAT_RECV_DATA, ChatProtocol.OP_CHAT_RECV_END,
                msgId, bytes
            )
        }
    }

    private fun handleFileStart(device: BluetoothDevice, value: ByteArray) {
        val start = FileProtocol.parseStart(value) ?: return
        if (start.totalLength > FileProtocol.MAX_FILE_BYTES) {
            sendFileAck(device, start.msgId, FileProtocol.STATUS_ERR_TOO_LARGE)
            return
        }
        fileReceiveStates[device.address] = FileReceiveState(start.msgId, start.totalLength)
    }

    private fun handleFileData(device: BluetoothDevice, value: ByteArray) {
        val data = FileProtocol.parseData(value) ?: return
        val state = fileReceiveStates[device.address] ?: return
        if (data.msgId != state.msgId) return
        state.append(data.payload)
    }

    private fun handleFileEnd(device: BluetoothDevice, value: ByteArray) {
        val end = FileProtocol.parseEnd(value) ?: return
        val state = fileReceiveStates[device.address] ?: return
        if (end.msgId != state.msgId) return

        if (state.buffer.size() != end.totalLength) {
            sendFileAck(device, end.msgId, FileProtocol.STATUS_ERR_LENGTH_MISMATCH)
            fileReceiveStates.remove(device.address)
            return
        }

        val bytes = state.toByteArray()
        fileReceiveStates.remove(device.address)
        val senderAddress = device.address

        sendFileAck(device, end.msgId, FileProtocol.STATUS_OK)
        appendLog("file relay: ${bytes.size} bytes")

        fileExecutor.execute {
            broadcastFile(excludeAddress = senderAddress, bytes = bytes)
        }
    }

    /** Relays a shared file to every connected device except the original sender. */
    private fun broadcastFile(excludeAddress: String, bytes: ByteArray) {
        val msgId = nextFileMsgId()
        for ((address, state) in connectedDevices) {
            if (address == excludeAddress) continue
            if (!state.notificationsEnabled) continue
            sendFramedToDevice(
                state.device, state.mtu,
                FileProtocol.OP_FILE_RECV, FileProtocol.OP_FILE_RECV_DATA, FileProtocol.OP_FILE_RECV_END,
                msgId, bytes
            )
        }
    }

    /**
     * Chunked send of one id+bytes payload to a single device as opStart/opData/opEnd
     * frames, sized to the device's actual negotiated MTU when known (falls back to the
     * guaranteed-safe unnegotiated default: 23-byte ATT MTU, 3-byte header, 20 usable).
     * Shared by chat relay, file relay, and music search results.
     */
    private fun sendFramedToDevice(
        device: BluetoothDevice,
        mtu: Int,
        opStart: Byte,
        opData: Byte,
        opEnd: Byte,
        id: Byte,
        bytes: ByteArray
    ) {
        val dataHeaderSize = 4 // opcode + id + seq(u16)
        val payloadSize = (mtu - 3 - dataHeaderSize).coerceAtLeast(1)

        notifyDevice(device, buildLenFrame(opStart, id, bytes.size))
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
            notifyDevice(device, frame)
            offset += chunk.size
            seq++
        }
        notifyDevice(device, buildLenFrame(opEnd, id, bytes.size))
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

    private fun handleMusicQueueStart(device: BluetoothDevice, value: ByteArray) {
        val start = MusicProtocol.parseQueueStart(value) ?: return
        if (start.totalLength > MusicProtocol.MAX_QUEUE_PAYLOAD_BYTES) return
        musicQueueReceiveStates[device.address] =
            MusicReceiveState(start.msgId, start.totalLength, MusicProtocol.MAX_QUEUE_PAYLOAD_BYTES)
    }

    private fun handleMusicQueueData(device: BluetoothDevice, value: ByteArray) {
        val data = MusicProtocol.parseQueueData(value) ?: return
        val state = musicQueueReceiveStates[device.address] ?: return
        if (data.msgId != state.msgId) return
        state.append(data.payload)
    }

    private fun handleMusicQueueEnd(device: BluetoothDevice, value: ByteArray) {
        val end = MusicProtocol.parseQueueEnd(value) ?: return
        val state = musicQueueReceiveStates[device.address] ?: return
        if (end.msgId != state.msgId) return
        musicQueueReceiveStates.remove(device.address)
        if (state.buffer.size() != end.totalLength) return

        val payloadBytes = state.toByteArray()
        val videoId = try {
            JSONObject(String(payloadBytes, Charsets.UTF_8)).optString("v", "")
        } catch (e: Exception) {
            ""
        }
        if (!youtubeIdPattern.matches(videoId)) {
            sendMusicQueueAck(device, end.msgId, MusicProtocol.QUEUE_STATUS_INVALID_ID)
            return
        }

        runOnUiThread {
            binding.youtubePlayerWebView.evaluateJavascript("addToQueue('$videoId')", null)
        }
        appendLog("queued video $videoId from ${device.address}")
        sendMusicQueueAck(device, end.msgId, MusicProtocol.QUEUE_STATUS_OK)

        // Relay the same title/channel payload to every connected browser (including the
        // sender) so everyone's "recently queued" list stays in sync.
        val broadcastId = nextMusicMsgId()
        for (recipient in connectedDevices.values) {
            if (!recipient.notificationsEnabled) continue
            sendFramedToDevice(
                recipient.device, recipient.mtu,
                MusicProtocol.OP_MUSIC_QUEUE_RECV, MusicProtocol.OP_MUSIC_QUEUE_RECV_DATA, MusicProtocol.OP_MUSIC_QUEUE_RECV_END,
                broadcastId, payloadBytes
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendMusicQueueAck(device: BluetoothDevice, msgId: Byte, status: Byte) {
        notifyDevice(device, MusicProtocol.buildQueueAck(msgId, status))
    }

    /** Echoes a sync-latency ping straight back with no processing delay, so the round-trip
     *  time a browser measures reflects transport latency, not app work. */
    private fun handleMusicPing(device: BluetoothDevice, value: ByteArray) {
        if (value.size < 2) return
        val pingId = value[1]
        notifyDevice(device, byteArrayOf(MusicProtocol.OP_MUSIC_PONG, pingId))
    }

    @SuppressLint("MissingPermission")
    private fun sendAck(device: BluetoothDevice, transferId: Byte, status: Byte) {
        notifyDevice(device, PhotoProtocol.buildAck(transferId, status))
    }

    @SuppressLint("MissingPermission")
    private fun sendChatAck(device: BluetoothDevice, msgId: Byte, status: Byte) {
        notifyDevice(device, ChatProtocol.buildAck(msgId, status))
    }

    @SuppressLint("MissingPermission")
    private fun sendFileAck(device: BluetoothDevice, msgId: Byte, status: Byte) {
        notifyDevice(device, FileProtocol.buildAck(msgId, status))
    }

    @SuppressLint("MissingPermission")
    private fun notifyDevice(device: BluetoothDevice, payload: ByteArray) {
        val server = gattServer ?: return
        val characteristic = txCharacteristic ?: return
        val deviceState = connectedDevices[device.address] ?: return
        if (!deviceState.notificationsEnabled) return

        // BluetoothGattCharacteristic is a single shared mutable object; on API 33+ use the
        // overload that takes the value directly (no shared-state mutation, safe with multiple
        // concurrent centrals). On older APIs, synchronize the legacy set+notify pair.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, payload)
        } else {
            synchronized(characteristic) {
                characteristic.value = payload
                server.notifyCharacteristicChanged(device, characteristic, false)
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
        decodeExecutor.shutdown()
        chatExecutor.shutdown()
        fileExecutor.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
