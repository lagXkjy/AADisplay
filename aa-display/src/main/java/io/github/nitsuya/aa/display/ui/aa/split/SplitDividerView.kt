package io.github.nitsuya.aa.display.ui.aa.split

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Thin split divider inspired by OneUI look (not StageCoordinator).
 *
 * Split mode: tap swaps panes; long-press opens recent-task stack; drag adjusts ratio.
 * Drag past [SplitPane.FULLSCREEN_ENTER_RATIO] edges settles into fullscreen.
 *
 * Fullscreen peel mode: always docked on a fixed driver-side edge (left when
 * side-by-side, top when stacked) so tap-to-swap does not move the handle.
 * Flush to the outer frame ([SplitPane.FULLSCREEN_PEEL_INSET_DP]). Idle visual is a
 * short edge-adsorbed tab. Once the user drags, the host morphs this view to the
 * normal full-length split seam + dots ([setPeelDragSplitVisual]).
 *
 * Hit target is wider than the visual seam and overlaps adjacent panes via negative
 * margins + elevation. Ends of the strip ([SplitPane.DIVIDER_TOUCH_END_INSET_DP] in
 * split; peel uses a center hit band around [SplitPane.PEEL_TAB_LENGTH_DP]) do not
 * consume touches so pane chrome stays tappable.
 */
class SplitDividerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var sideBySide: Boolean = true

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
    /** Dark tab fills so the handle reads on both bright maps and dark media. */
    private val peelPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6282828.toInt()
        style = Paint.Style.FILL
    }
    private val peelPillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8E8.toInt()
        style = Paint.Style.FILL
    }
    private val peelDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F5F5.toInt()
        style = Paint.Style.FILL
    }
    private val peelPillRect = RectF()
    private val peelPillPath = Path()
    private val peelCornerRadii = FloatArray(8)
    private val dotRadius = 2.5f * density
    private val peelDotRadius = 2.25f * density
    private val peelTabLengthPx = SplitPane.PEEL_TAB_LENGTH_DP * density
    private val peelTabThicknessPx = SplitPane.PEEL_TAB_THICKNESS_DP * density
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
    private var peelMode = false
    /** While peeling: draw the normal split seam instead of the idle edge tab. */
    private var peelDragSplitVisual = false

    private val longPressRunnable = Runnable {
        if (!tracking || dragging || longPressFired) return@Runnable
        longPressFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onStackClick?.invoke()
    }

    init {
        isClickable = true
        // Elevation for Z-order above panes (2) / empty overlays (4); empty outline
        // so Material does not cast a shadow around the hit strip / peel tab.
        elevation = 8f
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setEmpty()
            }
        }
        clipToOutline = false
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

    /** [SplitPane.FULLSCREEN_NONE] = split divider; PRIMARY/SECONDARY = peel handle. */
    fun setFullscreenPane(pane: Int) {
        val next = SplitPane.isFullscreenPane(pane)
        peelMode = next
        peelDragSplitVisual = false
        elevation = 8f
        translationX = 0f
        translationY = 0f
        invalidate()
    }

    /**
     * Peel handle is only an entry affordance. Once drag starts, show the same
     * full-length seam + dots as split mode until settle / cancel.
     */
    fun setPeelDragSplitVisual(enabled: Boolean) {
        if (peelDragSplitVisual == enabled) return
        peelDragSplitVisual = enabled
        invalidate()
    }

    /**
     * Peel handle in a [FrameLayout] host — fixed driver-side edge: left when
     * [sideBySide], top when stacked. Flush to the outer frame (see inset const).
     */
    fun applyPeelLayoutParams(lp: FrameLayout.LayoutParams, sideBySide: Boolean) {
        val seamPx = (SplitPane.DIVIDER_DP * density).toInt().coerceAtLeast(1)
        val expandPx = (SplitPane.DIVIDER_TOUCH_EXPAND_DP * density).toInt().coerceAtLeast(0)
        val touchSpan = seamPx + 2 * expandPx
        val insetPx = (SplitPane.FULLSCREEN_PEEL_INSET_DP * density).toInt().coerceAtLeast(0)
        // When flush, widen the strip to cover the Coolwalk rail steal band so
        // reinjected rail coordinates still hit this view; tab draws at the outer edge.
        val edgeHitMin = (SplitPane.PEEL_EDGE_HIT_MIN_DP * density).toInt().coerceAtLeast(0)
        val edgeSpan = if (insetPx == 0) touchSpan.coerceAtLeast(edgeHitMin) else touchSpan
        // Long-axis size matches the tappable tab band (not full screen) so the
        // hit rect and capsule share the same visual center — avoids a tall empty
        // strip looking like a misaligned divider.
        val longHit = (
            SplitPane.PEEL_TAB_LENGTH_DP + 2 * SplitPane.PEEL_TAB_HIT_EXPAND_DP
            ).let { (it * density).toInt().coerceAtLeast(1) }
        lp.width = if (sideBySide) edgeSpan else longHit
        lp.height = if (sideBySide) longHit else edgeSpan
        lp.marginStart = 0
        lp.marginEnd = 0
        lp.topMargin = 0
        lp.bottomMargin = 0
        if (sideBySide) {
            lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            lp.marginStart = insetPx
        } else {
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin = insetPx
        }
    }

    /**
     * Touches on the long-axis ends fall through to the panes. If the divider /
     * peel hit band is shorter than two insets, keep the full span so a tiny
     * display still works.
     */
    private fun isOnEndInset(event: MotionEvent): Boolean {
        // Peel view is already sized to the tab hit band — consume the whole view.
        if (peelMode) return false
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
        val drawPeelTab = peelMode && !peelDragSplitVisual
        if (drawPeelTab) {
            val (dotX, dotY) = drawPeelEdgeTab(canvas)
            drawDots(canvas, dotX, dotY, vertical = sideBySide, peel = true)
        } else if (sideBySide) {
            canvas.drawLine(cx, 0f, cx, height.toFloat(), seamPaint)
            drawDots(canvas, cx, cy, vertical = true, peel = false)
        } else {
            canvas.drawLine(0f, cy, width.toFloat(), cy, seamPaint)
            drawDots(canvas, cx, cy, vertical = false, peel = false)
        }
    }

    /**
     * Edge-adsorbed drawer tab: flush to the outer (driver) edge of this view,
     * rounded only on the inward side so it reads as stuck to the rail side.
     * View is sized to the tab hit band, so the capsule is centered in both axes
     * of the short axis / mid of the long axis.
     * @return center of the tab for the grip dots
     */
    private fun drawPeelEdgeTab(canvas: Canvas): Pair<Float, Float> {
        val halfLong = peelTabLengthPx / 2f
        val thick = peelTabThicknessPx.coerceAtMost(
            if (sideBySide) width.toFloat() else height.toFloat()
        )
        val r = thick / 2f
        // Only round the two corners facing into content.
        for (i in peelCornerRadii.indices) peelCornerRadii[i] = 0f
        if (sideBySide) {
            val cy = height / 2f
            peelPillRect.set(0f, cy - halfLong, thick, cy + halfLong)
            // top-right + bottom-right
            peelCornerRadii[2] = r
            peelCornerRadii[3] = r
            peelCornerRadii[4] = r
            peelCornerRadii[5] = r
            peelPillPath.reset()
            peelPillPath.addRoundRect(peelPillRect, peelCornerRadii, Path.Direction.CW)
            canvas.drawPath(peelPillPath, peelPillPaint)
            canvas.drawPath(peelPillPath, peelPillStrokePaint)
            return thick / 2f to cy
        } else {
            val cx = width / 2f
            peelPillRect.set(cx - halfLong, 0f, cx + halfLong, thick)
            // bottom-left + bottom-right
            peelCornerRadii[4] = r
            peelCornerRadii[5] = r
            peelCornerRadii[6] = r
            peelCornerRadii[7] = r
            peelPillPath.reset()
            peelPillPath.addRoundRect(peelPillRect, peelCornerRadii, Path.Direction.CW)
            canvas.drawPath(peelPillPath, peelPillPaint)
            canvas.drawPath(peelPillPath, peelPillStrokePaint)
            return cx to thick / 2f
        }
    }

    private fun drawDots(canvas: Canvas, cx: Float, cy: Float, vertical: Boolean, peel: Boolean) {
        val paint = if (peel) peelDotPaint else dotPaint
        val radius = if (peel) peelDotRadius else dotRadius
        val offsets = floatArrayOf(-dotGap, 0f, dotGap)
        for (offset in offsets) {
            if (vertical) {
                canvas.drawCircle(cx, cy + offset, radius, paint)
            } else {
                canvas.drawCircle(cx + offset, cy, radius, paint)
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
                // Preview unclamped; settle decides enter-FS / exit-FS / clamp.
                val raw = rawRatioFromEvent(event, parentView)
                lastRawRatio = raw
                if (abs(raw - lastRatio) > 0.002f) {
                    lastRatio = raw
                    onRatioChanged?.invoke(raw)
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
        if (peelMode) {
            // Fixed left/top peel: drag inward raises ratio; exit past threshold.
            if (lastRawRatio >= SplitPane.FULLSCREEN_EXIT_RATIO) {
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
