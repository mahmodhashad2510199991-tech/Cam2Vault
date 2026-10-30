package com.example.cam2vault.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Small draggable camera preview window drawn over other apps.
 * Tap it to toggle between small and larger size.
 */
class FloatingPreview(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var large = false

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show(onSurfaceReady: (Surface?) -> Unit) {
        hide()
        try {
            val texture = TextureView(context)
            val container = FrameLayout(context)
            container.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.BLACK)
                setStroke(dp(2), Color.WHITE)
            }
            container.clipToOutline = true
            container.addView(
                texture,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            val lp = WindowManager.LayoutParams(
                dp(SMALL_W),
                dp(SMALL_H),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = dp(12)
            lp.y = dp(120)

            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    st.setDefaultBufferSize(1280, 720)
                    onSurfaceReady(Surface(st))
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }

            container.setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var moved = false

                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    val p = params ?: return false
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = p.x
                            startY = p.y
                            touchX = e.rawX
                            touchY = e.rawY
                            moved = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (e.rawX - touchX).toInt()
                            val dy = (e.rawY - touchY).toInt()
                            if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                            if (moved) {
                                p.x = startX + dx
                                p.y = startY + dy
                                try { windowManager.updateViewLayout(v, p) } catch (_: Exception) {}
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!moved) {
                                large = !large
                                p.width = dp(if (large) LARGE_W else SMALL_W)
                                p.height = dp(if (large) LARGE_H else SMALL_H)
                                try { windowManager.updateViewLayout(v, p) } catch (_: Exception) {}
                            }
                        }
                    }
                    return true
                }
            })

            windowManager.addView(container, lp)
            root = container
            params = lp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating preview: ${e.message}", e)
            root = null
            params = null
            onSurfaceReady(null)
        }
    }

    fun hide() {
        root?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        root = null
        params = null
    }

    companion object {
        private const val TAG = "FloatingPreview"
        private const val SMALL_W = 110
        private const val SMALL_H = 196
        private const val LARGE_W = 180
        private const val LARGE_H = 320
    }
}
