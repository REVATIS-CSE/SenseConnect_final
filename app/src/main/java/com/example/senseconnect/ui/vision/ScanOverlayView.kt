package com.example.senseconnect.ui.vision

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.senseconnect.R
import com.example.senseconnect.core.ui.Feedback

/**
 * Camera guide drawn over the live preview: dims everything outside the scan frame, draws
 * corner brackets (white = searching, green = text detected) and an optional moving scan line
 * while OCR runs. The scan line is skipped when Reduce Motion is on.
 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.sc_scrim)
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.sc_scan_frame)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val frame = RectF()
    private val scrimPath = Path()
    private val cornerPath = Path()
    private val radius = dp(18f)
    private val cornerLength = resources.getDimension(R.dimen.scan_frame_corner)

    private var scanProgress = 0f
    private var animator: ValueAnimator? = null

    var textDetected: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            cornerPaint.color = ContextCompat.getColor(context, if (value) R.color.sc_scan_frame_active else R.color.sc_scan_frame)
            invalidate()
        }

    var scanning: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value && Feedback.motionEnabled(context)) startScanAnimation() else stopScanAnimation()
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val frameWidth = w * 0.86f
        val frameHeight = minOf(h * 0.70f, frameWidth * 1.15f)
        val left = (w - frameWidth) / 2f
        val top = (h - frameHeight) / 2f
        frame.set(left, top, left + frameWidth, top + frameHeight)

        scrimPath.reset()
        scrimPath.fillType = Path.FillType.EVEN_ODD
        scrimPath.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        scrimPath.addRoundRect(frame, radius, radius, Path.Direction.CW)

        buildCorners()
        linePaint.shader = LinearGradient(
            frame.left, 0f, frame.right, 0f,
            intArrayOf(Color.TRANSPARENT, ContextCompat.getColor(context, R.color.sc_scan_frame_active), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
    }

    private fun buildCorners() {
        val l = frame.left; val t = frame.top; val r = frame.right; val b = frame.bottom
        val c = cornerLength
        cornerPath.reset()
        // top-left
        cornerPath.moveTo(l, t + c); cornerPath.lineTo(l, t + radius / 2); cornerPath.quadTo(l, t, l + radius / 2, t); cornerPath.lineTo(l + c, t)
        // top-right
        cornerPath.moveTo(r - c, t); cornerPath.lineTo(r - radius / 2, t); cornerPath.quadTo(r, t, r, t + radius / 2); cornerPath.lineTo(r, t + c)
        // bottom-right
        cornerPath.moveTo(r, b - c); cornerPath.lineTo(r, b - radius / 2); cornerPath.quadTo(r, b, r - radius / 2, b); cornerPath.lineTo(r - c, b)
        // bottom-left
        cornerPath.moveTo(l + c, b); cornerPath.lineTo(l + radius / 2, b); cornerPath.quadTo(l, b, l, b - radius / 2); cornerPath.lineTo(l, b - c)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(scrimPath, scrimPaint)
        canvas.drawPath(cornerPath, cornerPaint)
        if (scanning && animator != null) {
            val y = frame.top + frame.height() * scanProgress
            canvas.drawRect(frame.left + dp(8f), y - dp(1.5f), frame.right - dp(8f), y + dp(1.5f), linePaint)
        }
    }

    private fun startScanAnimation() {
        stopScanAnimation()
        animator = ValueAnimator.ofFloat(0.05f, 0.95f).apply {
            duration = 1400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                scanProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopScanAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDetachedFromWindow() {
        stopScanAnimation()
        super.onDetachedFromWindow()
    }
}
