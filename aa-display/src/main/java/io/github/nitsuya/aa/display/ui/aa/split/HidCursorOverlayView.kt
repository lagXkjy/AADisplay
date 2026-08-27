package io.github.nitsuya.aa.display.ui.aa.split

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Phone BT mouse sprite on the AaDisplay shell — tip at [translationX]/[translationY].
 * Small footprint + no touch capture so [SplitDividerView] stays operable.
 */
class HidCursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowPath = Path()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }

    fun setCursorPosition(x: Float, y: Float) {
        val moved = translationX != x || translationY != y
        translationX = x
        translationY = y
        if (visibility != VISIBLE) visibility = VISIBLE
        if (moved) invalidate()
    }

    fun hideCursor() {
        if (visibility == GONE) return
        visibility = GONE
        invalidate()
    }

    /** Never steal touches from divider / TextureViews below. */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE) return
        val d = resources.displayMetrics.density
        strokePaint.strokeWidth = 1.25f * d
        buildArrowPath(d)
        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(arrowPath, strokePaint)
    }

    /** Classic upright pointer — tip at local (0, 0), stem runs vertically. */
    private fun buildArrowPath(d: Float) {
        arrowPath.reset()
        arrowPath.moveTo(0f, 0f)
        arrowPath.lineTo(0f, 16f * d)
        arrowPath.lineTo(4.5f * d, 12f * d)
        arrowPath.lineTo(7f * d, 20f * d)
        arrowPath.lineTo(9.5f * d, 12f * d)
        arrowPath.lineTo(14f * d, 12f * d)
        arrowPath.close()
    }
}
