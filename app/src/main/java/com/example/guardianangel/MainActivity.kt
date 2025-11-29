package com.example.guardianangel

import android.Manifest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.guardianangel.ui.theme.GuardianAngelTheme
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val currentScreen = mutableStateOf<Screen>(Screen.Main)
    private val discoveredDevices = mutableStateListOf<NsdServiceInfo>()
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serverSocket: ServerSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isStreaming = false

    companion object {
        private const val TAG = "GuardianAngel"
        private const val AUDIO_PORT = 5353
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
    
        setContent {
            GuardianAngelTheme {
                when (currentScreen.value) {
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
                        }
                    )
                    Screen.Ward -> WardScreen(
                        onBack = {
                            currentScreen.value = Screen.Main
                            stopAudioBroadcast()
                        }
                    )
                }
            }
        }
    }
    
    internal fun startBroadcasting() {
        val deviceName = Build.MODEL

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
                while (!isStreaming) {
                    val client = serverSocket?.accept()
                    client?.let {
                        Log.d(TAG, "Guardian connected")
                        startAudioCapture(it.getOutputStream())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private fun startAudioCapture(outputStream: OutputStream) {
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
        serverSocket?.close()
        serverSocket = null
    }
    
    internal fun startDiscovery() {
        discoveredDevices.clear()
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
            }
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode")
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Service resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}:${serviceInfo.port}")
                        discoveredDevices.add(serviceInfo)
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                discoveredDevices.removeAll { it.serviceName == serviceInfo.serviceName }
            }
        }
        nsdManager?.discoverServices("_ward._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    internal fun connectToWard(deviceName: String) {
        val serviceInfo = discoveredDevices.find { it.serviceName == deviceName }
        serviceInfo?.let {
            thread {
                try {
                    val socket = Socket(it.host, it.port)
                    Log.d(TAG, "Connected to ward at ${it.host}:${it.port}")
                    startAudioPlayback(socket.getInputStream())
                } catch (e: Exception) {
                    Log.e(TAG, "Connection error: ${e.message}")
                }
            }
        }
    }

    private fun startAudioPlayback(inputStream: InputStream) {
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

                audioTrack?.play()
                val buffer = ByteArray(bufferSize)

                while (true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    audioTrack?.write(buffer, 0, bytesRead)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio playback error: ${e.message}")
            } finally {
                audioTrack?.stop()
                audioTrack?.release()
                inputStream.close()
            }
        }
    }

    private fun stopAudioPlayback() {
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
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
}

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
fun WardScreen(onBack: () -> Unit) {
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
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp, top = 32.dp)
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
                    .align(Alignment.Center)
                    .padding(top = 32.dp),
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }

        // Status Message
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Ward is active",
                style = TextStyle(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            )
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
        requestMicrophonePermission()
        startBroadcasting()
    }
}

@Composable
fun GuardianScreen(
    devices: List<String>,
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
                .background(Color(0xFF2C5F8D))
        ) {
            IconButton(
                onClick = { showDialog.value = true },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp, top = 32.dp)
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
                    .align(Alignment.Center)
                    .padding(top = 32.dp),
                style = TextStyle(
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            )
        }

        // Devices List
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(devices) { device ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2C2C2C), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable {
                            activity?.connectToWard(device)
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