package com.example.cam2vault.data

import android.net.Uri
import android.util.Size

/**
 * Represents a hardware-validated video recording configuration.
 */
data class VideoConfig(
    val id: String,
    val resolution: Size,
    val fps: Int,
    val bitrate: Int,
    val label: String,
    val codec: String = "HEVC (H.265)",
    val isHighSpeed: Boolean = false
) {
    val resolutionString: String
        get() = "${resolution.width}x${resolution.height}"

    val formattedBitrate: String
        get() = "${bitrate / 1_000_000} Mbps"

    val is4K: Boolean
        get() = resolution.width >= 3840
}

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
