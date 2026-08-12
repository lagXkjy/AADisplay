package io.github.nitsuya.aa.display.ui.aa.split

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Thin split divider inspired by OneUI look (not StageCoordinator).
 * Tap swaps panes; long-press opens recent-task stack; drag adjusts ratio.
 */
class SplitDividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var sideBySide: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            scheduleTouchDelegateUpdate()
        }

    var onRatioChanged: ((Float) -> Unit)? = null
    var onRatioSettled: ((Float) -> Unit)? = null
    var onStackClick: (() -> Unit)? = null
    var onSwapClick: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchExpandPx = SplitPane.DIVIDER_TOUCH_EXPAND_DP * density
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
        onStackClick?.invoke()
    }

    private val updateTouchDelegateRunnable = Runnable { updateTouchDelegate() }

    init {
        isClickable = true
        setBackgroundColor(0x00000000)
    }

    fun setRatio(ratio: Float) {
        lastRatio = SplitPane.clampRatio(ratio)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            scheduleTouchDelegateUpdate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleTouchDelegateUpdate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        removeCallbacks(updateTouchDelegateRunnable)
        super.onDetachedFromWindow()
    }

    private fun scheduleTouchDelegateUpdate() {
        if (!isAttachedToWindow) return
        removeCallbacks(updateTouchDelegateRunnable)
        post(updateTouchDelegateRunnable)
    }

    private fun updateTouchDelegate() {
        val parentView = parent as? View ?: return
        if (width == 0 || height == 0) return
        val hitRect = Rect()
        getHitRect(hitRect)
        if (sideBySide) {
            hitRect.left -= touchExpandPx.toInt()
            hitRect.right += touchExpandPx.toInt()
        } else {
            hitRect.top -= touchExpandPx.toInt()
            hitRect.bottom += touchExpandPx.toInt()
        }
        parentView.touchDelegate = TouchDelegate(hitRect, this)
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
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        onSwapClick?.invoke()
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
}
