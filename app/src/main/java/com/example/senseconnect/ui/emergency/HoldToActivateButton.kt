package com.example.senseconnect.ui.emergency

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.Button
import com.example.senseconnect.R
import com.example.senseconnect.core.ui.Feedback

/**
 * Large circular SOS control that must be held for [holdDurationMs] to trigger, preventing
 * accidental activation. A progress ring fills while held, with a haptic tick each second.
 *
 * Accessibility: TalkBack/switch users activate via a normal click, which the screen turns into
 * an explicit confirmation dialog instead of a timed hold.
 */
class HoldToActivateButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onHoldStarted()
        fun onHoldCancelled()
        fun onHoldCompleted()
        fun onAccessibleActivate()
    }

    var listener: Listener? = null
    var holdDurationMs = 3_000L

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.sc_emergency_container) }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.sc_emergency_deep) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(8f); color = 0x40FFFFFF
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(8f); strokeCap = Paint.Cap.ROUND; color = 0xFFFFFFFF.toInt()
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL); textSize = sp(44f)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt(); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = sp(14f)
    }
    private val arcRect = RectF()

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var lastTickSecond = -1

    var subtitle: String = context.getString(R.string.sos_hold_short)
        set(value) { field = value; invalidate() }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.cd_sos_button)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resources.getDimensionPixelSize(R.dimen.sos_button_size)
        val w = resolveSize(size, widthMeasureSpec)
        val h = resolveSize(size, heightMeasureSpec)
        val s = minOf(w, h)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(cx, cy)
        val pressScale = if (animator != null) 0.97f else 1f
        canvas.drawCircle(cx, cy, outer * pressScale, haloPaint)
        val inner = outer * 0.82f * pressScale
        canvas.drawCircle(cx, cy, inner, circlePaint)

        val ringRadius = inner - dp(14f)
        arcRect.set(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        canvas.drawOval(arcRect, trackPaint)
        if (progress > 0f) canvas.drawArc(arcRect, -90f, 360f * progress, false, progressPaint)

        canvas.drawText("SOS", cx, cy + titlePaint.textSize * 0.3f, titlePaint)
        canvas.drawText(subtitle, cx, cy + titlePaint.textSize * 0.3f + subtitlePaint.textSize * 1.6f, subtitlePaint)
    }

    @SuppressLint("ClickableViewAccessibility") // performClick is routed to the accessible confirm path
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startHold()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (animator != null) cancelHold()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onAccessibleActivate()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }

    private fun startHold() {
        animator?.cancel()
        lastTickSecond = -1
        Feedback.longPress(this)
        listener?.onHoldStarted()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = holdDurationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                val second = (progress * holdDurationMs / 1000).toInt()
                if (second != lastTickSecond) {
                    lastTickSecond = second
                    if (second > 0) Feedback.tick(this@HoldToActivateButton)
                }
                invalidate()
                if (progress >= 1f) complete()
            }
            start()
        }
        invalidate()
    }

    private fun complete() {
        animator?.removeAllUpdateListeners()
        animator = null
        progress = 0f
        Feedback.confirm(this)
        invalidate()
        listener?.onHoldCompleted()
    }

    private fun cancelHold() {
        animator?.cancel()
        animator = null
        progress = 0f
        invalidate()
        listener?.onHoldCancelled()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
