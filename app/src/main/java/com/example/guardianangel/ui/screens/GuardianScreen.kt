package com.example.guardianangel.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp
import com.example.guardianangel.MainActivity

@Composable
fun GuardianScreen(
    devices: List<String>,
    onBack: () -> Unit,
    onManualConnect: (String) -> Unit,
    onDeviceConnect: (String) -> Unit
) {
    val activity = LocalContext.current as? MainActivity
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
                            .background(
                                Color(0xFF2C2C2C),
                                shape = RoundedCornerShape(8.dp)
                            )
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
        AlertDialog(
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
                TextButton(
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
                TextButton(
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
        AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = {
                Text(text = "Exit Guardian?")
            },
            text = {
                Text(text = "Are you sure you want to go back to the main screen?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDialog.value = false
                        onBack()
                    }
                ) {
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDialog.value = false }
                ) {
                    Text("No")
                }
            }
        )
    }

    // Start discovery once when screen is opened
    LaunchedEffect(key1 = true) {
        activity?.startDiscovery()
    }
}
