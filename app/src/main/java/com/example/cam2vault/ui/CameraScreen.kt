package com.example.cam2vault.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.cam2vault.camera.Camera2Manager
import com.example.cam2vault.service.VideoRecordingService
import com.example.cam2vault.service.VolumeKeyController
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun CameraScreen(
    camera2Manager: Camera2Manager,
    getService: () -> VideoRecordingService?
) {
    val context = LocalContext.current

    val cameraId = remember { camera2Manager.getBackCameraId() }
    val modes = remember(cameraId) { camera2Manager.queryModes(cameraId) }
    val slowMo = modes.slowMo

    var selectedId by remember { mutableStateOf(modes.uhd60.id) }
    val selectedConfig = if (slowMo != null && selectedId == slowMo.id) slowMo else modes.uhd60

    val orientationMode = 0 // portrait only

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

    val service = getService()
    val stateFlow = remember(service) {
        service?.recordingState ?: MutableStateFlow(VideoRecordingService.RecordingState())
    }
    val recordingState by stateFlow.collectAsState()
    val busy = recordingState.isRecording || recordingState.isStarting

    // One toggle used by the on-screen button and by the volume buttons
    val toggleRecording = rememberUpdatedState<() -> Unit> {
        val s = getService()
        if (s == null) {
            Toast.makeText(context, "الخدمة لسه بتجهز، جرّب تاني", Toast.LENGTH_SHORT).show()
        } else {
            val st = s.recordingState.value
            if (st.isRecording) {
                s.stopRecording()
            } else if (!st.isStarting) {
                s.startRecording(selectedConfig, cameraId, orientationMode) { ok, msg ->
                    if (!ok) {
                        Toast.makeText(context, "فشل بدء التسجيل: ${msg ?: ""}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    DisposableEffect(Unit) {
        VolumeKeyController.onVolumeKey = { toggleRecording.value() }
        onDispose { VolumeKeyController.onVolumeKey = null }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Top: the two modes + options
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModeCard(
                    title = modes.uhd60.label,
                    subtitle = "${modes.uhd60.codecName} • ${modes.uhd60.formattedBitrate}",
                    selected = selectedConfig.id == modes.uhd60.id,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = { selectedId = modes.uhd60.id }
                )
                if (slowMo != null) {
                    ModeCard(
                        title = slowMo.label,
                        subtitle = "${slowMo.codecName} • ${slowMo.formattedBitrate} • بدون صوت",
                        selected = selectedConfig.id == slowMo.id,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedId = slowMo.id }
                    )
                } else {
                    ModeCard(
                        title = "Slow-Mo",
                        subtitle = "الجهاز مش بيدعمه",
                        selected = false,
                        enabled = false,
                        modifier = Modifier.weight(1f),
                        onClick = {}
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

            if (canOverlay) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "المعاينة: اسحبها بصباع للتحريك • بصباعين أو من الدايرة اللي في الركن للتكبير والتصغير",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }

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
                            text = "التسجيل شغال • تقدر تقفل الشاشة (زرار الصوت بيوقّف)",
                            color = Color(0xFFE2E8F0),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Bottom: timer + record button
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 40.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                        Text(text = timeString, color = Color.White, fontSize = 16.sp)
                    }
                }
            } else if (recordingState.isStarting) {
                Text(
                    text = "بيجهّز...",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
            }

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
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = Color(0xFF10B981)
    val bg = if (selected) accent.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.65f)
    val borderColor = if (selected) accent else Color(0x55FFFFFF)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = modifier.clickable(enabled = enabled) { onClick() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = title,
                color = if (enabled || selected) Color.White else Color(0xFF94A3B8),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color(0xFFCBD5E1),
                fontSize = 11.sp
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
            text = "محتاج صلاحيات",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Cam2Vault محتاج صلاحية الكاميرا والميكروفون والإشعارات عشان يسجّل والشاشة مقفولة.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestAgain) {
            Text("إدّي الصلاحيات")
        }
    }
}
