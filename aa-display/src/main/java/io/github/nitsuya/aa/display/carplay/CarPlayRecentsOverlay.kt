package io.github.nitsuya.aa.display.carplay

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.model.RecentTaskInfo
import io.github.nitsuya.aa.display.ui.aa.split.PaneAppStack
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * CarPlay-side 3-column Recents, drawn into the CarPlay image (the host activity is
 * headless — the tee canvas is the only "screen" the driver sees).
 *
 * Layout matches AA [fragment_aa_recent_task]:
 * left = PRIMARY, center = SECONDARY, right = phone (main display);
 * no LEFT/RIGHT/PHONE titles; two vertical column dividers;「添加应用」fixed at the
 * bottom of pane columns; each card = large icon + name, large × centered
 * over the icon (AA [recent_task]); [CoreApi.removeTask] on ×.
 *
 * Opened by divider / peel long-press or wheel PREVIOUS long-press. While open, all
 * CarPlay touches are consumed by [onTouch].
 */
object CarPlayRecentsOverlay {
    private val TAG = CarPlayConsts.TAG

    private const val PHONE_MAX = 8

    /** Shared geometry (normalized / fraction of height or width). */
    private const val CARD_H = 0.20f
    private const val GAP = 0.02f
    private const val START_Y = 0.04f
    private const val CARD_W_FRAC = 0.26f
    private const val ADD_BTN_H = 0.07f
    private const val ADD_BTN_BOTTOM = 0.025f
    private const val DIVIDER_STROKE = 0.0012f

    /** Extra hit pad beyond the visual circle (fat-finger). */
    private const val CLOSE_HIT_EXPAND_DP = 10f
    /** × diameter as fraction of icon edge (centered overlay). */
    private const val CLOSE_FRAC_OF_ICON = 0.55f
    /** App icon center Y within a card. */
    private const val ICON_CY_IN_CARD = 0.42f
    /** Icon max edge as fraction of card height (square; fit keeps aspect). */
    private const val ICON_MAX_FRAC = 0.58f
    /** Label baseline Y within a card, below icon. */
    private const val LABEL_BASELINE_IN_CARD = 0.90f

    private val visible = AtomicBoolean(false)

    @Volatile
    private var columnPrimary = listOf<RecentTaskInfo>()
    @Volatile
    private var columnSecondary = listOf<RecentTaskInfo>()
    @Volatile
    private var columnPhone = listOf<RecentTaskInfo>()

    private val bgPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2A2A.toInt() }
    private val addPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF37474F.toInt() }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        style = Paint.Style.STROKE
    }
    private val closeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC424242.toInt() }
    private val closeXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val tmpRect = RectF()

    fun isVisible(): Boolean = visible.get()

    fun open() {
        if (visible.compareAndSet(false, true)) {
            refresh()
            Log.i(TAG, "CarPlay recents overlay opened")
            HidShellOverlayGate.sync()
        } else {
            refresh()
        }
    }

    fun close() {
        if (visible.compareAndSet(true, false)) {
            Log.i(TAG, "CarPlay recents overlay closed")
            HidShellOverlayGate.sync()
        }
    }

    fun toggle() {
        if (visible.get()) close() else open()
    }

    /** Pulls fresh task lists from system_server. */
    fun refresh() {
        val t = try {
            CoreApi.getRecentTask()
        } catch (e: Throwable) {
            Log.w(TAG, "recents fetch failed", e)
            null
        }
        columnPrimary = (t?.primaryDisplay ?: emptyList()).filter { !it.packageName.isNullOrBlank() }
            .take(PaneAppStack.MAX_PER_PANE)
        columnSecondary = (t?.secondaryDisplay ?: emptyList()).filter { !it.packageName.isNullOrBlank() }
            .take(PaneAppStack.MAX_PER_PANE)
        columnPhone = (t?.mainDisplay ?: emptyList()).filter { !it.packageName.isNullOrBlank() }
            .take(PHONE_MAX)
    }

    /** Tee composite hook: runs on the pump / GL thread. */
    fun draw(canvas: Canvas, w: Int, h: Int) {
        if (!visible.get()) return
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // AA [fragment_aa_recent_task]: two 1dp column dividers, no column titles.
        dividerPaint.strokeWidth = (w * DIVIDER_STROKE).coerceAtLeast(1f)
        canvas.drawLine(w / 3f, 0f, w / 3f, h.toFloat(), dividerPaint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h.toFloat(), dividerPaint)

        drawColumn(canvas, w / 6f, columnPrimary, w, h, showAdd = true)
        drawColumn(canvas, w / 2f, columnSecondary, w, h, showAdd = true)
        drawColumn(canvas, w * 5f / 6f, columnPhone, w, h, showAdd = false)
    }

    private fun addBtnTopNorm(): Float = 1f - ADD_BTN_BOTTOM - ADD_BTN_H

    private fun drawColumn(
        canvas: Canvas,
        centerX: Float,
        items: List<RecentTaskInfo>,
        w: Int,
        h: Int,
        showAdd: Boolean,
    ) {
        val cardW = w * CARD_W_FRAC
        val thumbH = h * CARD_H
        val gap = h * GAP
        val startY = h * START_Y

        for ((i, item) in items.withIndex()) {
            val y = startY + i * (thumbH + gap)
            val x = centerX - cardW / 2f
            tmpRect.set(x, y, x + cardW, y + thumbH)
            canvas.drawRoundRect(tmpRect, cardW * 0.06f, cardW * 0.06f, cardPaint)

            // Large centered icon; large × centered over the icon (AA recent_task).
            val logo = item.logo
            val iconMax = (cardW * 0.72f).coerceAtMost(thumbH * ICON_MAX_FRAC)
            val iconCy = y + thumbH * ICON_CY_IN_CARD
            var iconEdge = iconMax
            if (logo != null && !logo.isRecycled) {
                val scale = (iconMax / logo.height.coerceAtLeast(1))
                    .coerceAtMost(iconMax / logo.width.coerceAtLeast(1))
                val dw = logo.width * scale
                val dh = logo.height * scale
                iconEdge = dw.coerceAtLeast(dh)
                canvas.drawBitmap(
                    logo,
                    null,
                    RectF(
                        centerX - dw / 2f,
                        iconCy - dh / 2f,
                        centerX + dw / 2f,
                        iconCy + dh / 2f,
                    ),
                    null,
                )
            }

            val closeR = iconEdge * CLOSE_FRAC_OF_ICON / 2f
            closeXPaint.strokeWidth = (closeR * 0.22f).coerceAtLeast(2.5f)
            canvas.drawCircle(centerX, iconCy, closeR, closeFill)
            val arm = closeR * 0.42f
            canvas.drawLine(centerX - arm, iconCy - arm, centerX + arm, iconCy + arm, closeXPaint)
            canvas.drawLine(centerX + arm, iconCy - arm, centerX - arm, iconCy + arm, closeXPaint)

            textPaint.textSize = h * 0.026f
            val label = item.label ?: item.packageName ?: "…"
            canvas.drawText(label, centerX, y + thumbH * LABEL_BASELINE_IN_CARD, textPaint)
        }

        if (showAdd) {
            val addY = h * addBtnTopNorm()
            val addH = h * ADD_BTN_H
            val x = centerX - cardW / 2f
            tmpRect.set(x, addY, x + cardW, addY + addH)
            canvas.drawRoundRect(tmpRect, addH * 0.2f, addH * 0.2f, addPaint)
            textPaint.textSize = h * 0.028f
            canvas.drawText("添加应用", centerX, addY + addH * 0.68f, textPaint)
        }
    }

    /**
     * Touch while the overlay is open: DOWN/MOVE are consumed; UP hit-tests close,
     * a card, the bottom add-app button, or dismisses on empty background.
     */
    fun onTouch(xNorm: Float, yNorm: Float, action: Int): Boolean {
        if (!visible.get()) return false
        if (action != MotionEvent.ACTION_UP) return true

        val w = CarPlayConsts.ENCODE_WIDTH.toFloat().coerceAtLeast(1f)
        val h = CarPlayConsts.ENCODE_HEIGHT.toFloat().coerceAtLeast(1f)

        val col = when {
            xNorm < 1f / 3f -> 0
            xNorm < 2f / 3f -> 1
            else -> 2
        }
        val list = when (col) {
            0 -> columnPrimary
            1 -> columnSecondary
            else -> columnPhone
        }
        val pane = when (col) {
            0 -> SplitPane.PRIMARY
            1 -> SplitPane.SECONDARY
            else -> -1
        }

        // Bottom「添加应用」(pane columns only) — fixed like AA btn_add_app_*.
        if (pane >= 0 && yNorm in addBtnTopNorm()..(addBtnTopNorm() + ADD_BTN_H)) {
            close()
            CarPlayAppPickerOverlay.show(pane)
            return true
        }

        val cardSpan = CARD_H + GAP
        val idx = ((yNorm - START_Y) / cardSpan).toInt()
        val localY = yNorm - START_Y - idx * cardSpan
        val inCard = idx >= 0 && idx < list.size && localY in 0f..CARD_H
        if (inCard) {
            val item = list[idx]
            val colCenterX = when (col) {
                0 -> 1f / 6f
                1 -> 0.5f
                else -> 5f / 6f
            }
            // × centered on icon (same geometry as drawColumn).
            val cardW = w * CARD_W_FRAC
            val thumbH = h * CARD_H
            val iconMax = (cardW * 0.72f).coerceAtMost(thumbH * ICON_MAX_FRAC)
            val logo = item.logo
            val iconEdge = if (logo != null && !logo.isRecycled) {
                val scale = (iconMax / logo.height.coerceAtLeast(1))
                    .coerceAtMost(iconMax / logo.width.coerceAtLeast(1))
                (logo.width * scale).coerceAtLeast(logo.height * scale)
            } else {
                iconMax
            }
            val closeR = iconEdge * CLOSE_FRAC_OF_ICON / 2f
            val closeCx = colCenterX * w
            val closeCy = (START_Y + idx * (CARD_H + GAP) + CARD_H * ICON_CY_IN_CARD) * h
            val dxPx = xNorm * w - closeCx
            val dyPx = yNorm * h - closeCy
            if (hypot(dxPx, dyPx) <= closeR + CLOSE_HIT_EXPAND_DP) {
                closeItem(item, col)
                return true
            }
            launchItem(item, pane)
            return true
        }

        // Empty background → dismiss (AA overlay parity).
        close()
        return true
    }

    private fun closeItem(item: RecentTaskInfo, col: Int) {
        val taskId = item.taskId
        if (taskId <= 0) return
        when (col) {
            0 -> columnPrimary = columnPrimary.filterNot { it.taskId == taskId }
            1 -> columnSecondary = columnSecondary.filterNot { it.taskId == taskId }
            else -> columnPhone = columnPhone.filterNot { it.taskId == taskId }
        }
        try {
            CoreApi.removeTask(taskId)
            Log.i(TAG, "recents close taskId=$taskId pkg=${item.packageName}")
        } catch (e: Throwable) {
            Log.w(TAG, "recents close failed taskId=$taskId", e)
            refresh()
        }
    }

    private fun launchItem(item: RecentTaskInfo, pane: Int) {
        val pkg = item.packageName
        if (pkg.isNullOrBlank()) return
        close()
        try {
            if (pane < 0) {
                if (item.taskId > 0) {
                    CoreApi.moveTaskToFront(item.taskId)
                } else {
                    CoreApi.startActivity(pkg, 0)
                }
            } else {
                if (item.taskId > 0) {
                    CoreApi.moveTaskToFront(item.taskId)
                } else {
                    CoreApi.startActivityOnPaneForUser(pkg, 0, pane)
                }
            }
            Log.i(TAG, "recents launch $pkg pane=${if (pane < 0) "phone" else pane} taskId=${item.taskId}")
        } catch (e: Throwable) {
            Log.w(TAG, "recents launch failed pkg=$pkg", e)
        }
    }
}
