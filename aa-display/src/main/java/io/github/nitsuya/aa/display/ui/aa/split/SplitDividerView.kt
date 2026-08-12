package io.github.nitsuya.aa.display.ui.aa.split

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Thin split divider inspired by OneUI look (not StageCoordinator).
 * Tap opens recent-task stack; long-press swaps panes; drag adjusts ratio.
 */
class SplitDividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var sideBySide: Boolean = true
    var onRatioChanged: ((Float) -> Unit)? = null
    var onRatioSettled: ((Float) -> Unit)? = null
    var onStackClick: (() -> Unit)? = null
    var onSwapClick: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8E8.toInt()
        style = Paint.Style.FILL
    }
    private val dotRadius = 2.5f * density
    private val dotGap = 7f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var tracking = false
    private var dragging = false
    private var longPressFired = false
    private var downX = 0f
    private var downY = 0f
    private var lastRatio = SplitPane.DEFAULT_RATIO

    private val longPressRunnable = Runnable {
        if (!tracking || dragging || longPressFired) return@Runnable
        longPressFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onSwapClick?.invoke()
    }

    init {
        contentDescription = "点击打开最近任务，长按交换分屏"
        isClickable = true
        setBackgroundColor(0x00000000)
    }

    fun setRatio(ratio: Float) {
        lastRatio = SplitPane.clampRatio(ratio)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (sideBySide) {
            canvas.drawLine(cx, 0f, cx, height.toFloat(), seamPaint)
            drawDots(canvas, cx, cy, vertical = true)
        } else {
            canvas.drawLine(0f, cy, width.toFloat(), cy, seamPaint)
            drawDots(canvas, cx, cy, vertical = false)
        }
    }

    private fun drawDots(canvas: Canvas, cx: Float, cy: Float, vertical: Boolean) {
        val offsets = floatArrayOf(-dotGap, 0f, dotGap)
        for (offset in offsets) {
            if (vertical) {
                canvas.drawCircle(cx, cy + offset, dotRadius, dotPaint)
            } else {
                canvas.drawCircle(cx + offset, cy, dotRadius, dotPaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val parentView = parent as? View ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                dragging = false
                longPressFired = false
                downX = event.x
                downY = event.y
                parent.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dist = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()).toFloat()
                if (!dragging && dist > touchSlop) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                }
                if (!dragging || longPressFired) return true
                val ratio = if (sideBySide) {
                    val xInParent = left + event.x
                    xInParent / parentView.width.toFloat()
                } else {
                    val yInParent = top + event.y
                    yInParent / parentView.height.toFloat()
                }
                val clamped = SplitPane.clampRatio(ratio)
                if (abs(clamped - lastRatio) > 0.002f) {
                    lastRatio = clamped
                    onRatioChanged?.invoke(clamped)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    val wasDragging = dragging
                    val wasLongPress = longPressFired
                    removeCallbacks(longPressRunnable)
                    tracking = false
                    dragging = false
                    longPressFired = false
                    parent.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !wasDragging && !wasLongPress) {
                        onStackClick?.invoke()
                        performClick()
                    } else if (wasDragging && !wasLongPress) {
                        onRatioSettled?.invoke(lastRatio)
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }
}
