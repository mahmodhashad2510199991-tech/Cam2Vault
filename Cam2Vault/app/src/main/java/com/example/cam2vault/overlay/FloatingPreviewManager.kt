package com.example.cam2vault.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import kotlin.math.abs

/**
 * Manages the floating 16:9 mini camera preview overlay that displays
 * over other applications (WhatsApp, YouTube, Home Screen, etc.)
 */
class FloatingPreviewManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var textureView: TextureView? = null
    private var currentSurface: Surface? = null
    private var isAttached = false

    var onRecordClick: (() -> Unit)? = null
    var onSurfaceReady: ((Surface) -> Unit)? = null
    var onSurfaceDestroyed: (() -> Unit)? = null

    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun requestOverlayPermissionIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent()
        }
    }

    /**
     * Shows a compact 16:9 floating window over all apps.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showFloatingPreview(isLandscape: Boolean = true) {
        if (isAttached || !canDrawOverlays()) return

        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics).toInt()
        }

        // Exact 16:9 aspect ratio dimensions
        val widthPx = if (isLandscape) dpToPx(192f) else dpToPx(112f)
        val heightPx = if (isLandscape) dpToPx(108f) else dpToPx(200f)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(20f)
            y = dpToPx(120f)
        }

        val card = CardView(context).apply {
            radius = dpToPx(14f).toFloat()
            cardElevation = dpToPx(8f).toFloat()
            setCardBackgroundColor(Color.BLACK)
        }

        val rootFrame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // 16:9 TextureView
        textureView = TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    val surface = Surface(st)
                    currentSurface = surface
                    onSurfaceReady?.invoke(surface)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    onSurfaceDestroyed?.invoke()
                    currentSurface?.release()
                    currentSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        rootFrame.addView(textureView)

        // Top mini HUD on floating preview (4K 60FPS badge + REC indicator)
        val hudLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(6f), dpToPx(4f), dpToPx(6f), dpToPx(4f))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val recDot = TextView(context).apply {
            text = "●"
            setTextColor(Color.RED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, dpToPx(4f), 0)
        }
        val tagText = TextView(context).apply {
            text = "4K 60FPS 16:9"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        }
        hudLayout.addView(recDot)
        hudLayout.addView(tagText)
        rootFrame.addView(hudLayout)

        card.addView(rootFrame)
        overlayView = card

        // Draggable gesture handling
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        card.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = abs(event.rawX - initialTouchX)
                    val deltaY = abs(event.rawY - initialTouchY)
                    if (deltaX < 10 && deltaY < 10) {
                        onRecordClick?.invoke()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(overlayView, params)
            isAttached = true
            Log.i("FloatingPreviewManager", "Floating 16:9 4K preview overlay attached successfully")
        } catch (e: Exception) {
            Log.e("FloatingPreviewManager", "Failed to add floating preview overlay", e)
        }
    }

    fun hideFloatingPreview() {
        if (!isAttached || overlayView == null) return
        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            Log.e("FloatingPreviewManager", "Error removing overlay", e)
        } finally {
            isAttached = false
            overlayView = null
            textureView = null
            currentSurface?.release()
            currentSurface = null
        }
    }
}
