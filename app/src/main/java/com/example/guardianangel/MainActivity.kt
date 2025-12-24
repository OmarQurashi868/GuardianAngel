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
import com.example.guardianangel.model.Screen
import com.example.guardianangel.model.GuardianConnection
import com.example.guardianangel.ui.screens.MainScreen
import com.example.guardianangel.ui.screens.WardScreen
import com.example.guardianangel.ui.screens.GuardianScreen
import com.example.guardianangel.ui.screens.GuardianConnectedScreen
import com.example.guardianangel.service.ForegroundService
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

    // Centralized constants from Constants object
    private val TAG = com.example.guardianangel.core.Constants.TAG
    private val AUDIO_PORT = com.example.guardianangel.core.Constants.AUDIO_PORT
    private val PTT_PORT = com.example.guardianangel.core.Constants.PTT_PORT
    private val SAMPLE_RATE = com.example.guardianangel.core.Constants.SAMPLE_RATE
    private val CHANNEL_CONFIG_IN = com.example.guardianangel.core.Constants.CHANNEL_CONFIG_IN
    private val CHANNEL_CONFIG_OUT = com.example.guardianangel.core.Constants.CHANNEL_CONFIG_OUT
    private val AUDIO_FORMAT = com.example.guardianangel.core.Constants.AUDIO_FORMAT
    private val NOTIFICATION_CHANNEL_ID = com.example.guardianangel.core.Constants.NOTIFICATION_CHANNEL_ID
    private val ALERT_CHANNEL_ID = com.example.guardianangel.core.Constants.ALERT_CHANNEL_ID
    private val NOTIFICATION_ID = com.example.guardianangel.core.Constants.NOTIFICATION_ID
    private val ALERT_NOTIFICATION_ID = com.example.guardianangel.core.Constants.ALERT_NOTIFICATION_ID
    private val REQUEST_CODE_NOTIFICATION = com.example.guardianangel.core.Constants.REQUEST_CODE_NOTIFICATION
    private val CONNECTION_TIMEOUT_MS = com.example.guardianangel.core.Constants.CONNECTION_TIMEOUT_MS

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
                            stopDiscovery()
                            stopAudioPlayback()
                            stopForegroundService()
                            currentScreen.value = Screen.Main
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
                            audioTrack?.let { track ->
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        track.setVolume(newVolume)
                                    } else {
                                        track.setStereoVolume(newVolume, newVolume)
                                    }
                                } catch (_: Exception) {
                                    Log.w(TAG, "Failed to set volume")
                                }
                            }
                        },
                        onPttPress = {
                            connectedWardIp.value?.let { startPushToTalk(it) }
                        },
                        onPttRelease = {
                            stopPushToTalk()
                        },
                        onBack = {
                            stopAudioPlayback()
                            stopForegroundService()
                            currentScreen.value = Screen.Main
                        }
                    )

                    Screen.Ward -> WardScreen(
                        connectedGuardians = connectedGuardians.map { it.deviceName },
                        localIp = localIpAddress.value,
                        onBack = {
                            stopAudioBroadcast()
                            stopForegroundService()
                            currentScreen.value = Screen.Main
                        }
                    )
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) { // Build.VERSION_CODES.O
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                val channelClass = Class.forName("android.app.NotificationChannel")

                // Try to obtain the constructor safely
                val constructor = try {
                    channelClass.getConstructor(
                        String::class.java,
                        CharSequence::class.java,
                        Int::class.javaPrimitiveType
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "NotificationChannel constructor not found: ${e.message}")
                    null
                }

                // Service notification channel
                val serviceChannel = try {
                    constructor?.newInstance(NOTIFICATION_CHANNEL_ID, "Guardian Angel Service", 2) // IMPORTANCE_LOW = 2
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to instantiate service channel: ${e.message}")
                    null
                }

                if (serviceChannel != null) {
                    // Description (optional)
                    try {
                        val setDescription = channelClass.getMethod("setDescription", String::class.java)
                        setDescription.invoke(serviceChannel, "Keeps the audio streaming service running")
                    } catch (e: Exception) {
                        Log.w(TAG, "setDescription not supported for service channel: ${e.message}")
                    }

                    // Create channel
                    try {
                        val createMethod =
                            NotificationManager::class.java.getMethod("createNotificationChannel", channelClass)
                        createMethod.invoke(notificationManager, serviceChannel)
                    } catch (e: Exception) {
                        Log.w(TAG, "createNotificationChannel failed for service channel: ${e.message}")
                    }
                }

                // Alert notification channel
                val alertChannel = try {
                    constructor?.newInstance(ALERT_CHANNEL_ID, "Connection Alerts", 5) // IMPORTANCE_HIGH = 5
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to instantiate alert channel: ${e.message}")
                    null
                }

                if (alertChannel != null) {
                    // Description (optional)
                    try {
                        val setDescription = channelClass.getMethod("setDescription", String::class.java)
                        setDescription.invoke(alertChannel, "Alerts when connection is lost")
                    } catch (e: Exception) {
                        Log.w(TAG, "setDescription not supported for alert channel: ${e.message}")
                    }

                    // Sound (optional)
                    try {
                        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        val audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        val setSoundMethod =
                            channelClass.getMethod("setSound", Uri::class.java, AudioAttributes::class.java)
                        setSoundMethod.invoke(alertChannel, defaultSoundUri, audioAttributes)
                    } catch (e: Exception) {
                        Log.w(TAG, "setSound not supported for alert channel: ${e.message}")
                    }

                    // Bypass DND (optional)
                    try {
                        val setBypassDndMethod =
                            channelClass.getMethod("setBypassDnd", Boolean::class.javaPrimitiveType)
                        setBypassDndMethod.invoke(alertChannel, true)
                    } catch (e: Exception) {
                        Log.w(TAG, "setBypassDnd not supported for alert channel: ${e.message}")
                    }

                    // Create alert channel
                    try {
                        val createMethod =
                            NotificationManager::class.java.getMethod("createNotificationChannel", channelClass)
                        createMethod.invoke(notificationManager, alertChannel)
                    } catch (e: Exception) {
                        Log.w(TAG, "createNotificationChannel failed for alert channel: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error creating notification channels: ${e.message}")
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
        try {
            if (serviceBound) {
                unbindService(serviceConnection)
            }
        } catch (_: Exception) {
        }
        try {
            stopAudioPlayback()
        } catch (_: Exception) {
        }
        try {
            stopAudioBroadcast()
        } catch (_: Exception) {
        }
        try {
            stopDiscovery()
        } catch (_: Exception) {
        }
        try {
            stopForegroundService()
        } catch (_: Exception) {
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
                                try {
                                    socket.close()
                                } catch (e: Exception) {
                                }
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
            != PackageManager.PERMISSION_GRANTED
        ) {
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
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Record audio permission not granted")
            return
        }

        isStreaming = true
        thread {
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT
                )
                if (minBuffer <= 0) {
                    Log.e(TAG, "Invalid AudioRecord min buffer size: $minBuffer")
                    isStreaming = false
                    return@thread
                }
                val bufferSize = minBuffer * 2 // Larger buffer for better quality
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,  // Changed from VOICE_COMMUNICATION -> MIC for better compatibility
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize (state=${audioRecord?.state})")
                    try {
                        audioRecord?.release()
                    } catch (e: Exception) {
                    }
                    audioRecord = null
                    isStreaming = false
                    return@thread
                }

                try {
                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord?.startRecording()
                    } else {
                        Log.e(TAG, "AudioRecord not initialized at startRecording (state=${audioRecord?.state})")
                        isStreaming = false
                        return@thread
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "startRecording failed: ${e.message}")
                    isStreaming = false
                    return@thread
                }
                val buffer = ByteArray(bufferSize)

                while (isStreaming) {
                    val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (bytesRead > 0) {
                        // Skip broadcasting if PTT is active (to prevent feedback)
                        if (!isPttReceiving) {
                            // Amplify audio before sending
                            amplifyAudio(buffer, bytesRead, 3.5f)

                            // Broadcast to all connected guardians
                            val toRemove = mutableListOf<Socket>()
                            // Take a snapshot to avoid ConcurrentModification and to skip sockets already closed
                            val snapshot: List<Socket> = synchronized(clientSockets) { clientSockets.toList() }
                            snapshot.forEach { s ->
                                try {
                                    if (s.isClosed || !s.isConnected) {
                                        toRemove.add(s)
                                    } else {
                                        val out = s.getOutputStream()
                                        out.write(buffer, 0, bytesRead)
                                        out.flush()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to write to guardian: ${e.message}")
                                    toRemove.add(s)
                                }
                            }
                            if (toRemove.isNotEmpty()) {
                                synchronized(clientSockets) {
                                    toRemove.forEach { sock ->
                                        clientSockets.remove(sock)
                                        try {
                                            sock.close()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                                runOnUiThread {
                                    toRemove.forEach { sock ->
                                        connectedGuardians.removeAll { it.socket == sock }
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
                socket.soTimeout = 5000 // 5 second timeout

                // Continuously monitor the socket
                while (!socket.isClosed && socket.isConnected && !Thread.currentThread().isInterrupted) {
                    // Try to read 1 byte with timeout to detect disconnection
                    try {
                        /* timeout set once above */
                        val read = inputStream.read(buffer, 0, 1)
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
                try {
                    socket.close()
                } catch (e: Exception) {
                }
                Log.d(TAG, "Guardian $guardianName disconnected")
            }
        }
    }

    private fun stopAudioBroadcast() {
        // Idempotent guard to avoid repeated work
        if (!isStreaming && serverSocket == null && pttServerSocket == null) {
            Log.d(TAG, "stopAudioBroadcast: already stopped")
            return
        }
        isStreaming = false

        // Close all client sockets first (shutdown IO defensively)
        synchronized(clientSockets) {
            clientSockets.forEach { s ->
                try {
                    if (!s.isClosed) {
                        try {
                            s.shutdownInput()
                        } catch (_: Exception) {
                        }
                        try {
                            s.shutdownOutput()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                }
            }
            clientSockets.clear()
        }

        // Give audio thread time to finish
        // removed blocking sleep

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
            val ss = serverSocket
            if (ss != null && !ss.isClosed) {
                ss.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket: ${e.message}")
        } finally {
            serverSocket = null
        }

        try {
            val ps = pttServerSocket
            if (ps != null && !ps.isClosed) {
                ps.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PTT server socket: ${e.message}")
        } finally {
            pttServerSocket = null
        }

        // Clear UI state
        runOnUiThread {
            connectedGuardians.clear()
        }

        // Unregister NSD service (only if previously registered)
        val listener = registrationListener
        if (listener != null) {
            try {
                nsdManager?.unregisterService(listener)
                Log.d(TAG, "Unregistered NSD service")
            } catch (e: IllegalArgumentException) {
                // Already unregistered or not registered
                Log.w(TAG, "NSD service not registered: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering service: ${e.message}")
            } finally {
                registrationListener = null
            }
        } else {
            Log.d(TAG, "No NSD registration to unregister")
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
                        Log.d(
                            TAG,
                            "Service resolved: ${resolvedInfo.serviceName} at ${resolvedInfo.host}:${resolvedInfo.port}"
                        )
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

            val builder = NotificationCompat.Builder(this, this@MainActivity.ALERT_CHANNEL_ID)
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

            notificationManager.notify(this@MainActivity.ALERT_NOTIFICATION_ID, builder.build())
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

                audioTrack?.let { track ->
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            track.setVolume(streamVolume.value)
                        } else {
                            track.setStereoVolume(streamVolume.value, streamVolume.value)
                        }
                    } catch (_: Exception) {
                        Log.w(TAG, "Failed to set initial audio volume")
                    }
                }
                audioTrack?.play()
                val buffer = ByteArray(bufferSize)

                while (
                    isPlayingAudio &&
                    !socket.isClosed &&
                    socket.isConnected &&
                    !Thread.currentThread().isInterrupted
                ) {
                    try {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead <= 0) continue
                        // Update last connection time when data is received
                        lastConnectionTime = System.currentTimeMillis()
                        val track = audioTrack
                        if (track == null) {
                            Log.d(TAG, "AudioTrack is null, stopping playback loop")
                            break
                        }
                        try {
                            track.write(buffer, 0, bytesRead)
                        } catch (e: IllegalStateException) {
                            Log.e(TAG, "AudioTrack write failed: ${e.message}")
                            break
                        }
                    } catch (e: java.net.SocketException) {
                        // Socket closed, exit gracefully
                        Log.d(TAG, "Socket closed during playback")
                        break
                    } catch (e: java.io.IOException) {
                        // IO error, connection lost
                        Log.d(TAG, "Connection lost during playback")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected error during playback: ${e.message}")
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
                } catch (e: Exception) {
                }
                try {
                    socket.close()
                } catch (e: Exception) {
                }
                guardianSocket = null

                runOnUiThread {
                    if (currentScreen.value is Screen.GuardianConnected) {
                        currentScreen.value = Screen.Guardian
                        startDiscovery()
                    }
                    isConnectedToWard.value = false
                    isPttActive.value = false
                    stopForegroundService()
                }
            }
        }
    }

    internal fun startPushToTalk(wardIp: String) {
        if (isPttActive.value) return

        // Check permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicrophonePermission()
            return
        }

        isPttActive.value = true

        thread {
            try {
                // Connect to ward's PTT port
                pttSocket = Socket(wardIp, PTT_PORT)
                Log.d(TAG, "PTT connected to ward at $wardIp:$PTT_PORT")

                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT
                )
                if (minBuffer <= 0) {
                    throw IllegalStateException("Invalid PTT min buffer size: $minBuffer")
                }
                val bufferSize = minBuffer * 2

                pttAudioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    bufferSize
                )

                val recorder = pttAudioRecord
                if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("PTT AudioRecord not initialized (state=${pttAudioRecord?.state})")
                }
                try {
                    recorder.startRecording()
                } catch (e: IllegalStateException) {
                    throw IllegalStateException("PTT startRecording failed: ${e.message}")
                }

                val buffer = ByteArray(bufferSize)
                val outputStream = pttSocket?.getOutputStream()
                    ?: throw IllegalStateException("PTT output stream is null")

                while (isPttActive.value && pttSocket?.isConnected == true) {
                    val bytesRead = pttAudioRecord?.read(buffer, 0, bufferSize) ?: -1
                    if (bytesRead <= 0) continue
                    try {
                        outputStream.write(buffer, 0, bytesRead)
                        outputStream.flush()
                    } catch (e: Exception) {
                        Log.e(TAG, "PTT write failed: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PTT error: ${e.message}")
                isPttActive.value = false
            } finally {
                stopPushToTalk()
            }
        }
    }

    internal fun stopPushToTalk() {
        isPttActive.value = false

        try {
            val rec = pttAudioRecord
            if (rec != null && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                rec.stop()
            }
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
            pttSocket?.shutdownOutput()
        } catch (_: Exception) {
        }
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
        // removed blocking sleep

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
        isConnectedToWard.value = false
    }

    private fun stopDiscovery() {
        val listener = discoveryListener
        if (listener == null) {
            Log.d(TAG, "Stopping discovery: no active listener")
            return
        }
        try {
            nsdManager?.stopServiceDiscovery(listener)
            Log.d(TAG, "Stopping discovery")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery: ${e.message}")
        } finally {
            discoveryListener = null
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
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_CODE_NOTIFICATION
                )
            }
        }
    }
}
