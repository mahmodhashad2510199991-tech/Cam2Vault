package com.example.cam2vault.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.example.cam2vault.data.VideoConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Manages Camera2 hardware queries, StreamConfigurationMap checks,
 * and handles persistent capture sessions for background recording.
 */
class Camera2Manager(private val context: Context) {

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera2BackgroundThread").apply {
            start()
            backgroundHandler = Handler(looper)
        }
    }

    /**
     * Inspects StreamConfigurationMap on the target camera.
     * Evaluates output sizes for MediaRecorder and available AE FPS ranges
     * to strictly return ONLY valid hardware configurations.
     */
    fun querySupportedVideoConfigurations(cameraId: String): List<VideoConfig> {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map: StreamConfigurationMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return emptyList()

        // 1. Get all sizes officially supported for MediaRecorder surface
        val mediaRecorderSizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()

        // 2. Query available AE FPS ranges (e.g. [30, 30], [60, 60], [15, 60])
        val fpsRanges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ) ?: emptyArray()

        val maxAvailableFps = fpsRanges.maxOfOrNull { it.upper } ?: 30
        val supports60FpsGlobally = fpsRanges.any { it.upper >= 60 }

        // Also check High Speed video capabilities if supported by the sensor
        val highSpeedSizes = map.highSpeedVideoSizes ?: emptyArray()
        val highSpeedFpsRanges = map.highSpeedVideoFpsRanges ?: emptyArray()
        val supportsHighSpeed60 = highSpeedFpsRanges.any { it.upper >= 60 }

        Log.d(TAG, "Available MediaRecorder sizes: ${mediaRecorderSizes.map { "${it.width}x${it.height}" }}")
        Log.d(TAG, "Max FPS reported: $maxAvailableFps, Supports 60FPS: $supports60FpsGlobally")

        val supportedConfigs = mutableListOf<VideoConfig>()

        // Desired standard video resolutions to test against hardware map
        val candidateResolutions = listOf(
            Size(3840, 2160) to "4K UHD (3840x2160)",
            Size(1920, 1080) to "1080p FHD (1920x1080)",
            Size(1280, 720) to "720p HD (1280x720)"
        )

        for ((size, label) in candidateResolutions) {
            // Check if StreamConfigurationMap has this size for MediaRecorder
            val isSizeSupported = mediaRecorderSizes.any {
                it.width == size.width && it.height == size.height
            }

            if (isSizeSupported) {
                // Standard 30 FPS HEVC (always verified if size is supported)
                val bitrate30 = calculateHevcBitrate(size.width, size.height, 30)
                supportedConfigs.add(
                    VideoConfig(
                        id = "${size.width}x${size.height}_30",
                        resolution = size,
                        fps = 30,
                        bitrate = bitrate30,
                        label = label,
                        codec = "HEVC (H.265)",
                        isHighSpeed = false
                    )
                )

                // 60 FPS HEVC: verify if sensor supports 60fps for this size
                val is60SupportedForSize = if (size.width >= 3840) {
                    // For 4K at 60fps, ensure AE ranges allow [60, 60] or high-speed config lists 4K
                    fpsRanges.any { it.lower >= 30 && it.upper >= 60 } ||
                            highSpeedSizes.any { it.width == size.width && it.height == size.height }
                } else {
                    supports60FpsGlobally || supportsHighSpeed60
                }

                if (is60SupportedForSize) {
                    val bitrate60 = calculateHevcBitrate(size.width, size.height, 60)
                    supportedConfigs.add(
                        VideoConfig(
                            id = "${size.width}x${size.height}_60",
                            resolution = size,
                            fps = 60,
                            bitrate = bitrate60,
                            label = label,
                            codec = "HEVC (H.265)",
                            isHighSpeed = size.width >= 3840 && !fpsRanges.any { it.upper >= 60 }
                        )
                    )
                }
            }
        }

        return supportedConfigs
    }

    /**
     * Efficient HEVC H.265 bitrate targeting (approx 40-50% savings over H.264).
     */
    private fun calculateHevcBitrate(width: Int, height: Int, fps: Int): Int {
        return when {
            width >= 3840 -> if (fps >= 60) 48_000_000 else 32_000_000  // 48 Mbps vs 32 Mbps
            width >= 1920 -> if (fps >= 60) 20_000_000 else 12_000_000  // 20 Mbps vs 12 Mbps
            else -> if (fps >= 60) 10_000_000 else 6_000_000            // 10 Mbps vs 6 Mbps
        }
    }

    /**
     * Opens the camera asynchronously using suspendCoroutine.
     */
    @SuppressLint("MissingPermission")
    suspend fun openCamera(cameraId: String): CameraDevice = suspendCancellableCoroutine { cont ->
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                cont.resume(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                cont.resumeWithException(RuntimeException("Camera open error: $error"))
            }
        }, backgroundHandler)
    }

    /**
     * Configures a persistent CaptureSession containing the MediaRecorder surface
     * and optional preview surface. Crucially, even if preview surface is detached
     * (e.g. screen off / app minimized), the recording surface continues receiving frames!
     */
    fun startRecordingCaptureSession(
        cameraId: String,
        config: VideoConfig,
        recorderSurface: Surface,
        previewSurface: Surface?
    ) {
        val device = cameraDevice ?: run {
            Log.e(TAG, "Cannot start recording session: CameraDevice is null")
            return
        }

        // Close any previous session
        captureSession?.close()

        val surfaces = mutableListOf<Surface>(recorderSurface)
        if (previewSurface != null && previewSurface.isValid) {
            surfaces.add(previewSurface)
        }

        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                try {
                    val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(recorderSurface)
                        if (previewSurface != null && previewSurface.isValid) {
                            addTarget(previewSurface)
                        }

                        // Set continuous video autofocus
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)

                        // Enforce exact FPS target range (e.g. [60, 60] or [30, 30])
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(config.fps, config.fps))

                        // Enable optical video stabilization if hardware supports it
                        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                        val stabilizationModes = characteristics.get(
                            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
                        )
                        if (stabilizationModes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                        }
                    }

                    session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                    Log.i(TAG, "CaptureSession configured with target FPS: ${config.fps}")

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start repeating record request", e)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Failed to configure CameraCaptureSession for recording")
            }
        }, backgroundHandler)
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
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            backgroundThread?.quitSafely()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera manager", e)
        }
    }

    companion object {
        const val TAG = "Camera2Manager"
    }
}
