package com.shreya.cameraapp

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.*
import java.util.UUID
import android.annotation.SuppressLint
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import android.util.Base64
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream


class RemoteConnectionManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _deviceRole = MutableStateFlow(DeviceRole.NONE)
    val deviceRole: StateFlow<DeviceRole> = _deviceRole

    private val _connectedDeviceName = MutableStateFlow("")
    val connectedDeviceName: StateFlow<String> = _connectedDeviceName

    private var messageListener: ((RemoteMessage) -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // UUID for our app - must be same on both phones
    private val APP_UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    private val APP_NAME = "CameraRemote"
    private var lastFrameSentTime = 0L
    private val MIN_FRAME_INTERVAL = 250L  // Minimum 250ms between frames

    companion object {
        private const val TAG = "BluetoothRemote"
    }

    enum class ConnectionState {
        DISCONNECTED, DISCOVERING, CONNECTING, CONNECTED
    }

    enum class DeviceRole {
        NONE, HOST_CAMERA, CONTROLLER_REMOTE
    }

    init {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device")
        } else {
            Log.d(TAG, "Bluetooth RemoteConnectionManager initialized")
        }
    }

    // Phone A - Start as server
    @SuppressLint("MissingPermission")
    fun startAsHost(onReady: (Boolean) -> Unit) {
        Log.d(TAG, "📷 Starting as HOST (Phone A - Camera)")

        if (!checkBluetoothPermissions()) {
            Log.e(TAG, "❌ Bluetooth permissions not granted")
            onReady(false)
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "❌ Bluetooth is OFF or not available")
            onReady(false)
            return
        }

        _deviceRole.value = DeviceRole.HOST_CAMERA
        _connectionState.value = ConnectionState.DISCOVERING

        scope.launch {
            try {
                startBluetoothServer(onReady)
            } catch (e: Exception) {
                Log.e(TAG, "Host startup failed", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                withContext(Dispatchers.Main) { onReady(false) }
            }
        }
    }

    // Phone B - Get list of paired devices
    @SuppressLint("MissingPermission")
    fun startAsController(onDevicesFound: (List<BluetoothDevice>) -> Unit) {
        Log.d(TAG, "📱 Starting as CONTROLLER")

        if (!checkBluetoothPermissions()) {
            Log.e(TAG, "❌ Bluetooth permissions not granted")
            onDevicesFound(emptyList())
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "❌ Bluetooth is OFF")
            onDevicesFound(emptyList())
            return
        }

        _deviceRole.value = DeviceRole.CONTROLLER_REMOTE
        _connectionState.value = ConnectionState.DISCOVERING

        try {
            val pairedDevices = bluetoothAdapter.bondedDevices
            val deviceList = pairedDevices?.toList() ?: emptyList()
            Log.d(TAG, "Found ${deviceList.size} paired devices")
            onDevicesFound(deviceList)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting paired devices", e)
            onDevicesFound(emptyList())
        }
    }

    // Phone B - Connect to selected device
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice, onConnected: (Boolean) -> Unit) {
        Log.d(TAG, "🔗 Connecting to: ${device.name}")
        _connectionState.value = ConnectionState.CONNECTING
        _connectedDeviceName.value = device.name ?: "Unknown"

        scope.launch {
            try {
                connectToBluetoothDevice(device, onConnected)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                withContext(Dispatchers.Main) { onConnected(false) }
            }
        }
    }
    @SuppressLint("MissingPermission")
    private suspend fun startBluetoothServer(onReady: (Boolean) -> Unit) {
        if (!checkBluetoothPermissions()) {
            withContext(Dispatchers.Main) { onReady(false) }
            return
        }

        withContext(Dispatchers.IO) {
            try {
                // Use insecure connection (no pairing required during connection)
                serverSocket = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(
                    APP_NAME, APP_UUID
                )

                Log.d(TAG, "🎧 Bluetooth server listening on insecure RFCOMM...")
                Log.d(TAG, "📱 Server device: ${bluetoothAdapter?.name}")
                withContext(Dispatchers.Main) { onReady(true) }

                // Set timeout to avoid infinite blocking
                serverSocket?.let { server ->
                    socket = withTimeout(120000) { // 2 minute timeout
                        server.accept()
                    }
                }

                Log.d(TAG, "✅ Phone B connected!")
                _connectionState.value = ConnectionState.CONNECTED
                listenForMessages()

            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Server timeout - no connection in 2 minutes")
                _connectionState.value = ConnectionState.DISCONNECTED
                withContext(Dispatchers.Main) { onReady(false) }
            } catch (e: IOException) {
                Log.e(TAG, "Server error", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                withContext(Dispatchers.Main) { onReady(false) }
            }
        }
    }
    @SuppressLint("MissingPermission")
    private suspend fun connectToBluetoothDevice(device: BluetoothDevice, onConnected: (Boolean) -> Unit) {
        if (!checkBluetoothPermissions()) {
            withContext(Dispatchers.Main) { onConnected(false) }
            return
        }

        withContext(Dispatchers.IO) {
            try {
                bluetoothAdapter?.cancelDiscovery()

                // Try insecure connection first
                socket = try {
                    Log.d(TAG, "Trying insecure RFCOMM connection...")
                    device.createInsecureRfcommSocketToServiceRecord(APP_UUID)
                } catch (e: Exception) {
                    Log.w(TAG, "Insecure failed, trying secure...")
                    device.createRfcommSocketToServiceRecord(APP_UUID)
                }

                socket?.connect()

                Log.d(TAG, "✅ Connected to Phone A!")
                _connectionState.value = ConnectionState.CONNECTED
                withContext(Dispatchers.Main) { onConnected(true) }
                listenForMessages()

            } catch (e: IOException) {
                Log.e(TAG, "Standard connection failed, trying fallback method...")

                // Fallback: Use reflection to try alternate connection method
                try {
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    socket = method.invoke(device, 1) as BluetoothSocket
                    socket?.connect()

                    Log.d(TAG, "✅ Connected via fallback method!")
                    _connectionState.value = ConnectionState.CONNECTED
                    withContext(Dispatchers.Main) { onConnected(true) }
                    listenForMessages()

                } catch (fallbackError: Exception) {
                    Log.e(TAG, "All connection methods failed", fallbackError)
                    _connectionState.value = ConnectionState.DISCONNECTED
                    withContext(Dispatchers.Main) { onConnected(false) }
                }
            }
        }
    }

    private suspend fun listenForMessages() {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = socket?.inputStream
                val reader = BufferedReader(InputStreamReader(inputStream))

                while (_connectionState.value == ConnectionState.CONNECTED) {
                    val message = reader.readLine() ?: break
                    try {
                        val remoteMessage = parseMessage(message)
                        withContext(Dispatchers.Main) {
                            messageListener?.invoke(remoteMessage)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Message parsing error", e)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Listen error", e)
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    fun sendMessage(message: RemoteMessage) {
        Log.d(TAG, "=== sendMessage called ===")
        Log.d(TAG, "Message type: ${message.type}")
        Log.d(TAG, "Connection state: ${_connectionState.value}")
        Log.d(TAG, "Socket is null: ${socket == null}")
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.e(TAG, "Cannot send - not connected! State: ${_connectionState.value}")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                if (socket == null) {
                    Log.e(TAG, "ERROR: Socket is NULL - cannot send message")
                    return@launch
                }

                val json = message.toJson()
                Log.d(TAG, "Sending JSON: $json")

                val outputStream = socket?.outputStream
                if (outputStream == null) {
                    Log.e(TAG, "ERROR: OutputStream is NULL")
                    return@launch
                }

                outputStream.write("$json\n".toByteArray())
                outputStream.flush()
                Log.d(TAG, "Message sent successfully!")

            } catch (e: IOException) {
                Log.e(TAG, "Send error: ${e.message}", e)
            }
        }
    }
    fun sendPreviewFrame(bitmap: Bitmap) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameSentTime < MIN_FRAME_INTERVAL) {
            return  // Skip this frame
        }
        lastFrameSentTime = currentTime
        scope.launch(Dispatchers.IO) {
            try {
                // Resize bitmap to reduce size
                val resized = Bitmap.createScaledBitmap(
                    bitmap,
                    bitmap.width / 2,  // Half resolution for speed
                    bitmap.height / 2,
                    true
                )

                val stream = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, 40, stream) // Lower quality = faster
                val byteArray = stream.toByteArray()
                val base64Image = Base64.encodeToString(byteArray, Base64.NO_WRAP)

                val message = RemoteMessage(
                    type = MessageType.PREVIEW_FRAME,
                    value = base64Image
                )
                sendMessage(message)

                resized.recycle() // Free memory
            } catch (e: Exception) {
                Log.e(TAG, "Preview frame send failed", e)
            }
        }
    }

    fun setMessageListener(listener: (RemoteMessage) -> Unit) {
        messageListener = listener
    }

    private fun parseMessage(json: String): RemoteMessage {
        val obj = JSONObject(json)
        return RemoteMessage(
            type = MessageType.valueOf(obj.getString("type")),
            action = obj.optString("action"),
            value = obj.optString("value"),
            timestamp = obj.getLong("timestamp")
        )
    }

    fun disconnect() {
        scope.launch(Dispatchers.IO) {
            try {
                socket?.close()
                serverSocket?.close()
                socket = null
                serverSocket = null

                _connectionState.value = ConnectionState.DISCONNECTED
                _deviceRole.value = DeviceRole.NONE
                _connectedDeviceName.value = ""
                Log.d(TAG, "🔌 Disconnected")
            } catch (e: IOException) {
                Log.e(TAG, "Disconnect error", e)
            }
        }
    }

    private fun checkBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            val hasScan = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED

            if (!hasConnect || !hasScan) {
                Log.e(TAG, "Missing Bluetooth permissions")
            }
            return hasConnect && hasScan
        }
        return true // Old Android versions don't need runtime permissions
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}

// Keep existing enums and data class
enum class MessageType {
    CAPTURE_PHOTO, SWITCH_CAMERA, TOGGLE_FLASH, ZOOM_IN, ZOOM_OUT, SET_ZOOM,
    CHANGE_MODE, APPLY_SUGGESTION, PREVIEW_FRAME, CAMERA_STATE, SUGGESTION_UPDATE, PING, ACK
}

data class RemoteMessage(
    val type: MessageType,
    val action: String = "",
    val value: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("type", type.name)
            put("action", action)
            put("value", value)
            put("timestamp", timestamp)
        }.toString()
    }
}