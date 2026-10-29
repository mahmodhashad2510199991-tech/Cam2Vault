package com.example.cam2vault.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.example.cam2vault.R
import com.example.cam2vault.camera.Camera2Manager
import com.example.cam2vault.data.VideoConfig
import com.example.cam2vault.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground Service running camera & microphone types.
 * Keeps recording continuously with WakeLock even when the phone screen turns off.
 */
class VideoRecordingService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentFileDescriptor: ParcelFileDescriptor? = null
    private var currentVideoUri: Uri? = null

    private var recordingStartTime = 0L
    private var timerJob: Job? = null

    private val _recordingState = MutableStateFlow(RecordingState())
    val recordingState = _recordingState.asStateFlow()

    data class RecordingState(
        val isRecording: Boolean = false,
        val elapsedMs: Long = 0L,
        val activeConfig: VideoConfig? = null,
        val savedUri: Uri? = null,
        val currentFileName: String? = null
    )

    inner class LocalBinder : Binder() {
        fun getService(): VideoRecordingService = this@VideoRecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RECORDING -> stopRecording()
        }
        return START_NOT_STICKY
    }

    /**
     * Initializes MediaRecorder for HEVC (H.265), prepares MediaStore row,
     * promotes service to foreground, and binds camera surfaces.
     */
    fun startRecording(
        config: VideoConfig,
        cameraManager: Camera2Manager,
        cameraId: String,
        previewSurface: Surface?
    ): Boolean {
        try {
            // 1. Setup MediaStore output file
            val (uri, pfd) = createMediaStoreVideoFile(config)
            currentVideoUri = uri
            currentFileDescriptor = pfd

            // 2. Initialize MediaRecorder with HEVC
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                // HEVC (H.265) video encoder
                setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                // Resolution and framerate
                setVideoSize(config.resolution.width, config.resolution.height)
                setVideoFrameRate(config.fps)
                setVideoEncodingBitRate(config.bitrate)

                // High quality audio
                setAudioEncodingBitRate(192000)
                setAudioSamplingRate(48000)

                // Orientation hint from camera sensor
                val sensorOrientation = cameraManager.getSensorOrientation(cameraId)
                setOrientationHint(sensorOrientation)

                setOutputFile(pfd.fileDescriptor)
                prepare()
            }

            // 3. Promote to Foreground Service before starting recording (Android 14 requirements)
            startServiceForeground(config)

            // 4. Start MediaRecorder
            mediaRecorder?.start()
            recordingStartTime = SystemClock.elapsedRealtime()

            // 5. Connect Camera2 Capture Session with MediaRecorder Surface
            val recorderSurface = mediaRecorder!!.surface
            cameraManager.startRecordingCaptureSession(cameraId, config, recorderSurface, previewSurface)

            _recordingState.value = RecordingState(
                isRecording = true,
                elapsedMs = 0L,
                activeConfig = config,
                savedUri = uri,
                currentFileName = uri.lastPathSegment
            )

            startTimer(config)
            Log.i(TAG, "Recording started successfully: ${config.label} HEVC at ${config.fps}fps")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            cleanupRecording()
            return false
        }
    }

    /**
     * Gracefully stops recording, releases Camera2, closes MediaRecorder,
     * updates MediaStore IS_PENDING flag, and removes foreground state.
     */
    fun stopRecording() {
        if (!_recordingState.value.isRecording) return

        timerJob?.cancel()
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaRecorder: ${e.message}", e)
        } finally {
            mediaRecorder = null
        }

        try {
            currentFileDescriptor?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing file descriptor", e)
        }

        // Finalize MediaStore entry: set IS_PENDING = 0
        currentVideoUri?.let { uri ->
            finalizeMediaStoreVideo(uri, SystemClock.elapsedRealtime() - recordingStartTime)
        }

        val lastUri = currentVideoUri
        cleanupRecording()

        _recordingState.value = RecordingState(
            isRecording = false,
            elapsedMs = 0L,
            activeConfig = null,
            savedUri = lastUri
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startServiceForeground(config: VideoConfig) {
        val notification = buildRecordingNotification(config, 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires explicit camera | microphone foreground service types
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, notification, fgsTypes)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTimer(config: VideoConfig) {
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                val elapsed = SystemClock.elapsedRealtime() - recordingStartTime
                _recordingState.value = _recordingState.value.copy(elapsedMs = elapsed)

                // Update notification with live recording duration
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildRecordingNotification(config, elapsed))
            }
        }
    }

    /**
     * MediaStore insertion with IS_PENDING = 1 for scoped storage compliance.
     */
    private fun createMediaStoreVideoFile(config: VideoConfig): Pair<Uri, ParcelFileDescriptor> {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "VID_${timeStamp}_${config.resolution.width}x${config.resolution.height}_HEVC.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Cam2Vault")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to insert MediaStore record")

        val pfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("Failed to open ParcelFileDescriptor for URI: $uri")

        return Pair(uri, pfd)
    }

    private fun finalizeMediaStoreVideo(uri: Uri, durationMs: Long) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
            put(MediaStore.Video.Media.DURATION, durationMs)
        }
        contentResolver.update(uri, values, null, null)
        Log.i(TAG, "MediaStore video finalized at $uri (Duration: ${durationMs}ms)")
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Cam2Vault:BackgroundVideoRecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // Safe 24hr timeout max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun cleanupRecording() {
        currentFileDescriptor = null
        releaseWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Camera2 HEVC Background Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live recording status with screen off"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildRecordingNotification(config: VideoConfig, elapsedMs: Long): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VideoRecordingService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val seconds = (elapsedMs / 1000) % 60
        val minutes = (elapsedMs / (1000 * 60)) % 60
        val hours = (elapsedMs / (1000 * 60 * 60))
        val timeFormatted = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording HEVC Video • $timeFormatted")
            .setContentText("${config.label} @ ${config.fps}fps (Screen-off enabled)")
            .setSmallIcon(android.R.drawable.presence_video_busy)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop Recording", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        serviceScope.cancel()
    }

    companion object {
        const val TAG = "VideoRecordingService"
        const val CHANNEL_ID = "cam2vault_recording_channel"
        const val NOTIFICATION_ID = 4001
        const val ACTION_STOP_RECORDING = "com.example.cam2vault.action.STOP"
    }
}
