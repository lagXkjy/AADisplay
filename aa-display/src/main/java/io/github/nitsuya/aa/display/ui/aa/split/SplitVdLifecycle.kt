package io.github.nitsuya.aa.display.ui.aa.split

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerHidden
import android.os.Binder
import android.os.ServiceManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import io.github.nitsuya.aa.display.xposed.hook.PaneDisplayGroupForce
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

    fun dividerPx(): Int = SplitPane.dividerPx(c.mDensityDpi)

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
        //
        // Do NOT set VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS. Android 16 removed
        // IWindowManager#setShouldShowSystemDecors; omitting this create-time bit is the
        // supported equivalent of the old setShouldShowSystemDecors(id, false).
        //
        // PUBLIC is required so non-owner windows (LatinIME) can attach to the pane.
        // Without it FLAG_PRIVATE blocks soft-keyboard windows → SHOW_SOFT_INPUT timeouts
        // (A16 dumpsys: PHASE_WM_SET_REMOTE_TARGET_IME_VISIBILITY / 10s client timeout).
        // PUBLIC alone leaves panes in DisplayGroup 0 on Lineage — PaneDisplayGroupForce
        // reassigns after create so ALWAYS_UNLOCKED / lock sync still work.
        //
        // OWN_FOCUS kept phone as top-focused display for handset input, but on A16 it
        // also prevented LatinIME from attaching a visible window to the pane (IMMS show
        // with mInputShown + no TYPE_INPUT_METHOD on the VD). Omit it so the focused
        // EditText on the pane can own IME the same way as pre-Lineage builds.
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TRUSTED or
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED or
            // Input viewport so BT mouse cursor can bind via setVirtualMousePointerDisplayId
            // (without this, dumpsys shows touch NONE and override falls back to display 0).
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
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
        c.mSuppressReclaimUntil = maxOf(
            c.mSuppressReclaimUntil,
            SystemClock.uptimeMillis() + 800L,
        )
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
                val iwm = Instances.iWindowManager
                // Independent calls: a missing A16 AIDL must not abort IME / keyguard policy.
                runCatching { iwm.setDisplayImePolicy(displayId, imePolicy) }
                    .onFailure {
                        logDebug(SplitDisplayController.TAG, "setDisplayImePolicy: ${it.message}")
                    }
                runCatching { iwm.setShouldShowWithInsecureKeyguard(displayId, false) }
                    .onFailure {
                        logDebug(
                            SplitDisplayController.TAG,
                            "setShouldShowWithInsecureKeyguard: ${it.message}",
                        )
                    }
                forceHideSystemDecors(displayId)
                c.mImePolicyAppliedDisplays.add(displayId)
            }
            // Narrow side-by-side panes are taller than wide (e.g. 278×480). Landscape apps
            // then rotate the VD to ROTATION_90 (logical 480×278) while the TextureView stays
            // physical W×H → letterbox bars top/bottom. Lock physical orientation.
            // Inverse: fullscreen 800×480 + portrait app → FIXED_ORIENTATION pillarbox
            // (VdOrientationFill so the activity fills the pane instead).
            lockPaneDisplayOrientation(displayId, reason)
            // Lineage A16: OWN_DISPLAY_GROUP flag alone (esp. with PUBLIC) leaves panes in
            // Group 0 → phone keyguard/ColorFade sync. Force displayGroupName + reassign.
            // Re-assert shortly after — PUBLIC can trigger a later mapper pass back to 0.
            if (!reason.startsWith("resize-")) {
                PaneDisplayGroupForce.forceOwnGroup(displayId, reason)
                c.mHandler.postDelayed({
                    if (!c.mIsDestroying) {
                        PaneDisplayGroupForce.forceOwnGroup(displayId, "reassert-$reason")
                    }
                }, 750L)
            }
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "applyPolicies failed pane=$pane:", e)
        }
    }

    /**
     * Keep system decorations off pane VDs (status / nav / launcher).
     *
     * Pre-A16: [IWindowManager.setShouldShowSystemDecors].
     * A16+: AIDL removed; [vdFlags] already omits
     * [DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS]
     * (create-time equivalent). Still write
     * [DisplayWindowSettings.setShouldShowSystemDecorsLocked] when present so OEM
     * TRUSTED defaults cannot re-enable decorations — same policy, not a soft skip.
     */
    private fun forceHideSystemDecors(displayId: Int) {
        val iwm = Instances.iWindowManager as Any
        val aidl = iwm.javaClass.methods.firstOrNull { m ->
            m.name == "setShouldShowSystemDecors" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                m.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (aidl != null) {
            runCatching { aidl.invoke(iwm, displayId, false) }
                .onFailure {
                    logDebug(SplitDisplayController.TAG, "setShouldShowSystemDecors: ${it.message}")
                }
            return
        }
        val identity = Binder.clearCallingIdentity()
        try {
            val wms = ServiceManager.getService(Context.WINDOW_SERVICE) ?: return
            val settings = fieldWalk(wms, "mDisplayWindowSettings")
            val setLocked = settings?.javaClass?.methods?.firstOrNull { m ->
                m.name == "setShouldShowSystemDecorsLocked" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            if (setLocked != null && settings != null) {
                setLocked.invoke(settings, displayId, false)
                return
            }
            val internal = wms.javaClass.methods.firstOrNull { m ->
                m.name == "setShouldShowSystemDecorsInternalLocked" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            if (internal != null) {
                internal.invoke(wms, displayId, false)
                return
            }
            logDebug(
                SplitDisplayController.TAG,
                "systemDecors: no runtime setter; vdFlags omit SHOULD_SHOW id=$displayId",
            )
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "forceHideSystemDecors: ${e.message}")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun fieldWalk(obj: Any, name: String): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
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
}
