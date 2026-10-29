package com.example.cam2vault.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Bridge between volume-button events (Activity key events or MediaSession when the
 * screen is off) and the recording toggle registered by the camera screen.
 */
object VolumeKeyController {
    @Volatile
    var onVolumeKey: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTrigger = 0L

    fun trigger() {
        val handler = onVolumeKey ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrigger < 1000) return
        lastTrigger = now
        mainHandler.post { handler() }
    }
}
