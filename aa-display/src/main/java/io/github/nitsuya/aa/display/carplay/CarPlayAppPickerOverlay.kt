package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.MotionEvent
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.ui.aa.split.SplitAppEntry
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.LastSplitStore
import io.github.nitsuya.aa.display.util.PmIconCache
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

/**
 * CarPlay Host **Canvas** app picker (tee encode path).
 *
 * Host has no Window — View snapshot + [android.view.View.dispatchTouchEvent] breaks
 * RecyclerView scroll/click. Draw + hit-test like [CarPlayRecentsOverlay].
 *
 * Visuals: scrim,「选择应用」,「最近」/「全部应用」, rounded icons,
 * bottom「关闭」bar. Icons use BitmapShader round-rect (Launcher3 / Romain Guy pattern)
 * — no third-party UI library on the encode canvas.
 */
object CarPlayAppPickerOverlay {
    private val TAG = CarPlayConsts.TAG

    private const val COLS = 6
    private const val ICON_BMP_SIZE = 128
    private const val ICON_CORNER_FRAC = 0.22f
    private const val TAP_SLOP_NORM = 0.018f

    // Layout fractions of encode height / width (1280×720 @1:1).
    private const val PAD_X = 0.025f
    private const val TITLE_TOP = 0.035f
    private const val TITLE_SIZE = 0.042f
    private const val GRID_TOP = 0.10f
    private const val CLOSE_H = 0.075f
    private const val CLOSE_BOTTOM = 0.025f
    private const val CLOSE_RADIUS = 0.014f
    private const val HEADER_H = 0.055f
    private const val CELL_H = 0.175f
    private const val ICON_FRAC = 0.55f
    private const val LABEL_SIZE = 0.022f
    private const val HEADER_SIZE = 0.028f
    private const val CLOSE_TEXT = 0.032f

    private val visible = AtomicBoolean(false)
    private val loadGeneration = AtomicInteger(0)
    private val loadExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "carplay-app-picker").apply { isDaemon = true }
    }
    private val launchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "carplay-app-picker-launch").apply { isDaemon = true }
    }

    @Volatile
    private var appContextRef: WeakReference<Context>? = null

    @Volatile
    private var targetPane: Int = SplitPane.PRIMARY

    @Volatile
    private var rows: List<Row> = emptyList()

    @Volatile
    private var launchableCache: List<SplitAppEntry>? = null

    @Volatile
    private var scrollY = 0f

    private var touchDownY = 0f
    private var touchDownScroll = 0f
    private var touchMoved = false
    private var touchDownX = 0f

    private val bgPaint = Paint().apply { color = 0xCC000000.toInt() }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF455A64.toInt() }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textAlign = Paint.Align.LEFT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val closeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tmpRect = RectF()

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class App(
            val entry: SplitAppEntry,
            @Volatile var iconBmp: Bitmap?,
        ) : Row()
    }

    fun bindContext(context: Context) = bind(context)

    fun bind(context: Context) {
        appContextRef = WeakReference(context.applicationContext)
        prefetch()
    }

    fun unbind() {
        close()
        appContextRef = null
        rows = emptyList()
        launchableCache = null
        scrollY = 0f
    }

    /** Warm launchable metadata off the critical path. */
    fun prefetch() {
        loadExecutor.execute {
            try {
                ensureLaunchableCache()
            } catch (_: Throwable) {
            }
        }
    }

    fun isVisible(): Boolean = visible.get()

    fun show(pane: Int) {
        if (!SplitPane.isValid(pane)) return
        targetPane = pane
        scrollY = 0f
        touchMoved = false
        // Mark visible before closing Recents so shell-capture does not drop in between.
        visible.set(true)
        CarPlayRecentsOverlay.close()
        HidShellOverlayGate.sync()
        val gen = loadGeneration.incrementAndGet()
        loadExecutor.execute {
            val built = try {
                buildRows()
            } catch (e: Throwable) {
                Log.w(TAG, "picker load failed", e)
                emptyList()
            }
            if (gen != loadGeneration.get()) return@execute
            rows = built
            Log.i(TAG, "CarPlay app picker shown pane=$pane rows=${built.size} (canvas)")
        }
    }

    fun close() {
        if (visible.compareAndSet(true, false)) {
            loadGeneration.incrementAndGet()
            scrollY = 0f
            Log.i(TAG, "CarPlay app picker closed")
            HidShellOverlayGate.sync()
        }
    }

    fun draw(canvas: Canvas, w: Int, h: Int) {
        if (!visible.get()) return
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val padX = w * PAD_X
        titlePaint.textSize = h * TITLE_SIZE
        canvas.drawText("选择应用", padX, h * (TITLE_TOP + TITLE_SIZE), titlePaint)

        val gridTop = h * GRID_TOP
        val closeTop = h * (1f - CLOSE_BOTTOM - CLOSE_H)
        val gridBottom = closeTop - h * 0.015f
        val cellH = h * CELL_H
        val headerH = h * HEADER_H
        val contentH = contentHeight(rows, cellH, headerH)
        val viewport = (gridBottom - gridTop).coerceAtLeast(1f)
        val maxScroll = max(0f, contentH - viewport)
        scrollY = scrollY.coerceIn(0f, maxScroll)

        val save = canvas.save()
        canvas.clipRect(0f, gridTop, w.toFloat(), gridBottom)
        var y = gridTop - scrollY
        var col = 0
        val cellW = (w - padX * 2f) / COLS
        val iconSize = cellH * ICON_FRAC
        headerPaint.textSize = h * HEADER_SIZE
        labelPaint.textSize = h * LABEL_SIZE

        for (row in rows) {
            when (row) {
                is Row.Header -> {
                    if (col != 0) {
                        y += cellH
                        col = 0
                    }
                    if (y + headerH >= gridTop && y <= gridBottom) {
                        canvas.drawText(row.title, padX, y + headerH * 0.72f, headerPaint)
                    }
                    y += headerH
                }
                is Row.App -> {
                    val cx = padX + col * cellW + cellW / 2f
                    val top = y
                    if (top + cellH >= gridTop && top <= gridBottom) {
                        drawAppCell(canvas, row, cx, top, cellH, iconSize)
                    }
                    col++
                    if (col >= COLS) {
                        col = 0
                        y += cellH
                    }
                }
            }
        }
        canvas.restoreToCount(save)

        val closeL = padX
        val closeR = w - padX
        val radius = h * CLOSE_RADIUS
        tmpRect.set(closeL, closeTop, closeR, closeTop + h * CLOSE_H)
        canvas.drawRoundRect(tmpRect, radius, radius, closePaint)
        closeTextPaint.textSize = h * CLOSE_TEXT
        canvas.drawText(
            "关闭",
            w / 2f,
            closeTop + h * CLOSE_H * 0.68f,
            closeTextPaint,
        )
    }

    private fun drawAppCell(
        canvas: Canvas,
        row: Row.App,
        cx: Float,
        top: Float,
        cellH: Float,
        iconSize: Float,
    ) {
        val bmp = row.iconBmp
        if (bmp != null && !bmp.isRecycled) {
            val left = cx - iconSize / 2f
            val itop = top + cellH * 0.08f
            tmpRect.set(left, itop, left + iconSize, itop + iconSize)
            canvas.drawBitmap(bmp, null, tmpRect, iconPaint)
        }
        canvas.drawText(ellipsize(row.entry.label, 10), cx, top + cellH * 0.88f, labelPaint)
    }

    fun onTouch(xNorm: Float, yNorm: Float, action: Int): Boolean {
        if (!visible.get()) return false
        val x = xNorm.coerceIn(0f, 1f)
        val y = yNorm.coerceIn(0f, 1f)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = x
                touchDownY = y
                touchDownScroll = scrollY
                touchMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = abs(y - touchDownY)
                val dx = abs(x - touchDownX)
                if (dy > TAP_SLOP_NORM || dx > TAP_SLOP_NORM) {
                    touchMoved = true
                }
                val h = CarPlayInputBridge.shellHeightPx.coerceAtLeast(1).toFloat()
                val gridTop = h * GRID_TOP
                val closeTop = h * (1f - CLOSE_BOTTOM - CLOSE_H)
                val viewport = (closeTop - h * 0.015f - gridTop).coerceAtLeast(1f)
                val contentH = contentHeight(rows, h * CELL_H, h * HEADER_H)
                val maxScroll = max(0f, contentH - viewport)
                scrollY = (touchDownScroll - (y - touchDownY) * h).coerceIn(0f, maxScroll)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (action == MotionEvent.ACTION_CANCEL || touchMoved) {
                    return true
                }
                return handleTap(x, y)
            }
            else -> return true
        }
    }

    private fun handleTap(xNorm: Float, yNorm: Float): Boolean {
        val closeTopNorm = 1f - CLOSE_BOTTOM - CLOSE_H
        if (yNorm in closeTopNorm..(closeTopNorm + CLOSE_H)) {
            close()
            return true
        }
        if (yNorm < GRID_TOP || yNorm >= closeTopNorm) {
            close()
            return true
        }

        // Use live encode size (AAP / CarPlay may differ from ENCODE_* defaults).
        val h = CarPlayInputBridge.shellHeightPx.coerceAtLeast(1).toFloat()
        val w = CarPlayInputBridge.shellWidthPx.coerceAtLeast(1).toFloat()
        val padX = w * PAD_X
        val gridTop = h * GRID_TOP
        val closeTopPx = h * closeTopNorm
        val gridBottom = closeTopPx - h * 0.015f
        val cellH = h * CELL_H
        val headerH = h * HEADER_H
        val cellW = (w - padX * 2f) / COLS
        val touchY = yNorm * h
        val touchX = xNorm * w

        if (touchY < gridTop || touchY > gridBottom) {
            close()
            return true
        }

        var y = gridTop - scrollY
        var col = 0
        for (row in rows) {
            when (row) {
                is Row.Header -> {
                    if (col != 0) {
                        y += cellH
                        col = 0
                    }
                    y += headerH
                }
                is Row.App -> {
                    val left = padX + col * cellW
                    val top = y
                    val right = left + cellW
                    val bottom = top + cellH
                    if (touchX in left..right && touchY in top..bottom) {
                        launch(row.entry)
                        return true
                    }
                    col++
                    if (col >= COLS) {
                        col = 0
                        y += cellH
                    }
                }
            }
        }
        close()
        return true
    }

    private fun launch(entry: SplitAppEntry) {
        val pane = targetPane
        val pkg = entry.packageName
        close()
        launchExecutor.execute {
            try {
                CoreApi.startActivityOnPaneForUser(pkg, 0, pane)
                Log.i(TAG, "picker launch $pkg pane=$pane")
            } catch (e: Throwable) {
                Log.w(TAG, "picker launch failed pkg=$pkg", e)
            }
        }
    }

    private fun contentHeight(list: List<Row>, cellH: Float, headerH: Float): Float {
        var h = 0f
        var col = 0
        for (row in list) {
            when (row) {
                is Row.Header -> {
                    if (col != 0) {
                        h += cellH
                        col = 0
                    }
                    h += headerH
                }
                is Row.App -> {
                    col++
                    if (col >= COLS) {
                        col = 0
                        h += cellH
                    }
                }
            }
        }
        if (col != 0) h += cellH
        return h
    }

    private fun buildRows(): List<Row> {
        val all = ensureLaunchableCache()
        val byPkg = all.associateBy { it.packageName }
        val recentPkgs = loadRecentPackages()
        val recentEntries = recentPkgs.mapNotNull { byPkg[it] }.distinctBy { it.packageName }
        val recentSet = recentEntries.map { it.packageName }.toSet()
        val rest = all.filter { it.packageName !in recentSet }
        val out = mutableListOf<Row>()
        val ctx = appContextRef?.get()
        val pm = ctx?.packageManager
        fun mapApp(entry: SplitAppEntry): Row.App {
            val icon = pm?.let { PmIconCache.getOrLoadDrawable(it, entry.packageName) }
            return Row.App(entry, roundedIconBitmap(icon, ICON_BMP_SIZE))
        }
        if (recentEntries.isNotEmpty()) {
            out += Row.Header("最近")
            recentEntries.forEach { out += mapApp(it) }
        }
        out += Row.Header("全部应用")
        rest.forEach { out += mapApp(it) }
        return out
    }

    private fun ensureLaunchableCache(): List<SplitAppEntry> {
        launchableCache?.let { return it }
        val loaded = loadLaunchableApps()
        launchableCache = loaded
        return loaded
    }

    private fun loadLaunchableApps(): List<SplitAppEntry> {
        val ctx = appContextRef?.get() ?: return emptyList()
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
        return resolved.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            val pkg = ai.packageName
            if (pkg == BuildConfig.APPLICATION_ID) return@mapNotNull null
            if (pkg == "android" || pkg == "com.android.systemui") return@mapNotNull null
            SplitAppEntry(
                packageName = pkg,
                label = ai.loadLabel(pm)?.toString() ?: pkg,
                icon = null,
            )
        }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    private fun loadRecentPackages(): List<String> {
        val ordered = linkedSetOf<String>()
        fun addPkg(raw: String?) {
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { ordered.add(it) }
        }
        try {
            val ctx = appContextRef?.get() ?: return emptyList()
            LastSplitStore.load(ctx.contentResolver)?.let { snap ->
                snap.primaryPackagesBottomToTop().asReversed().forEach(::addPkg)
                snap.secondaryPackagesBottomToTop().asReversed().forEach(::addPkg)
            }
        } catch (_: Throwable) {
        }
        return ordered.toList()
    }

    /**
     * Rasterize + round-rect clip (BitmapShader). Matches common launcher icon shaping
     * without AdaptiveIconDrawable OEM masks looking square on the encode canvas.
     */
    private fun roundedIconBitmap(drawable: Drawable?, size: Int): Bitmap? {
        if (drawable == null) return null
        val src: Bitmap = try {
            when {
                drawable is BitmapDrawable &&
                    drawable.bitmap != null &&
                    !drawable.bitmap.isRecycled -> {
                    val b = drawable.bitmap
                    if (b.width == size && b.height == size) b
                    else Bitmap.createScaledBitmap(b, size, size, true)
                }
                else -> {
                    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(c)
                    bmp
                }
            }
        } catch (_: Throwable) {
            return null
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (src.width != size || src.height != size) {
            val m = Matrix()
            m.setScale(size.toFloat() / src.width, size.toFloat() / src.height)
            shader.setLocalMatrix(m)
        }
        paint.shader = shader
        val r = size * ICON_CORNER_FRAC
        c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, paint)
        val originalBmp = (drawable as? BitmapDrawable)?.bitmap
        if (src !== originalBmp && !src.isRecycled) {
            src.recycle()
        }
        return out
    }

    private fun ellipsize(s: String, maxChars: Int): String =
        if (s.length <= maxChars) s else s.take(maxChars - 1) + "…"
}
