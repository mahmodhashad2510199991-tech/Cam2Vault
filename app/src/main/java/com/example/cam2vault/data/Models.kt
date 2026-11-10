package com.example.cam2vault.data

import android.net.Uri
import android.util.Size

/**
 * Hardware-validated recording configuration.
 *
 * [fpsMin]..[fps] is the exact AE target range that gets sent to the camera
 * (for constrained high-speed sessions it must be one of the ranges the camera reports).
 */
data class VideoConfig(
    val id: String,
    val resolution: Size,
    val fps: Int,
    val bitrate: Int,
    val label: String,
    val useHevc: Boolean = true,
    val isHighSpeed: Boolean = false,
    val fpsMin: Int = fps,
    val withAudio: Boolean = true
) {
    val resolutionString: String
        get() = "${resolution.width}x${resolution.height}"

    val formattedBitrate: String
        get() = "${bitrate / 1_000_000} Mbps"

    val codecName: String
        get() = if (useHevc) "HEVC" else "H.264"

    val is4K: Boolean
        get() = resolution.width >= 3840
}

/**
 * The only two modes the app offers.
 * [slowMo] is null when the phone does not expose high-speed recording to apps.
 */
data class RecordingModes(
    val uhd60: VideoConfig,
    val slowMo: VideoConfig?
)

/**
 * Video item query result from MediaStore.Video.Media
 */
data class MediaStoreVideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedMs: Long,
    val resolution: String
)
