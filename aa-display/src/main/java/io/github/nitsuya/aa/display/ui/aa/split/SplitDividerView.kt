package io.github.nitsuya.aa.display.ui.aa.split

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Thin split divider inspired by OneUI look (not StageCoordinator).
 *
 * Split mode: tap swaps panes; long-press opens recent-task stack; drag adjusts ratio.
 * Drag past [SplitPane.FULLSCREEN_ENTER_RATIO] edges settles into fullscreen.
 *
 * Fullscreen peel mode: docked on the opposite edge (right for PRIMARY FS; left inset
 * past Coolwalk rail for SECONDARY FS). Drag inward to exit; tap toggles fullscreen pane.
 *
 * Hit target is wider than the visual seam and overlaps adjacent panes via negative
 * margins + elevation. Ends of the strip ([SplitPane.DIVIDER_TOUCH_END_INSET_DP])
 * do not consume touches so pane-corner chrome stays tappable.
 */
class SplitDividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var sideBySide: Boolean = true

    /**
     * [SplitPane.FULLSCREEN_NONE] = normal split divider;
     * PRIMARY/SECONDARY = peel handle for that fullscreen pane.
     */
    var fullscreenPane: Int = SplitPane.FULLSCREEN_NONE
        private set

    var onRatioChanged: ((Float) -> Unit)? = null
    var onRatioSettled: ((Float) -> Unit)? = null
    var onFullscreenEnter: ((Int) -> Unit)? = null
    var onFullscreenExit: (() -> Unit)? = null
    /** Peel drag released without crossing exit threshold — snap UI back to fullscreen. */
    var onPeelCancelled: (() -> Unit)? = null
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
    /** Unclamped ratio used for fullscreen enter/exit decisions. */
    private var lastRawRatio = SplitPane.DEFAULT_RATIO

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

    /**
     * Sync display ratio when not mid-gesture. Must not run during drag — it would
     * clamp [lastRawRatio] back into 0.2..0.8 and block fullscreen-enter settle.
     */
    fun setRatio(ratio: Float) {
        if (tracking || dragging) return
        lastRatio = SplitPane.clampRatio(ratio)
        lastRawRatio = lastRatio
    }

    fun setFullscreenPane(pane: Int) {
        if (fullscreenPane == pane) return
        fullscreenPane = pane
        invalidate()
    }

    val isPeelMode: Boolean
        get() = SplitPane.isFullscreenPane(fullscreenPane)

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
     * Peel handle in a [FrameLayout] host: PRIMARY FS → right edge; SECONDARY FS →
     * left edge inset by [SplitPane.FULLSCREEN_PEEL_INSET_DP] (past Coolwalk rail).
     */
    fun applyPeelLayoutParams(lp: FrameLayout.LayoutParams, sideBySide: Boolean, fullscreenPane: Int) {
        val seamPx = (SplitPane.DIVIDER_DP * density).toInt().coerceAtLeast(1)
        val expandPx = (SplitPane.DIVIDER_TOUCH_EXPAND_DP * density).toInt().coerceAtLeast(0)
        val touchSpan = seamPx + 2 * expandPx
        val insetPx = (SplitPane.FULLSCREEN_PEEL_INSET_DP * density).toInt().coerceAtLeast(0)
        lp.width = if (sideBySide) touchSpan else ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = if (sideBySide) ViewGroup.LayoutParams.MATCH_PARENT else touchSpan
        lp.marginStart = 0
        lp.marginEnd = 0
        lp.topMargin = 0
        lp.bottomMargin = 0
        when {
            fullscreenPane == SplitPane.PRIMARY && sideBySide -> {
                lp.gravity = Gravity.END or Gravity.TOP
            }
            fullscreenPane == SplitPane.SECONDARY && sideBySide -> {
                lp.gravity = Gravity.START or Gravity.TOP
                lp.marginStart = insetPx
            }
            fullscreenPane == SplitPane.PRIMARY && !sideBySide -> {
                lp.gravity = Gravity.BOTTOM or Gravity.START
            }
            fullscreenPane == SplitPane.SECONDARY && !sideBySide -> {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.topMargin = insetPx
            }
            else -> {
                lp.gravity = Gravity.CENTER
            }
        }
    }

    /**
     * Touches on the long-axis ends fall through to the panes. If the divider is
     * shorter than two insets, keep the full span so a tiny display still works.
     * Peel mode keeps the full strip tappable (short docked handle).
     */
    private fun isOnEndInset(event: MotionEvent): Boolean {
        if (isPeelMode) return false
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

    /**
     * Finger position in parent space via raw screen coords — independent of the
     * divider's moving [left]/[top] while layout updates under the finger.
     */
    private fun rawRatioFromEvent(event: MotionEvent, parentView: View): Float {
        val loc = IntArray(2)
        parentView.getLocationOnScreen(loc)
        val ratio = if (sideBySide) {
            val xInParent = event.rawX - loc[0]
            xInParent / parentView.width.toFloat().coerceAtLeast(1f)
        } else {
            val yInParent = event.rawY - loc[1]
            yInParent / parentView.height.toFloat().coerceAtLeast(1f)
        }
        return ratio.coerceIn(0f, 1f)
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
                val raw = rawRatioFromEvent(event, parentView)
                lastRawRatio = raw
                if (isPeelMode) {
                    // Preview split weights while peeling; do not clamp to 0.2..0.8 yet.
                    if (abs(raw - lastRatio) > 0.002f) {
                        lastRatio = raw
                        onRatioChanged?.invoke(raw)
                    }
                } else {
                    // Split drag: allow visual preview past clamp; settle decides FS vs clamp.
                    if (abs(raw - lastRatio) > 0.002f) {
                        lastRatio = raw
                        onRatioChanged?.invoke(raw)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    val wasDragging = dragging
                    val wasLongPress = longPressFired
                    // Final sample before clearing tracking (setRatio may run from layout).
                    if (wasDragging && !wasLongPress) {
                        lastRawRatio = rawRatioFromEvent(event, parentView)
                        lastRatio = lastRawRatio
                    }
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
                        settleDrag()
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun settleDrag() {
        if (isPeelMode) {
            val exit = when (fullscreenPane) {
                // Right peel (PRIMARY FS): dragging left lowers ratio; exit when enough secondary shows.
                SplitPane.PRIMARY -> lastRawRatio <= 1f - SplitPane.FULLSCREEN_EXIT_RATIO
                // Left peel (SECONDARY FS): dragging right raises ratio; exit when enough primary shows.
                SplitPane.SECONDARY -> lastRawRatio >= SplitPane.FULLSCREEN_EXIT_RATIO
                else -> false
            }
            if (exit) {
                onFullscreenExit?.invoke()
            } else {
                onPeelCancelled?.invoke()
            }
            return
        }
        val fsPane = SplitPane.fullscreenPaneForRawRatio(lastRawRatio)
        if (fsPane != null) {
            performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            onFullscreenEnter?.invoke(fsPane)
            return
        }
        val clamped = SplitPane.clampRatio(lastRawRatio)
        lastRatio = clamped
        lastRawRatio = clamped
        onRatioSettled?.invoke(clamped)
    }
}
