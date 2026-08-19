package io.github.nitsuya.aa.display.ui.aa.split

import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerHidden
import android.os.Binder
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import io.github.nitsuya.aa.display.xposed.util.Instances

internal data class PaneSizes(
    val primaryW: Int,
    val primaryH: Int,
    val secondaryW: Int,
    val secondaryH: Int,
)

internal class SplitVdLifecycle(private val c: SplitDisplayController) {

    fun dividerPx(): Int {
        val dpi = c.mDensityDpi.coerceAtLeast(160)
        return (SplitPane.DIVIDER_DP * dpi / 160f).toInt().coerceAtLeast(8)
    }

    fun computePaneSizes(): PaneSizes {
        // Fullscreen: both VDs stay full-buffer so the hidden pane keeps rendering
        // (nav/media behind) while the AA UI stacks TextureViews.
        if (SplitPane.isFullscreenPane(c.mFullscreenPane)) {
            val w = c.mWidth.coerceAtLeast(1)
            val h = c.mHeight.coerceAtLeast(1)
            return PaneSizes(w, h, w, h)
        }
        // Split: VD buffer == pane TextureView size (live HU profile × ratio).
        // Do NOT keep both panes at full HU and crop — that makes every half-pane
        // show a center slice of a full-screen layout (looks like wrong resolution).
        val gap = dividerPx()
        return if (c.isSideBySide) {
            val usable = (c.mWidth - gap).coerceAtLeast(2)
            val pw = (usable * c.mRatio).toInt().coerceAtLeast(1)
            val sw = (usable - pw).coerceAtLeast(1)
            PaneSizes(pw, c.mHeight, sw, c.mHeight)
        } else {
            val usable = (c.mHeight - gap).coerceAtLeast(2)
            val ph = (usable * c.mRatio).toInt().coerceAtLeast(1)
            val sh = (usable - ph).coerceAtLeast(1)
            PaneSizes(c.mWidth, ph, c.mWidth, sh)
        }
    }

    fun vdFlags(): Int {
        // Do NOT set VIRTUAL_DISPLAY_FLAG_PRESENTATION. Douyin LivePlay uses MediaRouter
        // to attach a ty=PRESENTATION window onto "presentation" displays; with the flag
        // set, PRIMARY is offered as a target while the task stays on SECONDARY, covering
        // 高德 and killing key focus. Apps launched *on* the VD still work without it.
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TRUSTED or
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
    }

    fun resizePanesInternal(reason: String) {
        val primary = c.mPrimary ?: return
        val secondary = c.mSecondary ?: return
        val sizes = computePaneSizes()
        val primaryChanged =
            sizes.primaryW != c.mLastPrimaryW || sizes.primaryH != c.mLastPrimaryH
        val secondaryChanged =
            sizes.secondaryW != c.mLastSecondaryW || sizes.secondaryH != c.mLastSecondaryH
        if (!primaryChanged && !secondaryChanged && reason != "reconnect") {
            return
        }
        c.mLastPrimaryW = sizes.primaryW
        c.mLastPrimaryH = sizes.primaryH
        c.mLastSecondaryW = sizes.secondaryW
        c.mLastSecondaryH = sizes.secondaryH
        c.mLastResizeAt = SystemClock.uptimeMillis()
        c.mSuppressReclaimUntil = SystemClock.uptimeMillis() + 800L
        try {
            // Only resize panes whose buffer size actually changes. Fullscreen toggle between
            // panes is a no-op size-wise; resizing both always triggered Samsung
            // ExtraDisplayController.positionChildAt and dropped LivePlay focus/audio.
            if (primaryChanged || reason == "reconnect") {
                primary.resize(sizes.primaryW, sizes.primaryH, c.mDensityDpi)
                applyPolicies(SplitPane.PRIMARY, "resize-$reason")
            }
            if (secondaryChanged || reason == "reconnect") {
                secondary.resize(sizes.secondaryW, sizes.secondaryH, c.mDensityDpi)
                applyPolicies(SplitPane.SECONDARY, "resize-$reason")
            }
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "resize failed:", e)
        }
    }

    fun applyPolicies(pane: Int, reason: String) {
        val displayId = c.input.displayIdFor(pane) ?: return
        val imePolicy = 0 // DISPLAY_IME_POLICY_LOCAL
        try {
            // Resize hot path: IME/decor are sticky after first apply; re-calling WMS on every
            // ratio settle adds system_server work without changing behavior.
            val resizeHotPath = reason.startsWith("resize-")
            val imeAlready = c.mImePolicyAppliedDisplays.contains(displayId)
            if (!(resizeHotPath && imeAlready)) {
                Instances.iWindowManager.apply {
                    setDisplayImePolicy(displayId, imePolicy)
                    setShouldShowWithInsecureKeyguard(displayId, false)
                    setShouldShowSystemDecors(displayId, false)
                }
                c.mImePolicyAppliedDisplays.add(displayId)
            }
            // Narrow side-by-side panes are taller than wide (e.g. 278×480). Landscape apps
            // then rotate the VD to ROTATION_90 (logical 480×278) while the TextureView stays
            // physical W×H → letterbox bars top/bottom. Lock physical orientation.
            // Inverse: fullscreen 800×480 + portrait app → FIXED_ORIENTATION pillarbox
            // (VdOrientationFill so the activity fills the pane instead).
            lockPaneDisplayOrientation(displayId, reason)
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "applyPolicies failed pane=$pane:", e)
        }
    }

    /**
     * Keep each pane VD at ROTATION_0 matching its create/resize buffer so TextureView
     * aspect equals app layout. Reflection: OEM IWindowManager signatures differ.
     */
    fun lockPaneDisplayOrientation(displayId: Int, reason: String) {
        if (displayId == Display.INVALID_DISPLAY) return
        // Samsung freezeDisplayRotation re-walks every DisplayContent; during drag-ratio
        // resize this alone was 600–900ms on system_server main. Keep lock sticky.
        if (reason.startsWith("resize-") && c.mOrientationLockedDisplays.contains(displayId)) {
            return
        }
        val iwm = Instances.iWindowManager
        val identity = Binder.clearCallingIdentity()
        try {
            runCatching {
                iwm.javaClass.methods.firstOrNull { m ->
                    m.name == "setIgnoreOrientationRequest" &&
                        m.parameterTypes.size >= 2 &&
                        m.parameterTypes[0] == Int::class.javaPrimitiveType
                }?.invoke(iwm, displayId, true)
            }.onFailure {
                logDebug(SplitDisplayController.TAG, "setIgnoreOrientationRequest unavailable: ${it.message}")
            }
            runCatching {
                val methods = iwm.javaClass.methods.filter { it.name == "freezeDisplayRotation" }
                val withCaller = methods.firstOrNull { it.parameterTypes.size == 3 }
                val without = methods.firstOrNull { it.parameterTypes.size == 2 }
                when {
                    withCaller != null -> withCaller.invoke(iwm, displayId, Surface.ROTATION_0, "AADisplay")
                    without != null -> without.invoke(iwm, displayId, Surface.ROTATION_0)
                    else -> error("no freezeDisplayRotation")
                }
                c.mOrientationLockedDisplays.add(displayId)
            }.onFailure {
                logDebug(SplitDisplayController.TAG, "freezeDisplayRotation unavailable: ${it.message}")
            }
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "lockPaneDisplayOrientation failed display=$displayId:", e)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    fun addKeepAwakeOverlay(pane: Int) {
        val vd = if (pane == SplitPane.PRIMARY) c.mPrimary else c.mSecondary
        val forceView = if (pane == SplitPane.PRIMARY) c.mPrimaryForceView else c.mSecondaryForceView
        val display = vd?.display ?: return
        try {
            val wm = c.context.createDisplayContext(display)
                .createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                .getSystemService(WindowManager::class.java)
            wm.addView(
                forceView,
                WindowManager.LayoutParams(
                    0, 0,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSPARENT
                ).also {
                    it.gravity = Gravity.START or Gravity.TOP
                    // Do not force LANDSCAPE from overall HU orientation: a narrow pane is often
                    // taller than wide; overlay LANDSCAPE would rotate that VD and letterbox.
                    it.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
                    it.alpha = 0f
                }
            )
            if (pane == SplitPane.PRIMARY) c.mPrimaryWm = wm else c.mSecondaryWm = wm
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "keepAwake overlay failed pane=$pane:", e)
        }
    }

    fun removeKeepAwakeOverlay(pane: Int) {
        try {
            if (pane == SplitPane.PRIMARY) {
                c.mPrimaryWm?.removeView(c.mPrimaryForceView)
                c.mPrimaryWm = null
            } else {
                c.mSecondaryWm?.removeView(c.mSecondaryForceView)
                c.mSecondaryWm = null
            }
        } catch (_: Throwable) {
        }
    }
}
