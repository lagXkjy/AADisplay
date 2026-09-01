package io.github.nitsuya.aa.display.ui.aa.split

import android.app.KeyguardManager
import android.content.Intent
import android.os.Binder
import android.view.Display
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AaSystemBroadcast
import io.github.nitsuya.aa.display.xposed.CoreManagerService
import io.github.nitsuya.aa.display.xposed.util.Instances
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import kotlin.math.hypot

/**
 * Phone keyguard occludes Gearhead's AaDisplay presentation (no
 * [ALWAYS_UNLOCKED]), so Coolwalk peel inject via [SplitDisplayController.onTouchAaDisplay]
 * is dropped while pane VDs (ALWAYS_UNLOCKED) still work.
 *
 * While the keyguard is locked and we are in fullscreen, interpret peel gestures here
 * (same semantics as [SplitDividerView] peel mode). Live preview is published to
 * [CoreManagerService.publishLockedPeelPreview] for AaDisplayActivity to pull.
 */
internal class SplitLockedPeelController(private val c: SplitDisplayController) {

    private var tracking = false
    private var dragging = false
    private var longPressFired = false
    private var downX = 0f
    private var downY = 0f
    private var downUptimeMs = 0L
    private var downRatio = 0f
    private var lastRawRatio = 0f

    /** Skip repeated WMS keyguard patch on every peel DOWN when already applied this session. */
    private var keyguardPolicyAppliedDisplayId = Display.INVALID_DISPLAY

    private val touchSlop: Int by lazy {
        ViewConfiguration.get(c.context).scaledTouchSlop
    }
    private val longPressTimeout: Long by lazy {
        ViewConfiguration.getLongPressTimeout().toLong()
            .coerceAtLeast(SplitPane.DIVIDER_TAP_STACK_MIN_MS)
    }

    private val longPressRunnable = Runnable {
        if (tracking && !dragging) {
            longPressFired = true
        }
    }

    /** True when phone keyguard is showing (secure or swipe) — includes screen-on lock UI. */
    fun isPhoneKeyguardLocked(): Boolean {
        return try {
            val km = c.context.getSystemService(KeyguardManager::class.java) ?: return false
            km.isKeyguardLocked
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Allow AaDisplay presentation to stay interactive under keyguard.
     * Insecure: [IWindowManager.setShouldShowWithInsecureKeyguard].
     * Secure: best-effort [Display.FLAG_ALWAYS_UNLOCKED] on DisplayContent.
     */
    fun applyAaUiDisplayKeyguardPolicy(displayId: Int, reason: String) {
        if (displayId == Display.INVALID_DISPLAY || displayId == Display.DEFAULT_DISPLAY) return
        val identity = Binder.clearCallingIdentity()
        try {
            try {
                Instances.iWindowManager.setShouldShowWithInsecureKeyguard(displayId, true)
            } catch (e: Throwable) {
                logDebug(
                    SplitDisplayController.TAG,
                    "setShouldShowWithInsecureKeyguard failed id=$displayId: ${e.message}"
                )
            }
            patchDisplayAlwaysUnlocked(displayId)
            logDebug(
                SplitDisplayController.TAG,
                "applyAaUiDisplayKeyguardPolicy id=$displayId reason=$reason"
            )
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    /**
     * @return true if the event was consumed as a locked-phone peel gesture
     * (caller must not also inject into the presentation).
     */
    fun tryHandle(event: MotionEvent): Boolean {
        if (!isPhoneKeyguardLocked()) {
            if (tracking) reset()
            return false
        }
        if (!SplitPane.isFullscreenPane(c.mFullscreenPane)) {
            if (tracking) reset()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                dragging = false
                longPressFired = false
                downX = event.x
                downY = event.y
                downUptimeMs = event.downTime
                lastRawRatio = rawRatio(event)
                downRatio = lastRawRatio
                // Re-assert once per display per session so OEM keyguard re-occlusion does not stick.
                val aaUiId = c.mAaUiDisplayId
                if (aaUiId != Display.INVALID_DISPLAY &&
                    aaUiId != keyguardPolicyAppliedDisplayId
                ) {
                    applyAaUiDisplayKeyguardPolicy(aaUiId, "locked-peel")
                    keyguardPolicyAppliedDisplayId = aaUiId
                }
                c.mHandler.removeCallbacks(longPressRunnable)
                c.mHandler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dist = hypot(
                    (event.x - downX).toDouble(),
                    (event.y - downY).toDouble()
                ).toFloat()
                val heldMs = (event.eventTime - downUptimeMs).coerceAtLeast(0L)
                if (!dragging &&
                    SplitPane.shouldBeginDividerDrag(
                        dist, touchSlop.toFloat(), longPressFired, heldMs,
                    )
                ) {
                    dragging = true
                    c.mHandler.removeCallbacks(longPressRunnable)
                    longPressFired = false
                }
                if (dragging) {
                    lastRawRatio = rawRatio(event)
                    publishPreview(lastRawRatio)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                val wasDragging = dragging
                val runnableLongPress = longPressFired
                val isUp = event.actionMasked == MotionEvent.ACTION_UP
                val heldMs = (event.eventTime - downUptimeMs).coerceAtLeast(0L)
                val wasLongPress =
                    SplitPane.qualifiesDividerLongPress(runnableLongPress, heldMs)
                if (wasDragging && !wasLongPress) {
                    lastRawRatio = rawRatio(event)
                }
                c.mHandler.removeCallbacks(longPressRunnable)
                tracking = false
                dragging = false
                longPressFired = false
                val dist = hypot(
                    (event.x - downX).toDouble(),
                    (event.y - downY).toDouble(),
                ).toFloat()
                val ratioDelta = lastRawRatio - downRatio
                when {
                    // Drag (incl. peel-out-then-back) never opens Recent — heldMs≥550
                    // used to false-trigger qualifiesDividerRecentOnUp after cancel.
                    isUp && wasDragging -> settleDrag()
                    isUp && wasLongPress -> {
                        publishPreviewCancel()
                        openRecent()
                    }
                    isUp && SplitPane.shouldDividerSwapOnUp(
                        wasDragging, wasLongPress, heldMs, dist, touchSlop.toFloat(), ratioDelta,
                    ) -> {
                        publishPreviewCancel()
                        tapSwapFullscreenPane()
                    }
                    else -> publishPreviewCancel()
                }
                return true
            }
            else -> return tracking
        }
    }

    fun reset() {
        c.mHandler.removeCallbacks(longPressRunnable)
        tracking = false
        dragging = false
        longPressFired = false
        keyguardPolicyAppliedDisplayId = Display.INVALID_DISPLAY
        publishPreviewCancel()
    }

    private fun publishPreview(ratio: Float) {
        CoreManagerService.publishLockedPeelPreview(active = true, ratio = ratio)
    }

    private fun publishPreviewCancel() {
        CoreManagerService.publishLockedPeelPreview(active = false)
    }

    private fun rawRatio(event: MotionEvent): Float {
        val w = c.mWidth.coerceAtLeast(1).toFloat()
        val h = c.mHeight.coerceAtLeast(1).toFloat()
        val ratio = if (c.isSideBySide) event.x / w else event.y / h
        return ratio.coerceIn(0f, 1f)
    }

    private fun settleDrag() {
        if (lastRawRatio < SplitPane.FULLSCREEN_EXIT_RATIO) {
            publishPreviewCancel()
            return
        }
        val release = SplitPane.clampRatio(lastRawRatio)
        publishPreviewCancel()
        // Same order as AaMainFragment.exitFullscreen: stash ratio then one exit resize.
        c.setSplitRatio(release)
        c.setSplitFullscreen(SplitPane.FULLSCREEN_NONE)
        log(
            SplitDisplayController.TAG,
            "lockedPeel exitFullscreen ratio=$release"
        )
    }

    private fun tapSwapFullscreenPane() {
        val cur = c.mFullscreenPane
        if (!SplitPane.isFullscreenPane(cur)) return
        val other = if (cur == SplitPane.PRIMARY) SplitPane.SECONDARY else SplitPane.PRIMARY
        c.setSplitFullscreen(other)
        logDebug(SplitDisplayController.TAG, "lockedPeel tap → fullscreen pane=$other")
    }

    private fun openRecent() {
        try {
            AaSystemBroadcast.toAaDisplay(
                c.context,
                Intent(AABroadcastConst.ACTION_SHOW_RECENT_TASK),
            )
            logDebug(SplitDisplayController.TAG, "lockedPeel long-press → SHOW_RECENT_TASK")
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "lockedPeel openRecent failed:", e)
        }
    }

    /**
     * Best-effort: OR [Display.FLAG_ALWAYS_UNLOCKED] onto the presentation's
     * DisplayInfo so secure keyguard does not occlude AaDisplay chrome.
     */
    private fun patchDisplayAlwaysUnlocked(displayId: Int) {
        val flag = alwaysUnlockedDisplayFlag() ?: return
        try {
            val wms = Class.forName("android.view.WindowManagerGlobal")
                .getDeclaredMethod("getWindowManagerService")
                .apply { isAccessible = true }
                .invoke(null) ?: return
            val root = runCatching { wms.javaClass.getField("mRoot").get(wms) }.getOrNull()
                ?: return
            val displayContent = root.javaClass.methods.firstOrNull { m ->
                m.name == "getDisplayContent" &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.invoke(root, displayId) ?: return
            val info = runCatching {
                displayContent.javaClass.methods.firstOrNull { m ->
                    m.name == "getDisplayInfo" && m.parameterTypes.isEmpty()
                }?.invoke(displayContent)
                    ?: displayContent.javaClass.getField("mDisplayInfo").get(displayContent)
            }.getOrNull() ?: return
            val flagsField = info.javaClass.getField("flags")
            val cur = flagsField.getInt(info)
            if (cur and flag != 0) return
            flagsField.setInt(info, cur or flag)
            logDebug(
                SplitDisplayController.TAG,
                "patchDisplayAlwaysUnlocked id=$displayId flag=0x${Integer.toHexString(flag)}"
            )
        } catch (e: Throwable) {
            logDebug(
                SplitDisplayController.TAG,
                "patchDisplayAlwaysUnlocked unavailable: ${e.message}"
            )
        }
    }

    private fun alwaysUnlockedDisplayFlag(): Int? {
        return try {
            Class.forName("android.view.Display")
                .getField("FLAG_ALWAYS_UNLOCKED")
                .getInt(null)
        } catch (_: Throwable) {
            null
        }
    }
}
