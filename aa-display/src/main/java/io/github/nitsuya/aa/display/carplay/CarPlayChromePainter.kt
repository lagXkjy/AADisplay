package io.github.nitsuya.aa.display.carplay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane

/**
 * AA-parity split chrome drawn into the CarPlay encode canvas:
 * vertical seam + three grip dots, or fullscreen left-edge peel tab + dots.
 *
 * Kept separate from [CarPlayGlCompositor] so Canvas geometry stays in top-down
 * pixel space (same as Recents / picker overlays).
 */
internal object CarPlayChromePainter {
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val peelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6282828.toInt()
        style = Paint.Style.FILL
    }
    private val peelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE8E8E8.toInt()
        style = Paint.Style.FILL
    }
    private val peelDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF5F5F5.toInt()
        style = Paint.Style.FILL
    }
    private val peelRect = RectF()
    private val peelPath = Path()
    private val cornerRadii = FloatArray(8)
    private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6282828.toInt()
        style = Paint.Style.FILL
    }
    private val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
    }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val chipRect = RectF()
    private val measureText = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Left pane, divider gap, right pane. Same math as
     * [CarPlayGlCompositor] split layout so the hit seam sits on the painted line.
     */
    fun splitStrip(encodeWidth: Int, ratio: Float, densityDpi: Int): Triple<Int, Int, Int> {
        val gap = SplitPane.dividerPx(densityDpi)
        val usable = (encodeWidth - gap).coerceAtLeast(2)
        val clamped = ratio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        val left = (usable * clamped).toInt().coerceAtLeast(1)
        val right = (usable - left).coerceAtLeast(1)
        return Triple(left, gap, right)
    }

    /**
     * AA [AaMainFragment] chip: centered on the IME pane, 16dp above its bottom.
     * Fullscreen uses the whole canvas. Writes [out] and returns false when [pane]
     * is not a pane. Safe to call from the GL thread and the touch thread.
     */
    fun layoutHideImeChip(
        out: RectF,
        canvasW: Int,
        canvasH: Int,
        densityDpi: Int,
        pane: Int,
        fullscreen: Boolean,
        ratio: Float,
        label: String,
    ): Boolean {
        if (!SplitPane.isValid(pane) || canvasW <= 0 || canvasH <= 0) return false
        val d = densityDpi.coerceAtLeast(160) / 160f
        val text = label.ifEmpty { "收起键盘" }
        val textW: Float
        val textH: Float
        synchronized(measureText) {
            measureText.textSize = 15f * d
            textW = measureText.measureText(text)
            val fm = measureText.fontMetrics
            textH = fm.descent - fm.ascent
        }
        val cw = textW + 40f * d
        val ch = textH + 20f * d
        val margin = 16f * d
        val paneLeft: Float
        val paneWidth: Float
        if (fullscreen) {
            paneLeft = 0f
            paneWidth = canvasW.toFloat()
        } else {
            val (left, gap, right) = splitStrip(canvasW, ratio, densityDpi)
            if (pane == SplitPane.PRIMARY) {
                paneLeft = 0f
                paneWidth = left.toFloat()
            } else {
                paneLeft = (left + gap).toFloat()
                paneWidth = right.toFloat()
            }
        }
        val leftEdge = paneLeft + ((paneWidth - cw) / 2f).coerceAtLeast(0f)
        val top = (canvasH - ch - margin).coerceAtLeast(margin)
        out.set(leftEdge, top, leftEdge + cw, top + ch)
        return true
    }

    fun drawHideImeChip(canvas: Canvas, rect: RectF, densityDpi: Int, label: String) {
        val d = densityDpi.coerceAtLeast(160) / 160f
        chipRect.set(rect)
        val r = 20f * d
        chipStroke.strokeWidth = d
        canvas.drawRoundRect(chipRect, r, r, chipFill)
        canvas.drawRoundRect(chipRect, r, r, chipStroke)
        chipText.textSize = 15f * d
        val baseline = chipRect.centerY() - (chipText.descent() + chipText.ascent()) / 2f
        canvas.drawText(label.ifEmpty { "收起键盘" }, chipRect.centerX(), baseline, chipText)
    }

    /** Density relative to 160dpi, derived from encode height (720 → ~1.0× AA @160 on phone). */
    private fun density(h: Int): Float = (h / 720f).coerceIn(0.75f, 1.5f)

    fun drawSplitDivider(canvas: Canvas, w: Int, h: Int, seamX: Int) {
        val d = density(h)
        seamPaint.strokeWidth = 1.5f * d
        val x = seamX.toFloat()
        canvas.drawLine(x, 0f, x, h.toFloat(), seamPaint)
        drawDots(canvas, x, h / 2f, vertical = true, peel = false, d)
    }

    fun drawPeelTab(canvas: Canvas, w: Int, h: Int) {
        val d = density(h)
        val tabLen = SplitPane.PEEL_TAB_LENGTH_DP * d * 1.15f
        val thick = (SplitPane.PEEL_TAB_THICKNESS_DP * d * 1.35f).coerceAtLeast(8f)
        val half = tabLen / 2f
        val cy = h / 2f
        peelRect.set(0f, cy - half, thick, cy + half)
        val r = thick / 2f
        for (i in cornerRadii.indices) cornerRadii[i] = 0f
        // Round inward (right) corners only — AA drawer-tab look.
        cornerRadii[2] = r
        cornerRadii[3] = r
        cornerRadii[4] = r
        cornerRadii[5] = r
        peelPath.reset()
        peelPath.addRoundRect(peelRect, cornerRadii, Path.Direction.CW)
        peelStroke.strokeWidth = 1.25f * d
        canvas.drawPath(peelPath, peelFill)
        canvas.drawPath(peelPath, peelStroke)
        drawDots(canvas, thick / 2f, cy, vertical = true, peel = true, d)
    }

    private fun drawDots(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        vertical: Boolean,
        peel: Boolean,
        d: Float,
    ) {
        val paint = if (peel) peelDotPaint else dotPaint
        val radius = (if (peel) SplitPane.DIVIDER_DOT_RADIUS_DP * 0.9f else SplitPane.DIVIDER_DOT_RADIUS_DP) * d
        val gap = SplitPane.DIVIDER_DOT_GAP_DP * d
        for (offset in floatArrayOf(-gap, 0f, gap)) {
            if (vertical) {
                canvas.drawCircle(cx, cy + offset, radius, paint)
            } else {
                canvas.drawCircle(cx + offset, cy, radius, paint)
            }
        }
    }

    /**
     * Phone BT mouse tip in content-rect coords (hotspot = tip).
     * Lucide mouse-pointer-2 via [R.drawable.ic_hid_cursor_pointer]; falls back to a
     * filled triangle if the drawable cannot be resolved on the GL thread.
     */
    fun drawHidCursor(
        canvas: Canvas,
        tipX: Float,
        tipY: Float,
        densityDpi: Int,
        cursorDrawable: Drawable?,
    ) {
        val d = densityDpi.coerceAtLeast(160) / 160f
        val size = CURSOR_SIZE_DP * d
        val hotX = HOTSPOT_X_DP * d
        val hotY = HOTSPOT_Y_DP * d
        canvas.save()
        canvas.translate(tipX - hotX, tipY - hotY)
        if (cursorDrawable != null) {
            val px = size.toInt().coerceAtLeast(1)
            cursorDrawable.setBounds(0, 0, px, px)
            cursorDrawable.draw(canvas)
        } else {
            cursorFillPath.reset()
            cursorFillPath.moveTo(0f, 0f)
            cursorFillPath.lineTo(size * 0.72f, size * 0.28f)
            cursorFillPath.lineTo(size * 0.38f, size * 0.38f)
            cursorFillPath.lineTo(size * 0.28f, size * 0.72f)
            cursorFillPath.close()
            canvas.drawPath(cursorFillPath, cursorFill)
            canvas.drawPath(cursorFillPath, cursorStroke.apply { strokeWidth = 1.15f * d })
        }
        canvas.restore()
    }

    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val cursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val cursorFillPath = Path()

    private const val HOTSPOT_X_DP = 4.04f
    private const val HOTSPOT_Y_DP = 4.69f
    private const val CURSOR_SIZE_DP = 24f

    /**
     * Painted bounds (top-left origin, content-rect pixel coords, inflated for stroke /
     * dot / AA slack) of each chrome element. [CarPlayGlCompositor] unions the prev and
     * new key rects to clear / [android.opengl.GLUtils.texSubImage2D] only the affected
     * region instead of re-uploading the full ARGB overlay on every cursor tick.
     */
    fun dividerRect(seamX: Int, h: Int): Rect? {
        if (seamX < 0 || h <= 0) return null
        val d = density(h)
        // Dots reach gap + radius (9.5d) from the seam line; leave stroke / AA slack.
        val m = (12f * d).toInt() + 2
        return Rect(seamX - m, 0, seamX + m + 1, h)
    }

    fun peelTabRect(w: Int, h: Int): Rect? {
        if (w <= 0 || h <= 0) return null
        val d = density(h)
        val tabLen = SplitPane.PEEL_TAB_LENGTH_DP * d * 1.15f
        val thick = (SplitPane.PEEL_TAB_THICKNESS_DP * d * 1.35f).coerceAtLeast(8f)
        val cy = h / 2f
        val m = (10f * d).toInt() + 2
        val left = 0
        val top = (cy - tabLen / 2f - m).toInt().coerceAtLeast(0)
        val right = (thick + 2 * m).toInt().coerceAtMost(w)
        val bottom = (cy + tabLen / 2f + m).toInt().coerceAtMost(h)
        return Rect(left, top, right, bottom)
    }

    fun cursorRect(tipX: Float, tipY: Float, densityDpi: Int): Rect? {
        if (tipX.isNaN() || tipY.isNaN()) return null
        val d = densityDpi.coerceAtLeast(160) / 160f
        val size = CURSOR_SIZE_DP * d
        val m = 3f * d
        val left = (tipX - HOTSPOT_X_DP * d - m).toInt()
        val top = (tipY - HOTSPOT_Y_DP * d - m).toInt()
        val sizePx = size.toInt().coerceAtLeast(1)
        val mPx = m.toInt().coerceAtLeast(1)
        return Rect(left, top, left + sizePx + 2 * mPx, top + sizePx + 2 * mPx)
    }
}
