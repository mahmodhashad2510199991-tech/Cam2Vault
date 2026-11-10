package com.example.cam2vault.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Floating camera preview drawn over other apps.
 *
 * - Drag with one finger to move it.
 * - Pinch with two fingers to make it bigger / smaller.
 * - Or drag the small handle in the bottom-right corner.
 * - The picture is rotated to match the video orientation (so it is always upright).
 */
class FloatingPreview(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var texture: TextureView? = null
    private var params: WindowManager.LayoutParams? = null

    private var rotation = 0
    private var longSidePx = 0f

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private val isPortraitContent: Boolean
        get() = rotation == 90 || rotation == 270

    private fun widthFor(longSide: Float): Int =
        if (isPortraitContent) (longSide * 9f / 16f).toInt() else longSide.toInt()

    private fun heightFor(longSide: Float): Int =
        if (isPortraitContent) longSide.toInt() else (longSide * 9f / 16f).toInt()

    private fun minLongSide(): Float = dp(110).toFloat()

    private fun maxLongSide(): Float {
        val dm = context.resources.displayMetrics
        return if (isPortraitContent) dm.heightPixels * 0.92f else dm.widthPixels * 0.98f
    }

    private fun setLongSide(value: Float) {
        longSidePx = value.coerceIn(minLongSide(), maxLongSide())
        val p = params ?: return
        val view = root ?: return
        p.width = widthFor(longSidePx)
        p.height = heightFor(longSidePx)
        try {
            windowManager.updateViewLayout(view, p)
        } catch (_: Exception) {
        }
        updateTransform(p.width, p.height)
    }

    /** Rotates the (stretched) camera buffer so the image is upright and not distorted. */
    private fun updateTransform(vw: Int, vh: Int) {
        val tv = texture ?: return
        if (vw <= 0 || vh <= 0) return
        val m = Matrix()
        val cx = vw / 2f
        val cy = vh / 2f
        when (rotation) {
            90, 270 -> {
                m.postScale(vh.toFloat() / vw, vw.toFloat() / vh, cx, cy)
                m.postRotate(rotation.toFloat(), cx, cy)
            }
            180 -> m.postRotate(180f, cx, cy)
        }
        tv.setTransform(m)
    }

    /**
     * @param bufferWidth / bufferHeight size of the camera buffer going into the preview surface
     * @param rotationDegrees video orientation hint (0, 90, 180, 270)
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        onSurfaceReady: (Surface?) -> Unit
    ) {
        hide()
        rotation = ((rotationDegrees % 360) + 360) % 360
        try {
            val tv = TextureView(context)
            texture = tv

            val container = FrameLayout(context)
            container.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.BLACK)
                setStroke(dp(2), Color.WHITE)
            }
            container.clipToOutline = true
            container.addView(
                tv,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            // Resize handle (bottom-right corner)
            val handle = View(context)
            handle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(170, 255, 255, 255))
            }
            val handleSize = dp(26)
            val handleLp = FrameLayout.LayoutParams(handleSize, handleSize)
            handleLp.gravity = Gravity.BOTTOM or Gravity.END
            handleLp.setMargins(0, 0, dp(4), dp(4))
            container.addView(handle, handleLp)

            longSidePx = dp(260).toFloat().coerceIn(minLongSide(), maxLongSide())

            val lp = WindowManager.LayoutParams(
                widthFor(longSidePx),
                heightFor(longSidePx),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = dp(12)
            lp.y = dp(120)

            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    st.setDefaultBufferSize(bufferWidth, bufferHeight)
                    updateTransform(width, height)
                    onSurfaceReady(Surface(st))
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                    updateTransform(width, height)
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }

            // Pinch to resize
            val scaleDetector = ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        setLongSide(longSidePx * detector.scaleFactor)
                        return true
                    }
                }
            )

            // Drag to move
            container.setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f
                private var ignoreUntilUp = false

                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    val p = params ?: return false
                    scaleDetector.onTouchEvent(e)

                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            ignoreUntilUp = false
                            startX = p.x
                            startY = p.y
                            touchX = e.rawX
                            touchY = e.rawY
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> ignoreUntilUp = true
                        MotionEvent.ACTION_MOVE -> {
                            if (!ignoreUntilUp && e.pointerCount == 1 && !scaleDetector.isInProgress) {
                                p.x = startX + (e.rawX - touchX).toInt()
                                p.y = startY + (e.rawY - touchY).toInt()
                                try {
                                    windowManager.updateViewLayout(v, p)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ignoreUntilUp = false
                    }
                    return true
                }
            })

            // Corner handle: drag to resize
            handle.setOnTouchListener(object : View.OnTouchListener {
                private var startLongSide = 0f
                private var downX = 0f
                private var downY = 0f

                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startLongSide = longSidePx
                            downX = e.rawX
                            downY = e.rawY
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val delta = ((e.rawX - downX) + (e.rawY - downY)) / 2f
                            if (abs(delta) > 1f) setLongSide(startLongSide + delta)
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
            texture = null
            params = null
            onSurfaceReady(null)
        }
    }

    fun hide() {
        root?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
        texture = null
        params = null
    }

    companion object {
        private const val TAG = "FloatingPreview"
    }
}
