package com.example.guardianangel

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
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
    private var serverSocket: ServerSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isStreaming = false
    private val localIpAddress = mutableStateOf("")
    private var foregroundService: ForegroundService? = null
    private var serviceBound = false
    private var clientSockets = mutableListOf<Socket>()
    val streamVolume = mutableStateOf(0.5f)

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
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val NOTIFICATION_CHANNEL_ID = "guardian_angel_channel"
        const val NOTIFICATION_ID = 1
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        createNotificationChannel()
        localIpAddress.value = getLocalIpAddress()

        setContent {
            GuardianAngelTheme {
                when (val screen = currentScreen.value) {
                    Screen.Main -> MainScreen(
                        onGuardianClick = { currentScreen.value = Screen.Guardian },
                        onWardClick = { currentScreen.value = Screen.Ward }
                    )
                    Screen.Guardian -> GuardianScreen(
                        devices = discoveredDevices
                            .filter { !it.serviceName.contains(Build.MODEL) }
                            .map { it.serviceName },
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
                        onVolumeChange = { newVolume ->
                            streamVolume.value = newVolume
                            audioTrack?.setVolume(newVolume)
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
                val channel = constructor.newInstance(NOTIFICATION_CHANNEL_ID, "Guardian Angel Service", 2) // IMPORTANCE_LOW = 2

                // Set description
                val descField = channelClass.getMethod("setDescription", String::class.java)
                descField.invoke(channel, "Keeps the audio streaming service running")

                // Create channel
                val createMethod = NotificationManager::class.java.getMethod("createNotificationChannel", channelClass)
                createMethod.invoke(notificationManager, channel)
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
    
    internal fun startBroadcasting() {
        // Check permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermission()
            return
        }

        val deviceName = Build.MODEL
        startForegroundService("Ward Active", "Waiting for guardian connection...")

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

                nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD,
                    object : NsdManager.RegistrationListener {
                        override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                            Log.d(TAG, "Service registered: ${nsdServiceInfo.serviceName}")
                        }
                        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Registration failed: $errorCode")
                        }
                        override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
                        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    })

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
                                connectedGuardians.add(connection)
                                clientSockets.add(socket)
                                
                                // Monitor connection
                                thread {
                                    try {
                                        while (!socket.isClosed && socket.isConnected) {
                                            Thread.sleep(1000)
                                        }
                                    } catch (e: Exception) {
                                        Log.d(TAG, "Connection monitoring ended: ${e.message}")
                                    } finally {
                                        runOnUiThread {
                                            connectedGuardians.removeAll { it.socket == socket }
                                            clientSockets.remove(socket)
                                        }
                                        Log.d(TAG, "Guardian $guardianName disconnected")
                                    }
                                }
                                
                                startAudioCapture(socket.getOutputStream())
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

    private fun startAudioCapture(outputStream: OutputStream) {
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
                )

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
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
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception - permission denied: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error: ${e.message}")
            } finally {
                audioRecord?.stop()
                audioRecord?.release()
                outputStream.close()
            }
        }
    }

    private fun stopAudioBroadcast() {
        isStreaming = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        clientSockets.forEach { 
            try { it.close() } catch (e: Exception) { }
        }
        clientSockets.clear()
        
        serverSocket?.close()
        serverSocket = null
        connectedGuardians.clear()
    }
    
    internal fun startDiscovery() {
        discoveredDevices.clear()
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
                // Resolve on a separate thread to avoid blocking
                thread {
                    try {
                        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                            }
                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                Log.d(TAG, "Service resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}:${serviceInfo.port}")
                                runOnUiThread {
                                    if (!discoveredDevices.any { it.serviceName == serviceInfo.serviceName }) {
                                        discoveredDevices.add(serviceInfo)
                                    }
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resolving service: ${e.message}")
                    }
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                runOnUiThread {
                    discoveredDevices.removeAll { it.serviceName == serviceInfo.serviceName }
                }
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
            val wardName = it.serviceName.removePrefix("ward_")
            connectToWardByIp(it.host.hostAddress ?: "", wardName)
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

    private fun startAudioPlayback(inputStream: InputStream, socket: Socket) {
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

                while (!socket.isClosed && socket.isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    audioTrack?.write(buffer, 0, bytesRead)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback error: ${e.message}")
            } finally {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                try {
                    inputStream.close()
                    socket.close()
                } catch (e: Exception) { }
                
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

    private fun stopAudioPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        connectedWardName.value = null
    }
    
    private fun stopDiscovery() {
        try {
            discoveryListener?.let {
                nsdManager?.stopServiceDiscovery(it)
                Log.d(TAG, "Stopping discovery")
            }
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Manual connect button
            Button(
                onClick = { showManualConnectDialog.value = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C5F8D))
            ) {
                Text("Connect Manually")
            }

            Text(
                text = "Discovered Devices",
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                ),
                modifier = Modifier.padding(top = 8.dp)
            )

            // Devices List
            LazyColumn(
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

    // Start discovery
    activity?.startDiscovery()
}

@Composable
fun GuardianConnectedScreen(
    wardName: String,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
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
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(12.dp)
                            .background(Color(0xFF00FF00), shape = androidx.compose.foundation.shape.CircleShape)
                    )
                    Text(
                        text = "Audio streaming active",
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
    }
}