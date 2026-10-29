package com.example.cam2vault.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.cam2vault.camera.Camera2Manager
import com.example.cam2vault.service.VideoRecordingService
import com.example.cam2vault.ui.theme.Cam2VaultTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var camera2Manager: Camera2Manager
    private var recordingService: VideoRecordingService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VideoRecordingService.LocalBinder
            recordingService = localBinder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        camera2Manager = Camera2Manager(this)

        // Bind to background recording service
        Intent(this, VideoRecordingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        setContent {
            Cam2VaultTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreenContent(
                        camera2Manager = camera2Manager,
                        getService = { recordingService }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        camera2Manager.close()
    }
}

/**
 * Handles Android 14+ runtime permissions:
 * - CAMERA
 * - RECORD_AUDIO
 * - POST_NOTIFICATIONS (Android 13+ / API 33)
 */
@Composable
fun MainScreenContent(
    camera2Manager: Camera2Manager,
    getService: () -> VideoRecordingService?
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(checkAllPermissions(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] ?: false
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] ?: false
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else true

        hasPermissions = cameraGranted && audioGranted && notifGranted
        if (!hasPermissions) {
            Toast.makeText(context, "Permissions are required for background recording", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            val required = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                required.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(required.toTypedArray())
        }
    }

    if (hasPermissions) {
        CameraScreen(
            camera2Manager = camera2Manager,
            getService = getService
        )
    } else {
        PermissionRequestView(onRequestAgain = {
            val required = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                required.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(required.toTypedArray())
        })
    }
}

fun checkAllPermissions(context: Context): Boolean {
    val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val audio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true

    return camera && audio && notif
}
