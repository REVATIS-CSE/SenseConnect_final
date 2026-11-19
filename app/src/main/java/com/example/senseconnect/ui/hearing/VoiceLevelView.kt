package com.example.senseconnect.ui.hearing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.example.senseconnect.R
import com.example.senseconnect.core.ui.Feedback
import kotlin.math.max
import kotlin.math.sin

/**
 * Five-bar microphone level meter driven by SpeechRecognizer RMS values. Gives deaf and
 * hard-of-hearing users visual confirmation that sound is being picked up.
 */
class VoiceLevelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.sc_secondary) }
    private val rect = RectF()
    private val barWidth = dp(4f)
    private val gap = dp(4f)
    private val weights = floatArrayOf(0.55f, 0.8f, 1f, 0.8f, 0.55f)

    var level: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var active: Boolean = false
        set(value) {
            field = value
            paint.color = context.getColor(if (value) R.color.sc_secondary else R.color.sc_outline)
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (barWidth * weights.size + gap * (weights.size - 1)).toInt()
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(dp(24f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val minBar = barWidth
        val animate = active && Feedback.motionEnabled(context)
        val phase = if (animate) (System.currentTimeMillis() % 1000) / 1000f * 6.28f else 0f
        weights.forEachIndexed { i, weight ->
            val wobble = if (animate) 0.15f * (1 + sin(phase + i)) else 0f
            val scale = if (active) (level * weight + wobble).coerceIn(0.15f, 1f) else 0.15f
            val barH = max(minBar, h * scale)
            val left = i * (barWidth + gap)
            rect.set(left, (h - barH) / 2f, left + barWidth, (h + barH) / 2f)
            canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint)
        }
        if (animate) postInvalidateDelayed(48)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
