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
import android.media.MediaRecorder
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.cam2vault.camera.Camera2Manager
import com.example.cam2vault.data.VideoConfig
import com.example.cam2vault.ui.MainActivity
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service (camera + microphone).
 *
 * The service owns the camera, the MediaRecorder and the preview for the whole recording, so the
 * recording keeps going even if the Activity is closed or destroyed.
 */
class VideoRecordingService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentFileDescriptor: ParcelFileDescriptor? = null
    private var currentVideoUri: Uri? = null

    private var recordingStartTime = 0L
    private var timerJob: Job? = null
    private var startJob: Job? = null
    private var mediaSession: MediaSession? = null
    private var floatingPreview: FloatingPreview? = null
    private var activeCamera: Camera2Manager? = null

    private val _recordingState = MutableStateFlow(RecordingState())
    val recordingState = _recordingState.asStateFlow()

    data class RecordingState(
        val isRecording: Boolean = false,
        val isStarting: Boolean = false,
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_RECORDING -> stopRecording()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------------------------------
    // Start
    // ---------------------------------------------------------------------------------------------

    /**
     * Starts recording asynchronously. [onResult] is called on the main thread:
     * (true, null) when frames are flowing, (false, reason) when it failed.
     */
    fun startRecording(
        config: VideoConfig,
        cameraId: String,
        orientationMode: Int,
        onResult: (Boolean, String?) -> Unit
    ) {
        val state = _recordingState.value
        if (state.isRecording || state.isStarting) {
            onResult(false, "التسجيل شغال بالفعل")
            return
        }
        _recordingState.value = RecordingState(isStarting = true, activeConfig = config)

        startJob = serviceScope.launch {
            try {
                doStart(config, cameraId, orientationMode)
                onResult(true, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording: ${e.message}", e)
                abortStart()
                onResult(false, e.message ?: "فشل بدء التسجيل")
            }
        }
    }

    private suspend fun doStart(config: VideoConfig, cameraId: String, orientationMode: Int) {
        val cam = Camera2Manager(applicationContext)
        activeCamera = cam

        // 1. MediaStore output
        val (uri, pfd) = createMediaStoreVideoFile(config)
        currentVideoUri = uri
        currentFileDescriptor = pfd

        // 2. Keep CPU awake + foreground service (camera|microphone)
        acquireWakeLock()
        ContextCompat.startForegroundService(this, Intent(this, VideoRecordingService::class.java))
        startServiceForeground(config)

        // 3. MediaRecorder
        val hint = orientationHint(cam.getSensorOrientation(cameraId), orientationMode)
        val recorder = buildRecorder(config, hint, pfd)
        mediaRecorder = recorder

        // 4. Camera (owned by this service)
        cam.openCamera(cameraId)
        cam.onCameraLost = { stopRecording() }

        // 5. Optional floating preview
        var previewSurface: Surface? = null
        if (Settings.canDrawOverlays(this)) {
            val preview = FloatingPreview(this)
            floatingPreview = preview
            val bufferW = if (config.isHighSpeed) config.resolution.width else 1280
            val bufferH = if (config.isHighSpeed) config.resolution.height else 720
            previewSurface = withTimeoutOrNull(2500L) {
                suspendCancellableCoroutine<Surface?> { c ->
                    preview.show(bufferW, bufferH, hint) { s ->
                        if (c.isActive) c.resume(s)
                    }
                }
            }
        }

        // 6. Capture session (normal for 4K60, constrained high-speed for slow-mo)
        val recorderSurface = recorder.surface
        suspendCancellableCoroutine<Unit> { c ->
            cam.startRecordingCaptureSession(
                cameraId = cameraId,
                config = config,
                recorderSurface = recorderSurface,
                previewSurface = previewSurface,
                onReady = { if (c.isActive) c.resume(Unit) },
                onError = { msg -> if (c.isActive) c.resumeWithException(RuntimeException(msg)) }
            )
        }

        // 7. Frames are flowing: start the encoder
        recorder.start()
        recordingStartTime = SystemClock.elapsedRealtime()

        _recordingState.value = RecordingState(
            isRecording = true,
            elapsedMs = 0L,
            activeConfig = config,
            savedUri = uri,
            currentFileName = uri.lastPathSegment
        )

        activateMediaSession()
        startTimer(config)
        Log.i(TAG, "Recording started: ${config.label} ${config.codecName}")
    }

    private fun buildRecorder(config: VideoConfig, orientationHint: Int, pfd: ParcelFileDescriptor): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.apply {
                if (config.withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                setVideoEncoder(
                    if (config.useHevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264
                )
                if (config.withAudio) setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                setVideoSize(config.resolution.width, config.resolution.height)
                setVideoFrameRate(config.fps)
                setVideoEncodingBitRate(config.bitrate)

                if (config.withAudio) {
                    setAudioEncodingBitRate(192000)
                    setAudioSamplingRate(48000)
                }

                setOrientationHint(orientationHint)
                setOutputFile(pfd.fileDescriptor)
                prepare()
            }
        } catch (e: Exception) {
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            throw e
        }
        return recorder
    }

    private fun orientationHint(sensorOrientation: Int, orientationMode: Int): Int =
        when (orientationMode) {
            1 -> (sensorOrientation + 270) % 360
            2 -> (sensorOrientation + 90) % 360
            else -> sensorOrientation
        }

    // ---------------------------------------------------------------------------------------------
    // Stop / cleanup
    // ---------------------------------------------------------------------------------------------

    /** Start failed or was cancelled: release everything and delete the half-written file. */
    private fun abortStart() {
        timerJob?.cancel()
        activeCamera?.let {
            it.onCameraLost = null
            it.stopCaptureSession()
            it.close()
        }
        activeCamera = null
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        try {
            currentFileDescriptor?.close()
        } catch (_: Exception) {
        }
        currentVideoUri?.let {
            try {
                contentResolver.delete(it, null, null)
            } catch (_: Exception) {
            }
        }
        currentVideoUri = null
        cleanupRecording()
        _recordingState.value = RecordingState()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    /**
     * Gracefully stops recording, releases the camera, closes MediaRecorder and finalizes the
     * MediaStore entry (a recording that is too short to be valid is deleted instead of kept broken).
     */
    fun stopRecording() {
        val state = _recordingState.value
        if (!state.isRecording) {
            if (state.isStarting) {
                startJob?.cancel()
                abortStart()
            }
            return
        }

        timerJob?.cancel()

        activeCamera?.let {
            it.onCameraLost = null
            it.stopCaptureSession()
        }

        var stoppedCleanly = true
        val recorder = mediaRecorder
        try {
            recorder?.stop()
        } catch (e: Exception) {
            stoppedCleanly = false
            Log.e(TAG, "MediaRecorder.stop failed (recording too short?): ${e.message}")
        }
        try {
            recorder?.reset()
            recorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaRecorder: ${e.message}")
        }
        mediaRecorder = null

        activeCamera?.close()
        activeCamera = null

        try {
            currentFileDescriptor?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing file descriptor", e)
        }

        val uri = currentVideoUri
        var savedUri: Uri? = null
        if (uri != null) {
            if (stoppedCleanly) {
                finalizeMediaStoreVideo(uri, SystemClock.elapsedRealtime() - recordingStartTime)
                savedUri = uri
            } else {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
            }
        }

        cleanupRecording()
        currentVideoUri = null

        _recordingState.value = RecordingState(savedUri = savedUri)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startServiceForeground(config: VideoConfig) {
        val notification = buildRecordingNotification(config, 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, notification, types)
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

                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, buildRecordingNotification(config, elapsed))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MediaStore
    // ---------------------------------------------------------------------------------------------

    private fun createMediaStoreVideoFile(config: VideoConfig): Pair<Uri, ParcelFileDescriptor> {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = if (config.isHighSpeed) "SLOMO" else "VID"
        val fileName = "${prefix}_${timeStamp}_${config.resolution.width}x${config.resolution.height}" +
                "_${config.fps}fps_${config.codecName.replace(".", "")}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Cam2Vault")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("مقدرتش أعمل ملف الفيديو")

        val pfd = contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("مقدرتش أفتح ملف الفيديو للكتابة")

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

    // ---------------------------------------------------------------------------------------------
    // Wake lock / media session / notification
    // ---------------------------------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Cam2Vault:BackgroundVideoRecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    /** Routes the hardware volume buttons to this app while recording, even with the screen off. */
    private fun activateMediaSession() {
        try {
            releaseMediaSession()
            val session = MediaSession(this, "Cam2VaultVolumeControl")
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build()
            )
            session.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction != 0) VolumeKeyController.trigger()
                }
            })
            session.isActive = true
            mediaSession = session
        } catch (e: Exception) {
            Log.e(TAG, "MediaSession setup failed: ${e.message}", e)
        }
    }

    private fun releaseMediaSession() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (_: Exception) {
        }
        mediaSession = null
    }

    private fun cleanupRecording() {
        currentFileDescriptor = null
        floatingPreview?.hide()
        floatingPreview = null
        releaseMediaSession()
        releaseWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Camera2 Background Recording",
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
        val timeFormatted = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("بيسجّل • $timeFormatted")
            .setContentText("${config.label} • ${config.codecName}")
            .setSmallIcon(android.R.drawable.presence_video_busy)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف التسجيل", stopPendingIntent)
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
