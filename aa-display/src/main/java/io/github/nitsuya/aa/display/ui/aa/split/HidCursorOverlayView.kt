package io.github.nitsuya.aa.display.ui.aa.split

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import io.github.nitsuya.aa.display.R

/**
 * Phone BT mouse sprite on the AaDisplay shell — tip at [translationX]/[translationY].
 * Lucide mouse-pointer-2 ([R.drawable.ic_hid_cursor_pointer]).
 */
class HidCursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val cursorDrawable: Drawable =
        ContextCompat.getDrawable(context, R.drawable.ic_hid_cursor_pointer)!!.mutate()
    private var cursorSizePx = 0

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
        val sizePx = (CURSOR_SIZE_DP * d).toInt()
        if (sizePx != cursorSizePx) {
            cursorSizePx = sizePx
            cursorDrawable.setBounds(0, 0, sizePx, sizePx)
        }
        canvas.save()
        canvas.translate(-HOTSPOT_X_DP * d, -HOTSPOT_Y_DP * d)
        cursorDrawable.draw(canvas)
        canvas.restore()
    }

    private companion object {
        const val HOTSPOT_X_DP = 4.04f
        const val HOTSPOT_Y_DP = 4.69f
        const val CURSOR_SIZE_DP = 24f
    }
}
