package com.example.cam2vault.ui

import android.graphics.SurfaceTexture
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.view.Surface as AndroidSurface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cam2vault.camera.Camera2Manager
import com.example.cam2vault.data.VideoConfig
import com.example.cam2vault.service.VideoRecordingService
import com.example.cam2vault.service.VolumeKeyController
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    camera2Manager: Camera2Manager,
    getService: () -> VideoRecordingService?
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val cameraId = remember { camera2Manager.getBackCameraId() }
    val supportedConfigs = remember(cameraId) {
        camera2Manager.querySupportedVideoConfigurations(cameraId)
    }

    var selectedConfig by remember {
        mutableStateOf(
            supportedConfigs.firstOrNull { it.fps == 60 && it.resolution.width >= 3840 }
                ?: supportedConfigs.firstOrNull { it.fps == 60 }
                ?: supportedConfigs.firstOrNull()
                ?: VideoConfig(
            id = "default",
            resolution = android.util.Size(1920, 1080),
            fps = 30,
            bitrate = 14_000_000,
            label = "1080p FHD (1920x1080)"
        ))
    }

    var orientationMode by remember { mutableIntStateOf(1) } // 0 portrait, 1 landscape, 2 landscape flipped
    var canOverlay by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    DisposableEffect(Unit) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canOverlay = AndroidSettings.canDrawOverlays(context)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    var showConfigDialog by remember { mutableStateOf(false) }

    val service = getService()
    val stateFlow = remember(service) {
        service?.recordingState ?: MutableStateFlow(VideoRecordingService.RecordingState())
    }
    val recordingState by stateFlow.collectAsState()

    // Single toggle used by both the on-screen button and the volume buttons
    val toggleRecording = rememberUpdatedState<() -> Unit> {
        val activeService = getService()
        if (activeService != null) {
            if (activeService.recordingState.value.isRecording) {
                activeService.stopRecording()
            } else {
                val ok = activeService.startRecording(
                    config = selectedConfig,
                    cameraManager = camera2Manager,
                    cameraId = cameraId,
                    previewSurface = null,
                    orientationMode = orientationMode
                )
                if (!ok) {
                    Toast.makeText(context, "فشل بدء التسجيل - جرّب إعداداً آخر", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    DisposableEffect(Unit) {
        VolumeKeyController.onVolumeKey = { toggleRecording.value() }
        onDispose { VolumeKeyController.onVolumeKey = null }
    }

    LaunchedEffect(cameraId) {
        try {
            camera2Manager.openCamera(cameraId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 2. Top Header Overlay (Config badge, HEVC indicator, Lockscreen reminder)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Resolution & FPS selector button
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.clickable(enabled = !recordingState.isRecording) {
                        showConfigDialog = true
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Resolution/FPS",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${selectedConfig.label} • ${selectedConfig.fps} FPS",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }

                // HEVC Codec Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.25f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981))
                ) {
                    Text(
                        text = "HEVC / H.265",
                        color = Color(0xFF10B981),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.clickable(enabled = !recordingState.isRecording) {
                        orientationMode = (orientationMode + 1) % 3
                    }
                ) {
                    Text(
                        text = when (orientationMode) {
                            1 -> "عرضي 16:9"
                            2 -> "عرضي مقلوب 16:9"
                            else -> "طولي 9:16"
                        },
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
                if (!canOverlay) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFB45309),
                        modifier = Modifier.clickable {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    ) {
                        Text(
                            text = "فعّل المعاينة العائمة",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Screen-off background notice
            if (recordingState.isRecording) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E293B).copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Foreground Service & WakeLock active • Screen can turn off safely",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // 3. Bottom Recording Controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 40.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Elapsed Timer when recording
            if (recordingState.isRecording) {
                val sec = (recordingState.elapsedMs / 1000) % 60
                val min = (recordingState.elapsedMs / (1000 * 60)) % 60
                val hrs = (recordingState.elapsedMs / (1000 * 60 * 60))
                val timeString = String.format("%02d:%02d:%02d", hrs, min, sec)

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Red.copy(alpha = 0.85f),
                    modifier = Modifier.padding(bottom = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = timeString,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // Record / Stop Button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .clickable { toggleRecording.value() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (recordingState.isRecording) 32.dp else 64.dp)
                        .clip(if (recordingState.isRecording) RoundedCornerShape(6.dp) else CircleShape)
                        .background(Color.Red)
                )
            }
        }

        // 4. Configuration Picker Dialog (Only displays verified supported combinations!)
        if (showConfigDialog) {
            AlertDialog(
                onDismissRequest = { showConfigDialog = false },
                title = { Text("StreamConfigurationMap Supported Profiles") },
                text = {
                    Column {
                        Text(
                            text = "Only resolutions and FPS verified directly on this device sensor are listed:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        supportedConfigs.forEach { config ->
                            val isSelected = config.id == selectedConfig.id
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        selectedConfig = config
                                        showConfigDialog = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = config.label,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "${config.fps} FPS • ${config.formattedBitrate} • HEVC",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (config.is4K && config.fps == 60) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFF59E0B)
                                        ) {
                                            Text(
                                                text = "4K 60FPS",
                                                color = Color.Black,
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showConfigDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }
    }
}

@Composable
fun PermissionRequestView(onRequestAgain: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Cam2Vault requires Camera, Microphone, and Notification permissions to record HEVC video continuously with the screen turned off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestAgain) {
            Text("Grant Permissions")
        }
    }
}
