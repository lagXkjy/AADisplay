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
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Thin split divider inspired by OneUI look (not StageCoordinator).
 * Tap swaps panes; long-press opens recent-task stack; drag adjusts ratio.
 *
 * Hit target is wider than the visual seam and overlaps adjacent panes via negative
 * margins + elevation. Ends of the strip ([SplitPane.DIVIDER_TOUCH_END_INSET_DP])
 * do not consume touches so pane-corner chrome stays tappable. Parent [TouchDelegate]
 * cannot steal touches already consumed by sibling TextureViews (which long-press
 * open the app picker).
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
        onStackClick?.invoke()
    }

    init {
        isClickable = true
        // Above empty-pane overlays (elevation 4) so overlap hit target wins Z-order.
        elevation = 8f
        setBackgroundColor(0x00000000)
    }

    fun setRatio(ratio: Float) {
        lastRatio = SplitPane.clampRatio(ratio)
    }

    /**
     * Layout size = visual seam + expand on each side; negative margins keep the
     * LinearLayout gap at [SplitPane.DIVIDER_DP] while the view overlaps panes for touch.
     */
    fun applyLayoutParams(lp: LinearLayout.LayoutParams, sideBySide: Boolean) {
        val seamPx = (SplitPane.DIVIDER_DP * density).toInt().coerceAtLeast(1)
        val expandPx = (SplitPane.DIVIDER_TOUCH_EXPAND_DP * density).toInt().coerceAtLeast(0)
        val touchSpan = seamPx + 2 * expandPx
        if (sideBySide) {
            lp.width = touchSpan
            lp.height = LinearLayout.LayoutParams.MATCH_PARENT
            lp.marginStart = -expandPx
            lp.marginEnd = -expandPx
            lp.topMargin = 0
            lp.bottomMargin = 0
        } else {
            lp.width = LinearLayout.LayoutParams.MATCH_PARENT
            lp.height = touchSpan
            lp.topMargin = -expandPx
            lp.bottomMargin = -expandPx
            lp.marginStart = 0
            lp.marginEnd = 0
        }
    }

    /**
     * Touches on the long-axis ends fall through to the panes. If the divider is
     * shorter than two insets, keep the full span so a tiny display still works.
     */
    private fun isOnEndInset(event: MotionEvent): Boolean {
        val inset = SplitPane.DIVIDER_TOUCH_END_INSET_DP * density
        return if (sideBySide) {
            val usable = height - 2f * inset
            usable > 0f && (event.y < inset || event.y > height - inset)
        } else {
            val usable = width - 2f * inset
            usable > 0f && (event.x < inset || event.x > width - inset)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
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
                if (isOnEndInset(event)) return false
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
