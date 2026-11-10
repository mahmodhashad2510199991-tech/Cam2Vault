package com.example.cam2vault.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.CamcorderProfile
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.example.cam2vault.data.RecordingModes
import com.example.cam2vault.data.VideoConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Camera2 helper.
 *
 * - [queryModes] returns the two supported modes: 4K 60fps and 1080p slow-motion (240fps when the
 *   phone exposes it through the constrained high-speed API).
 * - The recording service owns an instance of this class for the whole recording, so recording does
 *   not depend on the Activity staying alive.
 */
class Camera2Manager(private val context: Context) {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    /** Called on the main thread if the camera is taken away while recording (another app, error...). */
    @Volatile
    var onCameraLost: (() -> Unit)? = null

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2BackgroundThread").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Modes
    // ---------------------------------------------------------------------------------------------

    fun queryModes(cameraId: String): RecordingModes {
        val map = try {
            cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read stream map", e)
            null
        }
        return RecordingModes(
            uhd60 = build4k60(cameraId, map),
            slowMo = if (map != null) buildSlowMo(map) else null
        )
    }

    private fun build4k60(cameraId: String, map: StreamConfigurationMap?): VideoConfig {
        val size = Size(3840, 2160)
        var verified = false
        if (map != null) {
            val sizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
            val sizeOk = sizes.any { it.width == size.width && it.height == size.height }
            val minDurationNs = try {
                map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
            } catch (e: Exception) {
                0L
            }
            val sizeMaxFps = if (minDurationNs > 0) (1_000_000_000.0 / minDurationNs).toInt() else 0
            verified = sizeOk && (sizeMaxFps >= 59 || profileFrameRate(cameraId, size) >= 59)
            Log.d(TAG, "4K sizeOk=$sizeOk sizeMaxFps=$sizeMaxFps verified=$verified")
        }
        val hevc = chooseHevc(size.width, size.height, 60)
        return VideoConfig(
            id = "4k60",
            resolution = size,
            fps = 60,
            bitrate = bitrateFor(size.width, size.height, 60, hevc),
            label = if (verified) "4K • 60fps" else "4K • 60fps (تجريبي)",
            useHevc = hevc,
            isHighSpeed = false,
            withAudio = true
        )
    }

    /**
     * Slow-motion: prefers 1920x1080 @ 240fps. If the phone does not offer that through the
     * high-speed API, falls back to the best combination it does offer (the real numbers are shown in the UI).
     */
    private fun buildSlowMo(map: StreamConfigurationMap): VideoConfig? {
        val sizes = try {
            map.highSpeedVideoSizes
        } catch (e: Exception) {
            null
        } ?: return null
        if (sizes.isEmpty()) return null

        val candidates = ArrayList<Pair<Size, Range<Int>>>()
        for (s in sizes) {
            val ranges = try {
                map.getHighSpeedVideoFpsRangesFor(s)
            } catch (e: Exception) {
                null
            } ?: continue
            val maxUpper = ranges.maxOfOrNull { it.upper } ?: continue
            val best = ranges.filter { it.upper == maxUpper }.maxByOrNull { it.lower } ?: continue
            candidates.add(s to best)
        }
        if (candidates.isEmpty()) return null

        val chosen =
            candidates.firstOrNull { it.first.width == 1920 && it.first.height == 1080 && it.second.upper >= 240 }
                ?: candidates.filter { it.second.upper >= 240 }
                    .maxByOrNull { it.first.width * it.first.height }
                ?: candidates.firstOrNull { it.first.width == 1920 && it.first.height == 1080 }
                ?: candidates.maxByOrNull { it.second.upper * 100_000_000L + it.first.width * it.first.height }
                ?: return null

        val size = chosen.first
        val range = chosen.second
        val hevc = chooseHevc(size.width, size.height, range.upper)
        Log.d(TAG, "SlowMo chosen: ${size.width}x${size.height} range=$range hevc=$hevc")
        return VideoConfig(
            id = "slowmo",
            resolution = size,
            fps = range.upper,
            fpsMin = range.lower,
            bitrate = bitrateFor(size.width, size.height, range.upper, hevc),
            label = "Slow-Mo ${size.height}p • ${range.upper}fps",
            useHevc = hevc,
            isHighSpeed = true,
            // Audio is not recorded in slow-motion (high-speed sessions often fail with a mic attached).
            withAudio = false
        )
    }

    private fun profileFrameRate(cameraId: String, size: Size): Int {
        return try {
            val id = cameraId.toInt()
            val quality = when {
                size.width >= 3840 -> CamcorderProfile.QUALITY_2160P
                size.width >= 1920 -> CamcorderProfile.QUALITY_1080P
                else -> CamcorderProfile.QUALITY_720P
            }
            if (CamcorderProfile.hasProfile(id, quality)) {
                CamcorderProfile.get(id, quality)?.videoFrameRate ?: 0
            } else 0
        } catch (e: Exception) {
            0
        }
    }

    private fun encoderSupports(mime: String, width: Int, height: Int, fps: Int): Boolean {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                        try {
                            info.getCapabilitiesForType(mime).videoCapabilities
                                .areSizeAndRateSupported(width, height, fps.toDouble())
                        } catch (e: Exception) {
                            false
                        }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** HEVC when the encoder can do it, otherwise H.264, otherwise HEVC anyway (let the recorder decide). */
    private fun chooseHevc(width: Int, height: Int, fps: Int): Boolean {
        if (encoderSupports("video/hevc", width, height, fps)) return true
        if (encoderSupports("video/avc", width, height, fps)) return false
        return true
    }

    private fun bitrateFor(width: Int, height: Int, fps: Int, hevc: Boolean): Int {
        val bitsPerPixel = if (hevc) 0.08 else 0.12
        val raw = (width.toDouble() * height * fps * bitsPerPixel).toLong()
        return raw.coerceIn(8_000_000L, 80_000_000L).toInt()
    }

    // ---------------------------------------------------------------------------------------------
    // Camera + session
    // ---------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    suspend fun openCamera(cameraId: String): CameraDevice = suspendCancellableCoroutine { cont ->
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    if (cont.isActive) cont.resume(camera) else camera.close()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    if (cont.isActive) {
                        cont.resumeWithException(RuntimeException("الكاميرا اتفصلت"))
                    } else {
                        mainHandler.post { onCameraLost?.invoke() }
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    if (cont.isActive) {
                        cont.resumeWithException(RuntimeException("فشل فتح الكاميرا (كود $error)"))
                    } else {
                        mainHandler.post { onCameraLost?.invoke() }
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    /**
     * Starts the capture session. Normal session for 4K60, constrained high-speed session for slow-mo.
     * [onReady] / [onError] are always delivered on the main thread.
     */
    fun startRecordingCaptureSession(
        cameraId: String,
        config: VideoConfig,
        recorderSurface: Surface,
        previewSurface: Surface?,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        val device = cameraDevice
        if (device == null) {
            mainHandler.post { onError("الكاميرا مش مفتوحة") }
            return
        }

        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null

        val surfaces = ArrayList<Surface>()
        surfaces.add(recorderSurface)
        if (previewSurface != null && previewSurface.isValid) surfaces.add(previewSurface)

        try {
            if (config.isHighSpeed) {
                startHighSpeedSession(device, config, surfaces, onReady, onError)
            } else {
                startNormalSession(device, cameraId, config, surfaces, onReady, onError)
            }
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed", e)
            mainHandler.post { onError(e.message ?: "فشل إنشاء جلسة الكاميرا") }
        }
    }

    @Suppress("DEPRECATION")
    private fun startNormalSession(
        device: CameraDevice,
        cameraId: String,
        config: VideoConfig,
        surfaces: List<Surface>,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    for (s in surfaces) builder.addTarget(s)

                    builder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                    )

                    val aeRanges = characteristics
                        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        ?: emptyArray()
                    val chosenRange = aeRanges.firstOrNull { it.lower == config.fps && it.upper == config.fps }
                        ?: aeRanges.filter { it.upper == config.fps }.maxByOrNull { it.lower }
                        ?: Range(config.fps, config.fps)
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosenRange)

                    val stabilization = characteristics
                        .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    if (stabilization?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                        builder.set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        )
                    }

                    session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    Log.i(TAG, "Normal session ready, fps range=$chosenRange")
                    mainHandler.post { onReady() }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start repeating request", e)
                    mainHandler.post { onError(e.message ?: "فشل بدء الكاميرا") }
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Normal session configure failed")
                mainHandler.post { onError("الجهاز مش بيدعم الإعداد ده (${config.label})") }
            }
        }, backgroundHandler)
    }

    @Suppress("DEPRECATION")
    private fun startHighSpeedSession(
        device: CameraDevice,
        config: VideoConfig,
        surfaces: List<Surface>,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        device.createConstrainedHighSpeedCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val highSpeed = session as CameraConstrainedHighSpeedCaptureSession
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        for (s in surfaces) builder.addTarget(s)
                        builder.set(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            Range(config.fpsMin, config.fps)
                        )
                        builder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
                        val requests = highSpeed.createHighSpeedRequestList(builder.build())
                        highSpeed.setRepeatingBurst(requests, null, backgroundHandler)
                        Log.i(TAG, "High-speed session ready: ${config.fpsMin}-${config.fps}fps")
                        mainHandler.post { onReady() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start high-speed burst", e)
                        mainHandler.post { onError(e.message ?: "فشل بدء السلو موشن") }
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "High-speed session configure failed")
                    mainHandler.post { onError("الجهاز مش بيدعم السلو موشن (${config.label})") }
                }
            },
            backgroundHandler
        )
    }

    fun stopCaptureSession() {
        try {
            captureSession?.stopRepeating()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping repeating request", e)
        }
        try {
            captureSession?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing capture session", e)
        }
        captureSession = null
    }

    fun getSensorOrientation(cameraId: String): Int {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    }

    fun getBackCameraId(): String {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull() ?: "0"
    }

    fun close() {
        onCameraLost = null
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null
        try {
            backgroundThread?.quitSafely()
        } catch (_: Exception) {
        }
        backgroundThread = null
        backgroundHandler = null
    }

    companion object {
        const val TAG = "Camera2Manager"
    }
}
