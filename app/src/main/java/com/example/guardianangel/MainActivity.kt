package com.example.guardianangel

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.guardianangel.ui.theme.GuardianAngelTheme
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val currentScreen = mutableStateOf<Screen>(Screen.Main)
    private val discoveredDevices = mutableStateListOf<NsdServiceInfo>()
    private val connectedGuardians = mutableStateListOf<GuardianConnection>()
    private val connectedWardName = mutableStateOf<String?>(null)
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serverSocket: ServerSocket? = null
    private var pttServerSocket: ServerSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var pttAudioTrack: AudioTrack? = null
    private var isStreaming = false
    private var guardianSocket: Socket? = null
    private var isPlayingAudio = false
    private var isPttReceiving = false
    private val localIpAddress = mutableStateOf("")
    private var foregroundService: ForegroundService? = null
    private var serviceBound = false
    private var clientSockets = mutableListOf<Socket>()
    val streamVolume = mutableStateOf(0.5f)
    private var lastConnectionTime = 0L
    private var connectionMonitorThread: Thread? = null
    private var isMonitoringConnection = false
    private var pttSocket: Socket? = null
    private var pttAudioRecord: AudioRecord? = null
    private var isPttActive = mutableStateOf(false)
    private var connectedWardIp = mutableStateOf<String?>(null)
    private var isConnectedToWard = mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ForegroundService.LocalBinder
            foregroundService = binder?.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            foregroundService = null
            serviceBound = false
        }
    }

    companion object {
        private const val TAG = "GuardianAngel"
        private const val AUDIO_PORT = 5353
        private const val PTT_PORT = 5354
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val NOTIFICATION_CHANNEL_ID = "guardian_angel_channel"
        const val ALERT_CHANNEL_ID = "guardian_angel_alert_channel"
        const val NOTIFICATION_ID = 1
        const val ALERT_NOTIFICATION_ID = 2
        private const val REQUEST_CODE_NOTIFICATION = 101
        private const val CONNECTION_TIMEOUT_MS = 30000L // 30 seconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        createNotificationChannel()
        requestNotificationPermission()
        localIpAddress.value = getLocalIpAddress()

        setContent {
            GuardianAngelTheme {
                when (val screen = currentScreen.value) {
                    Screen.Main -> MainScreen(
                        onGuardianClick = { currentScreen.value = Screen.Guardian },
                        onWardClick = { currentScreen.value = Screen.Ward }
                    )
                    Screen.Guardian -> GuardianScreen(
                        devices = discoveredDevices.map { it.serviceName },
                        onBack = {
                            currentScreen.value = Screen.Main
                            stopDiscovery()
                            stopAudioPlayback()
                            stopForegroundService()
                        },
                        onManualConnect = { ip -> connectToWardByIp(ip, null) },
                        onDeviceConnect = { deviceName -> connectToWard(deviceName) }
                    )
                    is Screen.GuardianConnected -> GuardianConnectedScreen(
                        wardName = screen.wardName,
                        volume = streamVolume.value,
                        isPttActive = isPttActive.value,
                        isConnected = isConnectedToWard.value,
                        onVolumeChange = { newVolume ->
                            streamVolume.value = newVolume
                            audioTrack?.setVolume(newVolume)
                        },
                        onPttPress = {
                            connectedWardIp.value?.let { startPushToTalk(it) }
                        },
                        onPttRelease = {
                            stopPushToTalk()
                        },
                        onBack = {
                            currentScreen.value = Screen.Main
                            stopAudioPlayback()
                            stopForegroundService()
                        }
                    )
                    Screen.Ward -> WardScreen(
                        connectedGuardians = connectedGuardians.map { it.deviceName },
                        localIp = localIpAddress.value,
                        onBack = {
                            currentScreen.value = Screen.Main
                            stopAudioBroadcast()
                            stopForegroundService()
                        }
                    )
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) { // Build.VERSION_CODES.O
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                // Use reflection to avoid compile-time dependency on API 26+
                val channelClass = Class.forName("android.app.NotificationChannel")
                val constructor = channelClass.getConstructor(String::class.java, CharSequence::class.java, Int::class.javaPrimitiveType)

                // Service notification channel
                val channel = constructor.newInstance(NOTIFICATION_CHANNEL_ID, "Guardian Angel Service", 2) // IMPORTANCE_LOW = 2
                val descField = channelClass.getMethod("setDescription", String::class.java)
                descField.invoke(channel, "Keeps the audio streaming service running")
                val createMethod = NotificationManager::class.java.getMethod("createNotificationChannel", channelClass)
                createMethod.invoke(notificationManager, channel)

                // Alert notification channel with high importance and DND bypass
                val alertChannel = constructor.newInstance(ALERT_CHANNEL_ID, "Connection Alerts", 5) // IMPORTANCE_HIGH = 5
                descField.invoke(alertChannel, "Alerts when connection is lost")

                // Set sound
                val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val setSoundMethod = channelClass.getMethod("setSound", Uri::class.java, AudioAttributes::class.java)
                setSoundMethod.invoke(alertChannel, defaultSoundUri, audioAttributes)

                // Set to bypass DND
                val setBypassDndMethod = channelClass.getMethod("setBypassDnd", Boolean::class.javaPrimitiveType)
                setBypassDndMethod.invoke(alertChannel, true)

                createMethod.invoke(notificationManager, alertChannel)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating notification channel: ${e.message}")
            }
        }
    }

    private fun startForegroundService(title: String, text: String) {
        val intent = Intent(this, ForegroundService::class.java).apply {
            putExtra("title", title)
            putExtra("text", text)
        }

        // Start and bind to foreground service with API level check
        if (Build.VERSION.SDK_INT >= 26) { // Build.VERSION_CODES.O
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopForegroundService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        stopService(Intent(this, ForegroundService::class.java))
        foregroundService = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP: ${e.message}")
        }
        return "Unknown"
    }

    private fun startPttServer() {
        thread {
            try {
                pttServerSocket = ServerSocket(PTT_PORT)
                Log.d(TAG, "PTT server socket started on port $PTT_PORT")

                while (pttServerSocket != null && !pttServerSocket!!.isClosed) {
                    val client = pttServerSocket?.accept()
                    client?.let { socket ->
                        Log.d(TAG, "PTT client connected")

                        thread {
                            try {
                                isPttReceiving = true  // Mute ward's mic during PTT
                                Log.d(TAG, "Ward microphone muted for PTT")

                                val bufferSize = AudioTrack.getMinBufferSize(
                                    SAMPLE_RATE,
                                    CHANNEL_CONFIG_OUT,
                                    AUDIO_FORMAT
                                )

                                pttAudioTrack = AudioTrack(
                                    AudioManager.STREAM_MUSIC,
                                    SAMPLE_RATE,
                                    CHANNEL_CONFIG_OUT,
                                    AUDIO_FORMAT,
                                    bufferSize,
                                    AudioTrack.MODE_STREAM
                                )

                                pttAudioTrack?.play()
                                val buffer = ByteArray(bufferSize)
                                val inputStream = socket.getInputStream()

                                while (!socket.isClosed && socket.isConnected) {
                                    val bytesRead = inputStream.read(buffer)
                                    if (bytesRead == -1) break
                                    pttAudioTrack?.write(buffer, 0, bytesRead)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "PTT playback error: ${e.message}")
                            } finally {
                                isPttReceiving = false  // Unmute ward's mic
                                Log.d(TAG, "Ward microphone unmuted")

                                pttAudioTrack?.stop()
                                pttAudioTrack?.release()
                                pttAudioTrack = null
                                try { socket.close() } catch (e: Exception) { }
                                Log.d(TAG, "PTT client disconnected")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PTT server error: ${e.message}")
            }
        }
    }

    internal fun startBroadcasting() {
        // Check permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermission()
            return
        }

        val deviceName = Build.MODEL
        startForegroundService("Ward Active", "Waiting for guardian connection...")

        // Start PTT server
        startPttServer()

        // Start server socket for audio streaming
        thread {
            try {
                serverSocket = ServerSocket(AUDIO_PORT)
                Log.d(TAG, "Server socket started on port $AUDIO_PORT")

                val serviceInfo = NsdServiceInfo().apply {
                    serviceName = "ward_$deviceName"
                    serviceType = "_ward._tcp"
                    port = AUDIO_PORT
                }

                registrationListener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Service registered: ${nsdServiceInfo.serviceName}")
                    }
                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Registration failed: $errorCode")
                    }
                    override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                        Log.d(TAG, "Service unregistered")
                    }
                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Unregistration failed: $errorCode")
                    }
                }

                nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

                // Wait for Guardian to connect
                while (serverSocket != null && !serverSocket!!.isClosed) {
                    val client = serverSocket?.accept()
                    client?.let { socket ->
                        val guardianIp = socket.inetAddress.hostAddress ?: "Unknown"
                        Log.d(TAG, "Guardian connected from $guardianIp")

                        // Read device name from guardian
                        thread {
                            try {
                                val inputStream = socket.getInputStream()
                                val nameBytes = ByteArray(256)
                                val nameLength = inputStream.read(nameBytes)
                                val guardianName = if (nameLength > 0) {
                                    String(nameBytes, 0, nameLength).trim()
                                } else {
                                    guardianIp
                                }

                                val connection = GuardianConnection(guardianName, guardianIp, socket)
                                runOnUiThread {
                                    // Check for existing connection with same IP and remove it
                                    connectedGuardians.removeAll { it.ipAddress == guardianIp }
                                    connectedGuardians.add(connection)
                                }

                                synchronized(clientSockets) {
                                    clientSockets.add(socket)
                                }

                                // Start audio capture only if not already streaming
                                if (!isStreaming) {
                                    startAudioCapture()
                                }

                                // Monitor this socket for disconnection
                                monitorConnection(socket, guardianName)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error handling guardian connection: ${e.message}")
                                runOnUiThread {
                                    connectedGuardians.removeAll { it.socket == socket }
                                    clientSockets.remove(socket)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private fun amplifyAudio(buffer: ByteArray, bytesRead: Int, gain: Float = 3.0f) {
        // Amplify 16-bit PCM audio
        var i = 0
        while (i < bytesRead - 1) {
            // Read 16-bit sample (little endian)
            val sample = (buffer[i].toInt() and 0xFF) or ((buffer[i + 1].toInt() and 0xFF) shl 8)
            val signedSample = if (sample > 32767) sample - 65536 else sample

            // Apply gain
            var amplified = (signedSample * gain).toInt()

            // Clamp to prevent distortion
            amplified = amplified.coerceIn(-32768, 32767)

            // Convert back to bytes
            buffer[i] = (amplified and 0xFF).toByte()
            buffer[i + 1] = ((amplified shr 8) and 0xFF).toByte()

            i += 2
        }
    }

    private fun startAudioCapture() {
        // Check permission before starting recording
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Record audio permission not granted")
            return
        }

        isStreaming = true
        thread {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT
                ) * 2  // Larger buffer for better quality

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // Better pickup for voice
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    bufferSize
                )

                audioRecord?.startRecording()
                val buffer = ByteArray(bufferSize)

                while (isStreaming) {
                    val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (bytesRead > 0) {
                        // Skip broadcasting if PTT is active (to prevent feedback)
                        if (!isPttReceiving) {
                            // Amplify audio before sending
                            amplifyAudio(buffer, bytesRead, 3.5f)

                            // Broadcast to all connected guardians
                            val socketsToRemove = mutableListOf<Socket>()
                            synchronized(clientSockets) {
                                clientSockets.forEach { socket ->
                                    try {
                                        socket.getOutputStream().write(buffer, 0, bytesRead)
                                        socket.getOutputStream().flush()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to write to guardian: ${e.message}")
                                        socketsToRemove.add(socket)
                                    }
                                }
                            }

                            // Remove dead sockets
                            if (socketsToRemove.isNotEmpty()) {
                                synchronized(clientSockets) {
                                    socketsToRemove.forEach { socket ->
                                        clientSockets.remove(socket)
                                        try { socket.close() } catch (e: Exception) { }
                                    }
                                }
                                runOnUiThread {
                                    socketsToRemove.forEach { socket ->
                                        connectedGuardians.removeAll { it.socket == socket }
                                    }
                                }
                            }
                        } else {
                            // PTT is active, just consume the buffer without sending
                            Thread.sleep(10)
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception - permission denied: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error: ${e.message}")
            } finally {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            }
        }
    }

    private fun monitorConnection(socket: Socket, guardianName: String) {
        thread {
            try {
                val inputStream = socket.getInputStream()
                val buffer = ByteArray(1)

                // Continuously monitor the socket
                while (!socket.isClosed && socket.isConnected) {
                    // Try to read 1 byte with timeout to detect disconnection
                    try {
                        socket.soTimeout = 5000 // 5 second timeout
                        val read = inputStream.read(buffer, 0, 0)
                        if (read == -1) {
                            // Connection closed by remote
                            break
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout is expected, continue monitoring
                        continue
                    } catch (e: Exception) {
                        // Any other exception means connection is broken
                        Log.d(TAG, "Connection lost: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Connection monitoring ended: ${e.message}")
            } finally {
                // Clean up this connection
                synchronized(clientSockets) {
                    clientSockets.remove(socket)
                }
                runOnUiThread {
                    connectedGuardians.removeAll { it.socket == socket }
                }
                try { socket.close() } catch (e: Exception) { }
                Log.d(TAG, "Guardian $guardianName disconnected")
            }
        }
    }

    private fun stopAudioBroadcast() {
        isStreaming = false

        // Close all client sockets first
        synchronized(clientSockets) {
            clientSockets.forEach {
                try { it.close() } catch (e: Exception) { }
            }
            clientSockets.clear()
        }

        // Give audio thread time to finish
        Thread.sleep(100)

        // Stop and release audio resources safely
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record: ${e.message}")
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio record: ${e.message}")
        }
        audioRecord = null

        // Stop PTT audio
        try {
            pttAudioTrack?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping PTT audio: ${e.message}")
        }
        try {
            pttAudioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing PTT audio: ${e.message}")
        }
        pttAudioTrack = null

        // Close server sockets
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null

        try {
            pttServerSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PTT server socket: ${e.message}")
        }
        pttServerSocket = null

        // Clear UI state
        runOnUiThread {
            connectedGuardians.clear()
        }

        // Unregister NSD service
        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
                Log.d(TAG, "Unregistering NSD service")
            }
            registrationListener = null
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering service: ${e.message}")
        }
    }

    internal fun startDiscovery() {
        val resolveQueue = mutableListOf<NsdServiceInfo>()
        var isResolving = false

        fun resolveNext() {
            if (resolveQueue.isEmpty() || isResolving) return

            isResolving = true
            val serviceInfo = resolveQueue.removeAt(0)

            try {
                nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        isResolving = false
                        resolveNext()
                    }
                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                        Log.d(TAG, "Service resolved: ${resolvedInfo.serviceName} at ${resolvedInfo.host}:${resolvedInfo.port}")
                        runOnUiThread {
                            if (!discoveredDevices.any { it.serviceName == resolvedInfo.serviceName }) {
                                discoveredDevices.add(resolvedInfo)
                            }
                        }
                        isResolving = false
                        resolveNext()
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving service: ${e.message}")
                isResolving = false
                resolveNext()
            }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                runOnUiThread {
                    // Retry discovery after a delay
                    thread {
                        Thread.sleep(2000)
                        runOnUiThread {
                            if (currentScreen.value == Screen.Guardian) {
                                startDiscovery()
                            }
                        }
                    }
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                resolveQueue.add(serviceInfo)
                resolveNext()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                // Don't immediately remove - let devices stay in list for manual connection
                // runOnUiThread {
                //     discoveredDevices.removeAll { it.serviceName == serviceInfo.serviceName }
                // }
            }
        }

        try {
            nsdManager?.discoverServices("_ward._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            Log.d(TAG, "Started discovering services")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery: ${e.message}")
        }
    }

    internal fun connectToWard(deviceName: String) {
        val serviceInfo = discoveredDevices.find { it.serviceName == deviceName }
        serviceInfo?.let {
            connectToWardByIp(it.host.hostAddress ?: "", deviceName)
        }
    }

    internal fun connectToWardByIp(ipAddress: String, wardName: String?) {
        startForegroundService("Guardian Active", "Connecting to ward...")
        stopDiscovery()

        thread {
            try {
                val socket = Socket(ipAddress, AUDIO_PORT)
                Log.d(TAG, "Connected to ward at $ipAddress:$AUDIO_PORT")

                // Send device name to ward
                val deviceName = Build.MODEL
                socket.getOutputStream().write(deviceName.toByteArray())
                socket.getOutputStream().flush()

                val displayName = wardName ?: ipAddress
                runOnUiThread {
                    connectedWardName.value = displayName
                    connectedWardIp.value = ipAddress
                    isConnectedToWard.value = true
                    currentScreen.value = Screen.GuardianConnected(displayName)
                }

                startAudioPlayback(socket.getInputStream(), socket)
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                runOnUiThread {
                    stopForegroundService()
                }
            }
        }
    }

    private fun startConnectionMonitor(socket: Socket) {
        isMonitoringConnection = true
        lastConnectionTime = System.currentTimeMillis()

        connectionMonitorThread = thread {
            var alertShown = false
            while (isMonitoringConnection && !socket.isClosed) {
                Thread.sleep(1000)
                val timeSinceLastData = System.currentTimeMillis() - lastConnectionTime

                if (timeSinceLastData > CONNECTION_TIMEOUT_MS && !alertShown) {
                    // Connection lost for more than 30 seconds
                    alertShown = true
                    showConnectionLostAlert()
                }
            }
        }
    }

    private fun stopConnectionMonitor() {
        isMonitoringConnection = false
        connectionMonitorThread?.interrupt()
        connectionMonitorThread = null
        cancelConnectionLostAlert()
    }

    private fun showConnectionLostAlert() {
        runOnUiThread {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("Connection Lost!")
                .setContentText("Ward connection has been lost for more than 30 seconds")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))

            // For API 26+, set full screen intent for call-like behavior
            if (Build.VERSION.SDK_INT >= 26) {
                builder.setFullScreenIntent(pendingIntent, true)
            }

            notificationManager.notify(ALERT_NOTIFICATION_ID, builder.build())
        }
    }

    private fun cancelConnectionLostAlert() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
    }

    private fun startAudioPlayback(inputStream: InputStream, socket: Socket) {
        guardianSocket = socket
        isPlayingAudio = true
        startConnectionMonitor(socket)

        thread {
            try {
                val bufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT
                )

                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )

                audioTrack?.setStereoVolume(streamVolume.value, streamVolume.value)
                audioTrack?.play()
                val buffer = ByteArray(bufferSize)

                while (isPlayingAudio && !socket.isClosed && socket.isConnected) {
                    try {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break
                        // Update last connection time when data is received
                        lastConnectionTime = System.currentTimeMillis()
                        audioTrack?.write(buffer, 0, bytesRead)
                    } catch (e: java.net.SocketException) {
                        // Socket closed, exit gracefully
                        Log.d(TAG, "Socket closed during playback")
                        break
                    } catch (e: java.io.IOException) {
                        // IO error, connection lost
                        Log.d(TAG, "Connection lost during playback")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback error: ${e.message}")
            } finally {
                isPlayingAudio = false
                stopConnectionMonitor()

                try {
                    audioTrack?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping audio track: ${e.message}")
                }
                try {
                    audioTrack?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing audio track: ${e.message}")
                }
                audioTrack = null

                try {
                    inputStream.close()
                } catch (e: Exception) { }
                try {
                    socket.close()
                } catch (e: Exception) { }
                guardianSocket = null

                runOnUiThread {
                    if (currentScreen.value is Screen.GuardianConnected) {
                        currentScreen.value = Screen.Guardian
                        startDiscovery()
                    }
                    stopForegroundService()
                }
            }
        }
    }

    internal fun startPushToTalk(wardIp: String) {
        if (isPttActive.value) return

        // Check permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermission()
            return
        }

        isPttActive.value = true

        thread {
            try {
                // Connect to ward's PTT port
                pttSocket = Socket(wardIp, PTT_PORT)
                Log.d(TAG, "PTT connected to ward at $wardIp:$PTT_PORT")

                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT
                )

                pttAudioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    bufferSize
                )

                pttAudioRecord?.startRecording()
                val buffer = ByteArray(bufferSize)
                val outputStream = pttSocket?.getOutputStream()

                while (isPttActive.value && pttSocket?.isConnected == true) {
                    val bytesRead = pttAudioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (bytesRead > 0) {
                        outputStream?.write(buffer, 0, bytesRead)
                        outputStream?.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PTT error: ${e.message}")
            } finally {
                stopPushToTalk()
            }
        }
    }

    internal fun stopPushToTalk() {
        isPttActive.value = false

        try {
            pttAudioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping PTT audio: ${e.message}")
        }
        try {
            pttAudioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing PTT audio: ${e.message}")
        }
        pttAudioRecord = null

        try {
            pttSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PTT socket: ${e.message}")
        }
        pttSocket = null
    }

    private fun stopAudioPlayback() {
        isPlayingAudio = false
        stopConnectionMonitor()
        stopPushToTalk()

        // Close guardian socket to interrupt any blocking reads
        try {
            guardianSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing guardian socket: ${e.message}")
        }
        guardianSocket = null

        // Give thread time to exit
        Thread.sleep(100)

        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio track: ${e.message}")
        }
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio track: ${e.message}")
        }
        audioTrack = null
        connectedWardName.value = null
        connectedWardIp.value = null
    }

    private fun stopDiscovery() {
        try {
            discoveryListener?.let {
                nsdManager?.stopServiceDiscovery(it)
                Log.d(TAG, "Stopping discovery")
            }
            discoveryListener = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery: ${e.message}")
        }
    }

    internal fun requestMicrophonePermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            100
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) { // Build.VERSION_CODES.TIRAMISU
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATION
                )
            }
        }
    }
}

sealed class Screen {
    object Main : Screen()
    object Guardian : Screen()
    object Ward : Screen()
    data class GuardianConnected(val wardName: String) : Screen()
}

data class GuardianConnection(
    val deviceName: String,
    val ipAddress: String,
    val socket: Socket
)

@Composable
fun MainScreen(
    onGuardianClick: () -> Unit,
    onWardClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A)),
        verticalArrangement = Arrangement.Center
    ) {
        // Top Button - GUARDIAN (Dark Blue)
        Button(
            onClick = onGuardianClick,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C5F8D)),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
        ) {
            Text(
                text = "GUARDIAN",
                style = TextStyle(
                    fontSize = 60.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }

        // Bottom Button - WARD (Dark Red)
        Button(
            onClick = onWardClick,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8D2C2C)),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
        ) {
            Text(
                text = "WARD",
                style = TextStyle(
                    fontSize = 60.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }
    }
}

@Composable
fun WardScreen(
    connectedGuardians: List<String>,
    localIp: String,
    onBack: () -> Unit
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
    val showDialog = remember { mutableStateOf(false) }

    BackHandler {
        showDialog.value = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
    ) {
        // Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Color(0xFF8D2C2C))
        ) {
            IconButton(
                onClick = { showDialog.value = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "WARD",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Local IP
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2C2C2C), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "Local IP Address",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Gray
                        )
                    )
                    Text(
                        text = localIp,
                        style = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }
            }

            // Connected Guardians
            Text(
                text = "Connected Guardians (${connectedGuardians.size})",
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )

            if (connectedGuardians.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2C2C2C), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Waiting for guardian connections...",
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(connectedGuardians) { guardian ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2C2C2C), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .padding(16.dp)
                        ) {
                            Text(
                                text = guardian,
                                style = TextStyle(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialog
    if (showDialog.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = {
                Text(text = "Exit Ward?")
            },
            text = {
                Text(text = "Are you sure you want to go back to the main screen?")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showDialog.value = false
                        onBack()
                    }
                ) {
                    Text("Yes")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showDialog.value = false }
                ) {
                    Text("No")
                }
            }
        )
    }

    // Request permissions and start broadcasting
    activity?.apply {
        startBroadcasting()
    }
}

@Composable
fun GuardianScreen(
    devices: List<String>,
    onBack: () -> Unit,
    onManualConnect: (String) -> Unit,
    onDeviceConnect: (String) -> Unit
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
    val showDialog = remember { mutableStateOf(false) }
    val showManualConnectDialog = remember { mutableStateOf(false) }
    val manualIpAddress = remember { mutableStateOf("") }

    BackHandler {
        showDialog.value = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
    ) {
        // Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Color(0xFF2C5F8D))
        ) {
            IconButton(
                onClick = { showDialog.value = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "GUARDIAN",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Discovered Devices",
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                ),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Devices List
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(devices) { device ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2C2C2C), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .clickable {
                                onDeviceConnect(device)
                            }
                            .padding(16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = device,
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        )
                    }
                }
            }

            // Manual connect button at bottom
            Button(
                onClick = { showManualConnectDialog.value = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C5F8D))
            ) {
                Text(
                    text = "Connect Manually",
                    color = Color.White
                )
            }
        }
    }

    // Manual Connect Dialog
    if (showManualConnectDialog.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showManualConnectDialog.value = false },
            title = {
                Text(text = "Manual Connection")
            },
            text = {
                Column {
                    Text(text = "Enter the ward's IP address:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualIpAddress.value,
                        onValueChange = { manualIpAddress.value = it },
                        label = { Text("IP Address") },
                        placeholder = { Text("192.168.1.100") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (manualIpAddress.value.isNotEmpty()) {
                            onManualConnect(manualIpAddress.value)
                            showManualConnectDialog.value = false
                            manualIpAddress.value = ""
                        }
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showManualConnectDialog.value = false
                        manualIpAddress.value = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Confirmation Dialog
    if (showDialog.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = {
                Text(text = "Exit Guardian?")
            },
            text = {
                Text(text = "Are you sure you want to go back to the main screen?")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showDialog.value = false
                        onBack()
                    }
                ) {
                    Text("Yes")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showDialog.value = false }
                ) {
                    Text("No")
                }
            }
        )
    }

    // Start discovery once when screen is opened
    androidx.compose.runtime.LaunchedEffect(Unit) {
        activity?.startDiscovery()
    }
}

@Composable
fun GuardianConnectedScreen(
    wardName: String,
    volume: Float,
    isPttActive: Boolean,
    isConnected: Boolean,
    onVolumeChange: (Float) -> Unit,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    onBack: () -> Unit
) {
    val showDialog = remember { mutableStateOf(false) }

    BackHandler {
        showDialog.value = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
    ) {
        // Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Color(0xFF2C5F8D))
        ) {
            IconButton(
                onClick = { showDialog.value = true },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "GUARDIAN",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }

        // Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Connected to ward
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2C5F8D), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Connected to",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    )
                    Text(
                        text = wardName,
                        style = TextStyle(
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                }
            }

            // Volume control
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2C2C2C), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Audio Volume",
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "\uD83D\uDD08",
                        style = TextStyle(fontSize = 24.sp)
                    )
                    androidx.compose.material3.Slider(
                        value = volume,
                        onValueChange = onVolumeChange,
                        modifier = Modifier.weight(1f),
                        valueRange = 0f..1f
                    )
                    Text(
                        text = "\uD83D\uDD0A",
                        style = TextStyle(fontSize = 24.sp)
                    )
                }
                Text(
                    text = "${(volume * 100).toInt()}%",
                    style = TextStyle(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            // Push-to-talk button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        if (isPttActive) Color(0xFF4CAF50) else Color(0xFF8D2C2C),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onPttPress()
                                tryAwaitRelease()
                                onPttRelease()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isPttActive) "🎤 TRANSMITTING" else "🎤 PUSH TO TALK",
                        style = TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Text(
                        text = if (isPttActive) "Release to stop" else "Hold to speak to ward",
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    )
                }
            }

            // Status indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2C2C2C), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val statusColor = if (isConnected) Color(0xFF00FF00) else Color(0xFFFF5555)
                    val statusText = if (isConnected) "Audio streaming active" else "Connection DISCONNECTED"
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(12.dp)
                            .background(statusColor, shape = androidx.compose.foundation.shape.CircleShape)
                    )
                    Text(
                        text = statusText,
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = Color.White
                        )
                    )
                }
            }
        }
    }

    // Confirmation Dialog
    if (showDialog.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = {
                Text(text = "Disconnect?")
            },
            text = {
                Text(text = "Are you sure you want to disconnect from the ward?")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showDialog.value = false
                        onBack()
                    }
                ) {
                    Text("Yes")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showDialog.value = false }
                ) {
                    Text("No")
                }
            }
        )
    }
}

// Foreground Service to keep the app running
class ForegroundService : Service() {
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ForegroundService = this@ForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: "Guardian Angel"
        val text = intent?.getStringExtra("text") ?: "Service running"

        val notification = NotificationCompat.Builder(this, MainActivity.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) { // Build.VERSION_CODES.Q
            try {
                // Use reflection for FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                val method = Service::class.java.getMethod("startForeground", Int::class.javaPrimitiveType,
                    android.app.Notification::class.java, Int::class.javaPrimitiveType)
                method.invoke(this, MainActivity.NOTIFICATION_ID, notification, 2) // FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK = 2
            } catch (e: Exception) {
                startForeground(MainActivity.NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(MainActivity.NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= 24) { // Build.VERSION_CODES.N
            try {
                // Use reflection for STOP_FOREGROUND_REMOVE
                val method = Service::class.java.getMethod("stopForeground", Int::class.javaPrimitiveType)
                method.invoke(this, 1) // STOP_FOREGROUND_REMOVE = 1
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    GuardianAngelTheme {
        MainScreen(
            onGuardianClick = {},
            onWardClick = {}
        )
    };
}
