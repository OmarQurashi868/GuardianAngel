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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    private val pttActiveSockets = mutableSetOf<Socket>() // Track which guardian sockets are sending PTT
    private val localIpAddress = mutableStateOf("")
    private var foregroundService: ForegroundService? = null
    private var serviceBound = false
    private var clientSockets = mutableListOf<Socket>()
    val streamVolume = mutableStateOf(1.0f)
    private var lastConnectionTime = 0L
    private var connectionMonitorThread: Thread? = null
    private var isMonitoringConnection = false
    private var pttSocket: Socket? = null
    private var pttAudioRecord: AudioRecord? = null
    private var isPttActive = mutableStateOf(false)
    private var connectedWardIp = mutableStateOf<String?>(null)
    private var connectedWardNameState = mutableStateOf<String?>(null)
    private var isConnectedToWard = mutableStateOf(false)
    private var shouldAutoReconnect = false
    private var reconnectThread: Thread? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isNetworkAvailable = mutableStateOf(true)
    private var serverAcceptThread: Thread? = null
    private var monitorConnectionThreads = mutableSetOf<Thread>()

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
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
        requestNotificationPermission()
        localIpAddress.value = getLocalIpAddress()
        setupNetworkMonitoring()

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
                            shouldAutoReconnect = false
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
            stopNetworkMonitoring()
        } catch (_: Exception) {
        }
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
    
    private fun setupNetworkMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager?.let { cm ->
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.d(TAG, "Network available")
                        runOnUiThread {
                            isNetworkAvailable.value = true
                            updateWardNotification()
                            updateGuardianNotification()
                            
                            // If we're in ward mode and server socket is closed, restart it
                            if (isStreaming && (serverSocket == null || serverSocket!!.isClosed)) {
                                Log.d(TAG, "Network restored, restarting ward server socket")
                                // Restart the server accept loop
                                restartWardServerSocket()
                            }
                        }
                    }

                    override fun onLost(network: Network) {
                        Log.d(TAG, "Network lost")
                        runOnUiThread {
                            isNetworkAvailable.value = false
                            updateWardNotification()
                            updateGuardianNotification()
                        }
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        val hasInternet = networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET
                        ) && networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED
                        )
                        runOnUiThread {
                            isNetworkAvailable.value = hasInternet
                            updateWardNotification()
                            updateGuardianNotification()
                        }
                    }
                }

                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, networkCallback!!)
                
                // Check initial network state
                val activeNetwork = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(activeNetwork)
                isNetworkAvailable.value = capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                ) == true && capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
            }
        } else {
            // Fallback for older Android versions
            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager?.activeNetworkInfo
            isNetworkAvailable.value = activeNetworkInfo?.isConnected == true
        }
    }
    
    private fun stopNetworkMonitoring() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback: ${e.message}")
            }
            networkCallback = null
        }
    }
    
    private fun updateWardNotification() {
        if (currentScreen.value != Screen.Ward || !isStreaming) return
        
        synchronized(clientSockets) {
            val guardianCount = clientSockets.size
            val notificationText = when {
                !isNetworkAvailable.value -> "Attempting to reconnect..."
                guardianCount == 0 -> "Waiting for guardian..."
                else -> "Broadcasting..."
            }
            startForegroundService("Ward Active", notificationText)
        }
    }
    
    private fun updateGuardianNotification() {
        if (currentScreen.value !is Screen.GuardianConnected) return
        
        val notificationText = when {
            !isNetworkAvailable.value -> "Attempting to reconnect..."
            isConnectedToWard.value && isPlayingAudio -> "Monitoring ward..."
            shouldAutoReconnect -> "Attempting to reconnect..."
            else -> "Connecting to ward..."
        }
        startForegroundService("Guardian Active", notificationText)
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
                                // Track this socket as sending PTT
                                synchronized(pttActiveSockets) {
                                    pttActiveSockets.add(socket)
                                }
                                Log.d(TAG, "PTT client connected, muting ward mic for this guardian only")

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
                                
                                // Create a separate buffer for amplified audio
                                val amplifiedBuffer = ByteArray(bufferSize)

                                while (!socket.isClosed && socket.isConnected) {
                                    val bytesRead = inputStream.read(buffer)
                                    if (bytesRead == -1) break
                                    
                                    // Amplify PTT audio before playback
                                    System.arraycopy(buffer, 0, amplifiedBuffer, 0, bytesRead)
                                    amplifyAudio(amplifiedBuffer, bytesRead, 4.0f)
                                    
                                    // Write amplified audio to ward's speaker
                                    pttAudioTrack?.write(amplifiedBuffer, 0, bytesRead)
                                    
                                    // Broadcast PTT audio to all OTHER guardians (feedback) in a separate thread to prevent blocking
                                    thread {
                                        val feedbackBuffer = ByteArray(bytesRead)
                                        System.arraycopy(buffer, 0, feedbackBuffer, 0, bytesRead)
                                        
                                        synchronized(clientSockets) {
                                            val snapshot = clientSockets.toList()
                                            snapshot.forEach { guardianSocket ->
                                                if (guardianSocket != socket && !guardianSocket.isClosed && guardianSocket.isConnected) {
                                                    try {
                                                        guardianSocket.getOutputStream().write(feedbackBuffer, 0, bytesRead)
                                                        guardianSocket.getOutputStream().flush()
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Failed to send PTT feedback to guardian: ${e.message}")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "PTT playback error: ${e.message}")
                            } finally {
                                // Remove this socket from PTT active set
                                synchronized(pttActiveSockets) {
                                    pttActiveSockets.remove(socket)
                                }
                                Log.d(TAG, "PTT client disconnected, unmuting ward mic for this guardian")

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
        startForegroundService("Ward Active", "Waiting for guardian...")

        // Start PTT server
        startPttServer()

        // Start server socket for audio streaming
        serverAcceptThread = thread {
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
                while (serverSocket != null && !serverSocket!!.isClosed && isStreaming) {
                    // Update notification based on guardian count and network state
                    runOnUiThread {
                        updateWardNotification()
                    }
                    
                    val client = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        // Network error - socket might be closed or network disconnected
                        if (isStreaming && !serverSocket!!.isClosed) {
                            Log.w(TAG, "Server accept error (network issue?): ${e.message}")
                            // Wait a bit and continue if still streaming
                            Thread.sleep(1000)
                        }
                        null
                    }
                    
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

                                // First, check for and remove any stale guardians with the same IP
                                val staleSockets = mutableListOf<Socket>()
                                synchronized(clientSockets) {
                                    // Find all sockets with same IP
                                    clientSockets.filter { s ->
                                        try {
                                            s.inetAddress?.hostAddress == guardianIp && s != socket
                                        } catch (_: Exception) { false }
                                    }.forEach { staleSockets.add(it) }
                                    
                                    // Remove stale sockets from client list
                                    staleSockets.forEach { staleSocket ->
                                        clientSockets.remove(staleSocket)
                                        try {
                                            if (!staleSocket.isClosed) {
                                                staleSocket.shutdownInput()
                                                staleSocket.shutdownOutput()
                                                staleSocket.close()
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                                
                                // Remove stale guardian entries from UI
                                runOnUiThread {
                                    connectedGuardians.removeAll { it.ipAddress == guardianIp }
                                }
                                
                                // Also remove from PTT active set
                                synchronized(pttActiveSockets) {
                                    staleSockets.forEach { pttActiveSockets.remove(it) }
                                }
                                
                                // Now add the new connection
                                val connection = GuardianConnection(guardianName, guardianIp, socket)
                                runOnUiThread {
                                    connectedGuardians.add(connection)
                                    // Update notification when guardian connects
                                    updateWardNotification()
                                }
                                
                                synchronized(clientSockets) {
                                    clientSockets.add(socket)
                                }

                                // Start audio capture only if not already streaming
                                if (!isStreaming) {
                                    startAudioCapture()
                                }

                                // Monitor this socket for disconnection
                                val monitorThread = thread {
                                    monitorConnection(socket, guardianName)
                                }
                                synchronized(monitorConnectionThreads) {
                                    monitorConnectionThreads.add(monitorThread)
                                }
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
                // If we're still supposed to be streaming and network is available, try to restart
                if (isStreaming && isNetworkAvailable.value) {
                    Log.d(TAG, "Server socket error but still streaming, will restart on network callback")
                }
            } finally {
                serverAcceptThread = null
            }
        }
    }
    
    private fun restartWardServerSocket() {
        // Only restart if we're still in ward mode
        if (!isStreaming) {
            Log.d(TAG, "Not restarting server socket - not in ward mode")
            return
        }
        
        // Close old socket if it exists
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing old server socket: ${e.message}")
        }
        serverSocket = null
        
        // Wait for old thread to finish
        serverAcceptThread?.join(1000)
        serverAcceptThread = null
        
        // Restart the accept loop
        serverAcceptThread = thread {
            try {
                serverSocket = ServerSocket(AUDIO_PORT)
                Log.d(TAG, "Server socket restarted on port $AUDIO_PORT after network reconnect")
                
                // Wait for Guardian to connect
                while (serverSocket != null && !serverSocket!!.isClosed && isStreaming) {
                    // Update notification based on guardian count and network state
                    runOnUiThread {
                        updateWardNotification()
                    }
                    
                    val client = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        // Network error - socket might be closed or network disconnected
                        if (isStreaming && serverSocket != null && !serverSocket!!.isClosed) {
                            Log.w(TAG, "Server accept error (network issue?): ${e.message}")
                            // Wait a bit and continue if still streaming
                            Thread.sleep(1000)
                        }
                        null
                    }
                    
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

                                // First, check for and remove any stale guardians with the same IP
                                val staleSockets = mutableListOf<Socket>()
                                synchronized(clientSockets) {
                                    // Find all sockets with same IP
                                    clientSockets.filter { s ->
                                        try {
                                            s.inetAddress?.hostAddress == guardianIp && s != socket
                                        } catch (_: Exception) { false }
                                    }.forEach { staleSockets.add(it) }
                                    
                                    // Remove stale sockets from client list
                                    staleSockets.forEach { staleSocket ->
                                        clientSockets.remove(staleSocket)
                                        try {
                                            if (!staleSocket.isClosed) {
                                                staleSocket.shutdownInput()
                                                staleSocket.shutdownOutput()
                                                staleSocket.close()
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                                
                                // Remove stale guardian entries from UI
                                runOnUiThread {
                                    connectedGuardians.removeAll { it.ipAddress == guardianIp }
                                }
                                
                                // Also remove from PTT active set
                                synchronized(pttActiveSockets) {
                                    staleSockets.forEach { staleSocket ->
                                        pttActiveSockets.remove(staleSocket)
                                    }
                                }

                                // Add new guardian connection
                                runOnUiThread {
                                    connectedGuardians.add(
                                        GuardianConnection(
                                            deviceName = guardianName,
                                            ipAddress = guardianIp,
                                            socket = socket
                                        )
                                    )
                                }
                                
                                synchronized(clientSockets) {
                                    clientSockets.add(socket)
                                }

                                // Start audio capture only if not already streaming
                                if (!isStreaming) {
                                    startAudioCapture()
                                }

                                // Monitor this socket for disconnection
                                val monitorThread = thread {
                                    monitorConnection(socket, guardianName)
                                }
                                synchronized(monitorConnectionThreads) {
                                    monitorConnectionThreads.add(monitorThread)
                                }
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
                Log.e(TAG, "Server restart error: ${e.message}")
            } finally {
                serverAcceptThread = null
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
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // Optimized for phone call quality with noise suppression and echo cancellation
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
                        // Amplify audio before sending (increased gain for better pickup)
                        amplifyAudio(buffer, bytesRead, 5.0f)

                        // Broadcast to all connected guardians, but skip those sending PTT
                        val toRemove = mutableListOf<Socket>()
                        val pttActive: Set<Socket>
                        synchronized(pttActiveSockets) {
                            pttActive = pttActiveSockets.toSet()
                        }
                        
                        // Take a snapshot to avoid ConcurrentModification and to skip sockets already closed
                        val snapshot: List<Socket> = synchronized(clientSockets) { clientSockets.toList() }
                        snapshot.forEach { s ->
                            try {
                                if (s.isClosed || !s.isConnected) {
                                    toRemove.add(s)
                                } else if (!pttActive.contains(s)) {
                                    // Only send to guardians NOT currently sending PTT
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
                            synchronized(pttActiveSockets) {
                                pttActiveSockets.removeAll(toRemove)
                            }
                            runOnUiThread {
                                toRemove.forEach { sock ->
                                    connectedGuardians.removeAll { it.socket == sock }
                                }
                                    connectedGuardians.removeAll { it.socket.isClosed || !it.socket.isConnected }
                            }
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
        val guardianIp = try { socket.inetAddress?.hostAddress } catch (_: Exception) { null }
        
        var lastSuccessfulRead = System.currentTimeMillis()
        
        try {
            val inputStream = socket.getInputStream()
            val buffer = ByteArray(1)
            socket.soTimeout = 5000 // 5 second timeout

            // Continuously monitor the socket
            while (!socket.isClosed && socket.isConnected && !Thread.currentThread().isInterrupted && isStreaming) {
                    // Try to read 1 byte with timeout to detect disconnection
                    try {
                        val read = inputStream.read(buffer, 0, 1)
                        if (read == -1) {
                            // Connection closed by remote
                            Log.d(TAG, "Guardian $guardianName connection closed (EOF)")
                            break
                        }
                        // Successful read - update timestamp
                        lastSuccessfulRead = System.currentTimeMillis()
                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout is expected - check if we've had no successful reads for 5 seconds
                        val timeSinceLastRead = System.currentTimeMillis() - lastSuccessfulRead
                        if (timeSinceLastRead >= 5000) {
                            Log.d(TAG, "Guardian $guardianName no data for 5 seconds, removing")
                            break
                        }
                        continue
                    } catch (e: java.net.SocketException) {
                        // Socket exception means connection is broken
                        Log.d(TAG, "Guardian $guardianName socket error: ${e.message}")
                        break
                    } catch (e: Exception) {
                        // Any other exception means connection is broken
                        Log.d(TAG, "Guardian $guardianName connection lost: ${e.message}")
                        break
                    }
                }
        } catch (e: Exception) {
            Log.d(TAG, "Connection monitoring ended for $guardianName: ${e.message}")
        } finally {
            // Remove this thread from tracking
            synchronized(monitorConnectionThreads) {
                monitorConnectionThreads.remove(Thread.currentThread())
            }
            
            // Clean up this connection - use IP address for more reliable matching
            Log.d(TAG, "Cleaning up guardian $guardianName (IP: $guardianIp)")
            
            synchronized(clientSockets) {
                clientSockets.remove(socket)
            }
            synchronized(pttActiveSockets) {
                pttActiveSockets.remove(socket)
            }
            
            runOnUiThread {
                // Remove by both socket reference AND IP address to be thorough
                connectedGuardians.removeAll { it.socket == socket || it.ipAddress == guardianIp }
                // Update notification when guardian disconnects
                updateWardNotification()
            }
            
            try {
                if (!socket.isClosed) socket.close()
            } catch (e: Exception) {
                Log.d(TAG, "Error closing socket: ${e.message}")
            }
            Log.d(TAG, "Guardian $guardianName disconnected and cleaned up")
        }
    }

    private fun stopAudioBroadcast() {
        // Idempotent guard to avoid repeated work
        if (!isStreaming && serverSocket == null && pttServerSocket == null) {
            Log.d(TAG, "stopAudioBroadcast: already stopped")
            return
        }
        
        Log.d(TAG, "Stopping audio broadcast...")
        isStreaming = false
        
        // Wait for all monitor threads to finish (with timeout)
        synchronized(monitorConnectionThreads) {
            monitorConnectionThreads.forEach { thread ->
                try {
                    thread.join(500) // Wait up to 500ms per thread
                } catch (e: Exception) {
                    Log.w(TAG, "Error waiting for monitor thread: ${e.message}")
                }
            }
            monitorConnectionThreads.clear()
        }
        
        // Wait for server accept thread to finish
        serverAcceptThread?.let { thread ->
            try {
                thread.join(1000) // Wait up to 1 second
            } catch (e: Exception) {
                Log.w(TAG, "Error waiting for server accept thread: ${e.message}")
            }
        }
        serverAcceptThread = null

        // Close server sockets first to stop accepting new connections
        try {
            val ss = serverSocket
            if (ss != null && !ss.isClosed) {
                try {
                    ss.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing server socket: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing server socket: ${e.message}")
        } finally {
            serverSocket = null
        }

        try {
            val ps = pttServerSocket
            if (ps != null && !ps.isClosed) {
                try {
                    ps.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing PTT server socket: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing PTT server socket: ${e.message}")
        } finally {
            pttServerSocket = null
        }

        // Close all client sockets first (shutdown IO defensively)
        val socketsToClose: List<Socket>
        synchronized(clientSockets) {
            socketsToClose = clientSockets.toList()
            clientSockets.clear()
        }
        synchronized(pttActiveSockets) {
            pttActiveSockets.clear()
        }
        
        socketsToClose.forEach { s ->
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
        
        // Ensure all guardian entries are removed from UI
        runOnUiThread {
            connectedGuardians.clear()
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
        isPttReceiving = false

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
        serviceInfo?.let { info ->
            val ipAddress = info.host?.hostAddress ?: ""
            connectToWardByIp(ipAddress, deviceName)
        }
    }

    internal fun connectToWardByIp(ipAddress: String, wardName: String?) {
        // Don't attempt connection if already connected and playing
        if (isConnectedToWard.value && isPlayingAudio) {
            Log.d(TAG, "Already connected and playing, skipping connection attempt")
            return
        }
        
        // Clean up any existing connection before attempting new one
        if (isPlayingAudio || guardianSocket != null) {
            Log.d(TAG, "Cleaning up existing connection before reconnect")
            isPlayingAudio = false
            try { guardianSocket?.close() } catch (_: Exception) {}
            guardianSocket = null
            try { audioTrack?.stop() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null
        }
        
        updateGuardianNotification()
        stopDiscovery()

        thread {
            try {
                Log.d(TAG, "Attempting to connect to ward at $ipAddress:$AUDIO_PORT")
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(ipAddress, AUDIO_PORT), 10000) // 10 second timeout
                Log.d(TAG, "Connected to ward at $ipAddress:$AUDIO_PORT")

                // Send device name to ward
                val deviceName = Build.MODEL
                socket.getOutputStream().write(deviceName.toByteArray())
                socket.getOutputStream().flush()

                val displayName = wardName ?: ipAddress
                runOnUiThread {
                    connectedWardName.value = displayName
                    connectedWardNameState.value = displayName
                    connectedWardIp.value = ipAddress
                    isConnectedToWard.value = true
                    shouldAutoReconnect = false
                    reconnectThread?.interrupt()
                    reconnectThread = null
                    updateGuardianNotification()
                    currentScreen.value = Screen.GuardianConnected(displayName)
                }

                startAudioPlayback(socket.getInputStream(), socket)
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}")
                runOnUiThread {
                    isConnectedToWard.value = false
                    // Don't stop foreground service if we're in auto-reconnect mode
                    if (!shouldAutoReconnect && currentScreen.value !is Screen.GuardianConnected) {
                        stopForegroundService()
                    }
                }
            }
        }
    }

    private fun startConnectionMonitor(socket: Socket) {
        isMonitoringConnection = true
        lastConnectionTime = System.currentTimeMillis()

        connectionMonitorThread = thread {
            var alertShown = false
            var statusUpdatedToDisconnected = false
            var lastStatusCheck = System.currentTimeMillis()
            
            try {
                while (isMonitoringConnection) {
                    try {
                        // Check if socket is still valid - update status immediately if disconnected
                        if (socket.isClosed || !socket.isConnected) {
                            Log.d(TAG, "Socket closed or disconnected in monitor")
                            if (!statusUpdatedToDisconnected) {
                                statusUpdatedToDisconnected = true
                                runOnUiThread {
                                    isConnectedToWard.value = false
                                }
                            }
                            if (!alertShown) {
                                alertShown = true
                                showConnectionLostAlert()
                            }
                            break
                        }
                        
                        Thread.sleep(1000)
                        val timeSinceLastData = System.currentTimeMillis() - lastConnectionTime
                        val timeSinceLastCheck = System.currentTimeMillis() - lastStatusCheck
                        
                        // Only update status if we've waited at least 2 seconds since last check to prevent flickering
                        if (timeSinceLastCheck >= 2000) {
                            lastStatusCheck = System.currentTimeMillis()
                            
                            // Update status if no data received for more than 3 seconds
                            if (timeSinceLastData > 3000) {
                                if (!statusUpdatedToDisconnected) {
                                    Log.d(TAG, "No data received for ${timeSinceLastData}ms, marking as disconnected")
                                    statusUpdatedToDisconnected = true
                                    runOnUiThread {
                                        isConnectedToWard.value = false
                                    }
                                }
                            } else {
                                // We're receiving data - only set connected if we were previously disconnected
                                // This prevents flickering when data arrives intermittently
                                if (statusUpdatedToDisconnected && timeSinceLastData < 2000) {
                                    statusUpdatedToDisconnected = false
                                    runOnUiThread {
                                        isConnectedToWard.value = true
                                    }
                                }
                            }
                        }
                        
                        // Show alert after full timeout
                        if (timeSinceLastData > CONNECTION_TIMEOUT_MS && !alertShown) {
                            alertShown = true
                            showConnectionLostAlert()
                        }
                    } catch (e: InterruptedException) {
                        Log.d(TAG, "Connection monitor interrupted")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in connection monitor: ${e.message}")
                        // Connection likely lost, update UI state immediately
                        if (!statusUpdatedToDisconnected) {
                            statusUpdatedToDisconnected = true
                            runOnUiThread {
                                isConnectedToWard.value = false
                            }
                        }
                        if (!alertShown) {
                            alertShown = true
                            showConnectionLostAlert()
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in connection monitor: ${e.message}")
                runOnUiThread {
                    isConnectedToWard.value = false
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
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT
            )

            val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            val fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                1,
                fullScreenIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT
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

            // For API 26+, set full screen intent for call-like behavior (works when locked)
            if (Build.VERSION.SDK_INT >= 26) {
                builder.setFullScreenIntent(fullScreenPendingIntent, true)
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
        
        // Set socket timeout to detect disconnection faster
        try {
            socket.soTimeout = 5000 // 5 second read timeout
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set socket timeout: ${e.message}")
        }
        
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
                var consecutiveTimeouts = 0

                while (
                    isPlayingAudio &&
                    !socket.isClosed &&
                    socket.isConnected &&
                    !Thread.currentThread().isInterrupted
                ) {
                    try {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead <= 0) {
                            // EOF or no data - connection likely closed
                            if (bytesRead == -1) {
                                Log.d(TAG, "End of stream (EOF) received")
                                runOnUiThread {
                                    isConnectedToWard.value = false
                                }
                                break
                            }
                            continue
                        }
                        
                        // Reset timeout counter on successful read
                        consecutiveTimeouts = 0
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
                    } catch (e: java.net.SocketTimeoutException) {
                        // Read timeout - data stopped coming
                        consecutiveTimeouts++
                        Log.d(TAG, "Socket read timeout #$consecutiveTimeouts")
                        // Update UI to show disconnected if we've had multiple timeouts
                        if (consecutiveTimeouts >= 2) {
                            runOnUiThread {
                                isConnectedToWard.value = false
                            }
                        }
                        // Continue trying to read - the connection monitor will handle alert
                        continue
                    } catch (e: java.net.SocketException) {
                        // Socket closed, exit gracefully
                        Log.d(TAG, "Socket closed during playback")
                        runOnUiThread {
                            isConnectedToWard.value = false
                        }
                        break
                    } catch (e: java.io.IOException) {
                        // IO error, connection lost
                        Log.d(TAG, "Connection lost during playback: ${e.message}")
                        runOnUiThread {
                            isConnectedToWard.value = false
                        }
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Unexpected error during playback: ${e.message}")
                        runOnUiThread {
                            isConnectedToWard.value = false
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback error: ${e.message}")
                runOnUiThread {
                    isConnectedToWard.value = false
                }
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
                    isConnectedToWard.value = false
                    isPttActive.value = false
                    
                    // If we're still on GuardianConnected screen, start auto-reconnect
                    if (currentScreen.value is Screen.GuardianConnected) {
                        val wardIp = connectedWardIp.value
                        val wardName = connectedWardNameState.value
                        if (wardIp != null) {
                            shouldAutoReconnect = true
                            updateGuardianNotification()
                            startAutoReconnect(wardIp, wardName)
                        } else {
                            // No ward IP, go back to discovery
                            currentScreen.value = Screen.Guardian
                            startDiscovery()
                        }
                    } else {
                        stopForegroundService()
                    }
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

    private fun startAutoReconnect(wardIp: String, wardName: String?) {
        // Don't start multiple reconnect threads
        if (shouldAutoReconnect && reconnectThread?.isAlive == true) {
            Log.d(TAG, "Reconnect thread already running")
            return
        }
        
        shouldAutoReconnect = true
        reconnectThread?.interrupt()
        runOnUiThread {
            updateGuardianNotification()
        }
        
        reconnectThread = thread {
            var attemptCount = 0
            while (shouldAutoReconnect && !Thread.currentThread().isInterrupted) {
                try {
                    attemptCount++
                    Log.d(TAG, "Reconnect attempt #$attemptCount to ward at $wardIp")
                    
                    // Don't wait on first attempt
                    if (attemptCount > 1) {
                        Thread.sleep(5000) // Wait 5 seconds between reconnect attempts
                    }
                    
                    if (!shouldAutoReconnect) break
                    if (currentScreen.value !is Screen.GuardianConnected) {
                        Log.d(TAG, "No longer on GuardianConnected screen, stopping reconnect")
                        shouldAutoReconnect = false
                        break
                    }
                    
                    // Attempt connection directly in this thread (not on UI thread)
                    try {
                        Log.d(TAG, "Attempting to connect to ward at $wardIp:$AUDIO_PORT")
                        val socket = Socket()
                        socket.connect(java.net.InetSocketAddress(wardIp, AUDIO_PORT), 10000)
                        Log.d(TAG, "Reconnection successful to $wardIp:$AUDIO_PORT")
                        
                        // Send device name to ward
                        val deviceName = Build.MODEL
                        socket.getOutputStream().write(deviceName.toByteArray())
                        socket.getOutputStream().flush()
                        
                        val displayName = wardName ?: wardIp
                        runOnUiThread {
                            connectedWardName.value = displayName
                            connectedWardNameState.value = displayName
                            connectedWardIp.value = wardIp
                            isConnectedToWard.value = true
                            shouldAutoReconnect = false
                            cancelConnectionLostAlert()
                            updateGuardianNotification()
                        }
                        
                        // Start audio playback on this successful connection
                        startAudioPlayback(socket.getInputStream(), socket)
                        break // Exit reconnect loop on success
                        
                    } catch (e: Exception) {
                        Log.d(TAG, "Reconnect attempt #$attemptCount failed: ${e.message}")
                        runOnUiThread {
                            isConnectedToWard.value = false
                        }
                    }
                    
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Reconnect thread interrupted")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in reconnect thread: ${e.message}")
                }
            }
            Log.d(TAG, "Reconnect thread exiting (shouldAutoReconnect=$shouldAutoReconnect)")
        }
    }

    private fun stopAudioPlayback() {
        isPlayingAudio = false
        shouldAutoReconnect = false
        reconnectThread?.interrupt()
        reconnectThread = null
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
