package com.shreya.cameraapp

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.bluetooth.BluetoothDevice
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import java.util.Base64
import java.io.ByteArrayOutputStream
import androidx.compose.foundation.BorderStroke
import android.annotation.SuppressLint
import android.util.Log

/**
 * Main dialog for choosing role (Phone A or Phone B)
 */
@Composable
fun RemoteModeDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    onHostSelected: () -> Unit,
    onControllerSelected: () -> Unit
) {
    if (!showDialog) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Remote Camera Mode", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Use two phones for solo photography",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Phone A (Camera)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onHostSelected() },
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF6C5CE7).copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = Color(0xFF6C5CE7)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("📷", fontSize = 24.sp)
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                "Phone A (Camera)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Place this phone to take the photo",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // Phone B (Remote)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onControllerSelected() },
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFF9500).copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = Color(0xFFFF9500)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("📱", fontSize = 24.sp)
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                "Phone B (Remote)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Hold this phone to control camera",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Device discovery screen for Phone B
 */
@Composable
fun DeviceDiscoveryDialog(
    showDialog: Boolean,
    devices: List<BluetoothDevice>,
    isSearching: Boolean,
    onDismiss: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onRefresh: () -> Unit
) {
    if (!showDialog) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find Phone A") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (isSearching) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Searching for devices...")
                    }
                } else if (devices.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No devices found")
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onRefresh) {
                            Text("Search Again")
                        }
                    }
                } else {
                    Text(
                        "Found ${devices.size} device(s):",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(devices) { device ->
                            DeviceListItem(
                                device = device,
                                onClick = { onDeviceSelected(device) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
@SuppressLint("MissingPermission")
@Composable
private fun DeviceListItem(
    device: BluetoothDevice,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color(0xFF6C5CE7).copy(alpha = 0.2f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("📱", fontSize = 20.sp)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name ?: "Unknown Device",
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Tap to connect",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * Connection status indicator (top of screen)
 */
@Composable
fun RemoteConnectionStatusBar(
    connectionState: RemoteConnectionManager.ConnectionState,
    deviceRole: RemoteConnectionManager.DeviceRole,
    connectedDeviceName: String,
    onDisconnect: () -> Unit
) {
    AnimatedVisibility(
        visible = connectionState != RemoteConnectionManager.ConnectionState.DISCONNECTED,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (connectionState) {
                    RemoteConnectionManager.ConnectionState.CONNECTED -> Color(0xFF4CAF50)
                    RemoteConnectionManager.ConnectionState.CONNECTING -> Color(0xFFFF9500)
                    else -> Color(0xFF9E9E9E)
                }.copy(alpha = 0.9f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status icon
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Status text
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        when (deviceRole) {
                            RemoteConnectionManager.DeviceRole.HOST_CAMERA -> "📷 Phone A (Camera)"
                            RemoteConnectionManager.DeviceRole.CONTROLLER_REMOTE -> "📱 Phone B (Remote)"
                            else -> "Remote Mode"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    Text(
                        when (connectionState) {
                            RemoteConnectionManager.ConnectionState.CONNECTED ->
                                if (connectedDeviceName.isNotEmpty()) "Connected to $connectedDeviceName" else "Connected"
                            RemoteConnectionManager.ConnectionState.CONNECTING -> "Connecting..."
                            RemoteConnectionManager.ConnectionState.DISCOVERING -> "Searching..."
                            else -> "Disconnected"
                        },
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp
                    )
                }

                // Disconnect button
                if (connectionState == RemoteConnectionManager.ConnectionState.CONNECTED) {
                    TextButton(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White
                        )
                    ) {
                        Text("Disconnect", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/**
 * Remote control panel (visible on Phone B)
 */
@Composable
fun RemoteControlPanel(
    modifier: Modifier = Modifier,
    previewBitmap: Bitmap? = null, // ADD THIS PARAMETER
    onCaptureClick: () -> Unit,
    onFlashToggle: () -> Unit,
    onCameraSwitch: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize()
            .background(Color.Black), // ADD background
        verticalArrangement = Arrangement.SpaceBetween
    ){
        // Live Preview from Phone A
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "Live Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    "Waiting for camera preview...",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Control buttons at bottom
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.8f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Main capture button
                Surface(
                    modifier = Modifier
                        .size(72.dp)
                        .clickable(onClick = {
                            Log.d("RemoteUI", "Capture button clicked!")
                            onCaptureClick()
                        }),

                    shape = CircleShape,
                    color = Color(0xFFFF3B30),
                    border = BorderStroke(4.dp, Color.White)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Camera,
                            contentDescription = "Capture",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Control buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ControlButton(icon = "⚡", label = "Flash", onClick = onFlashToggle)
                    ControlButton(icon = "🔄", label = "Switch", onClick = onCameraSwitch)
                    ControlButton(icon = "🔍+", label = "Zoom In", onClick = onZoomIn)
                    ControlButton(icon = "🔍-", label = "Zoom Out", onClick = onZoomOut)
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: String,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.2f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(icon, fontSize = 20.sp)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            color = Color.White,
            fontSize = 10.sp
        )
    }
}