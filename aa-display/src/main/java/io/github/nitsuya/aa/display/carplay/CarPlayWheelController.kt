package io.github.nitsuya.aa.display.carplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay steering wheel (HID reports on the AirPlay event channel) → AADisplay actions.
 *
 * Same UX as Android Auto ([AaMainFragment] `STEERING_WHEEL_CONTROL`):
 *  - 短按下一曲/上一曲 → [CoreApi.pressKey]
 *  - **长按右（下一曲/快进）→ 交换分屏** ([CoreApi.swapSplitPanes])
 *  - **长按左（上一曲/快退）→ 应用栈 / Recents**
 *  - knob wheel tick → scroll focused pane
 *
 * Long-press fires at the hold threshold (not on release) so a deliberate hold cannot
 * also emit a short skip. MIB3 / Audi: physical right rocker often reports HID PREV
 * (invert → NEXT); left often reports REWIND(6). [invertMediaLeftRight] only swaps
 * NEXT↔PREV; REWIND/FF stay side-accurate (left=prev/recents, right=next/swap).
 */
object CarPlayWheelController {
    private val TAG = CarPlayConsts.TAG

    /** Event-channel HID device ids (DiPlay AirPlayHid uuid tails). */
    const val DEV_KNOB = 0
    const val DEV_MEDIA = 1
    const val DEV_TELEPHONY = 2

    // DiPlay / CSM AirPlayHid media usages (1-byte report).
    private const val MEDIA_PLAY = 1
    private const val MEDIA_PAUSE = 2
    private const val MEDIA_PLAY_PAUSE = 3
    private const val MEDIA_NEXT = 4
    private const val MEDIA_PREV = 5
    /** Audi MMI left rocker often reports REWIND (6), not NEXT (4). */
    private const val MEDIA_REWIND = 6
    private const val MEDIA_FF = 7

    // Knob button bits (DiPlay knob report byte 0); shared with [CarPlayEventChannel].
    const val KNOB_SELECT = 1
    const val KNOB_HOME = 2
    const val KNOB_BACK = 4
    /** Momentary wheel-tick code; [extra] carries the signed wheel delta. */
    const val KNOB_WHEEL = 16

    /**
     * Hold before long action. Higher than divider Recent (550ms): rocker ticks and
     * seek-style holds were too easy to fire swap / Recents at 550ms.
     */
    private const val LONG_PRESS_MS = 900L

    private val enabled = AtomicBoolean(false)
    private val invertMediaLr = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    /** CarPlay Host recents overlay hook (set by [CarPlayHostActivity]). */
    @Volatile
    var onRecentsToggle: (() -> Unit)? = null

    private val pressStartMs = ConcurrentHashMap<Int, Long>()
    /** Raw HID usage whose long-press action already ran (suppress short on release). */
    private val longFired = ConcurrentHashMap.newKeySet<Int>()
    private val longRunnables = ConcurrentHashMap<Int, Runnable>()

    /** Last media usage seen on press; release reports arrive with [code]/[extra] = 0. */
    @Volatile
    private var lastMediaUsage = 0

    fun setEnabled(
        live: Boolean,
        invertMediaLeftRight: Boolean = false,
        @Suppress("UNUSED_PARAMETER") context: Context? = null,
    ) {
        enabled.set(live)
        invertMediaLr.set(if (live) invertMediaLeftRight else false)
        if (!live) {
            clearPressState()
        }
    }

    private fun clearPressState() {
        for (r in longRunnables.values) handler.removeCallbacks(r)
        longRunnables.clear()
        pressStartMs.clear()
        longFired.clear()
        lastMediaUsage = 0
    }

    /** Media keys: [CoreApi.pressKey] → AvMediaArbiter target (idle Douyin must not steal). */
    private fun pressMediaKey(keyCode: Int) {
        runCatching { CoreApi.pressKey(keyCode) }
            .onFailure { e -> Log.w(TAG, "pressKey($keyCode) failed", e) }
    }

    /**
     * Event-channel HID report: [code] is the key (media usages, telephony, knob
     * bits / wheel), [pressed] its state, [extra] the raw value (wheel delta for
     * [KNOB_WHEEL]). Release reports arrive with [code] = 0 and [extra] = 0 — recover
     * the usage from [lastMediaUsage].
     */
    fun onHidKey(device: Int, code: Int, pressed: Boolean, extra: Int) {
        if (!enabled.get()) return
        when (device) {
            DEV_MEDIA -> handleMedia(code, pressed, extra)
            DEV_KNOB -> when (code) {
                KNOB_SELECT -> Log.i(TAG, "wheel knob select")
                KNOB_HOME -> Log.i(TAG, "wheel knob home")
                KNOB_BACK -> Log.i(TAG, "wheel knob back")
                KNOB_WHEEL -> if (extra != 0) scrollFocusedPane(extra)
                else -> Unit // button release
            }
            DEV_TELEPHONY -> Log.i(TAG, "wheel telephony code=$code pressed=$pressed (v1: log only)")
        }
    }

    /**
     * Map Audi-inverted HID NEXT/PREV onto AA logical next/prev.
     * REWIND/FF are already side-accurate on MMI (left=6, right stays PREV→NEXT via invert)
     * — do not swap them.
     */
    private fun logicalMedia(raw: Int): Int {
        if (!invertMediaLr.get()) return raw
        return when (raw) {
            MEDIA_NEXT -> MEDIA_PREV
            MEDIA_PREV -> MEDIA_NEXT
            else -> raw
        }
    }

    private fun isNextSide(key: Int): Boolean =
        key == MEDIA_NEXT || key == MEDIA_FF

    private fun isPrevSide(key: Int): Boolean =
        key == MEDIA_PREV || key == MEDIA_REWIND

    private fun mediaKeyCode(code: Int): Int = when (code) {
        MEDIA_PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
        MEDIA_PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
        MEDIA_PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        // Was hardcoded 130/129 (= RECORD/EJECT) — short press never skipped tracks.
        MEDIA_NEXT, MEDIA_FF -> KeyEvent.KEYCODE_MEDIA_NEXT
        MEDIA_PREV, MEDIA_REWIND -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> 0
    }

    private fun handleMedia(code: Int, pressed: Boolean, extra: Int) {
        if (pressed) {
            val key = if (extra != 0) extra else code
            if (key == 0) return
            lastMediaUsage = key
            pressStartMs[key] = System.currentTimeMillis()
            longFired.remove(key)
            longRunnables.remove(key)?.let { handler.removeCallbacks(it) }
            val logical = logicalMedia(key)
            // Fire long action while held so release cannot also emit a short skip.
            if (isNextSide(logical) || isPrevSide(logical)) {
                val r = Runnable {
                    if (!enabled.get()) return@Runnable
                    if (pressStartMs[key] == null) return@Runnable
                    if (!longFired.add(key)) return@Runnable
                    fireLongAction(key, logical, System.currentTimeMillis() - (pressStartMs[key] ?: 0L))
                }
                longRunnables[key] = r
                handler.postDelayed(r, LONG_PRESS_MS)
            }
            return
        }
        // Release: DiPlay sends usage=0; use the last pressed usage.
        val raw = when {
            extra != 0 -> extra
            code != 0 -> code
            lastMediaUsage != 0 -> lastMediaUsage
            else -> return
        }
        longRunnables.remove(raw)?.let { handler.removeCallbacks(it) }
        val start = pressStartMs.remove(raw) ?: return
        if (lastMediaUsage == raw) lastMediaUsage = 0
        val alreadyLong = longFired.remove(raw)
        if (alreadyLong) return
        val key = logicalMedia(raw)
        val heldMs = System.currentTimeMillis() - start
        if (heldMs < LONG_PRESS_MS) {
            val keyCode = mediaKeyCode(key)
            if (keyCode != 0) {
                pressMediaKey(keyCode)
                Log.i(
                    TAG,
                    "wheel media short raw=$raw logical=$key → pressKey($keyCode) " +
                        "held=${heldMs}ms invert=${invertMediaLr.get()}",
                )
            } else {
                Log.i(TAG, "wheel media short raw=$raw logical=$key ignored (no keycode)")
            }
        } else {
            // Threshold passed but runnable missed (looper stall) — fire on release.
            fireLongAction(raw, key, heldMs)
        }
    }

    private fun fireLongAction(raw: Int, key: Int, heldMs: Long) {
        if (isNextSide(key)) {
            runCatching { CoreApi.swapSplitPanes() }
            CarPlayInputBridge.primaryRatio =
                SplitPane.clampRatio(1f - CarPlayInputBridge.primaryRatio)
            Log.i(
                TAG,
                "wheel media next long → swap panes " +
                    "(raw=$raw logical=$key held=${heldMs}ms invert=${invertMediaLr.get()})",
            )
        } else if (isPrevSide(key)) {
            val cb = onRecentsToggle
            if (cb != null) {
                runCatching { cb() }
            } else {
                CarPlayRecentsOverlay.toggle()
            }
            Log.i(
                TAG,
                "wheel media prev long → recents toggle " +
                    "(raw=$raw logical=$key held=${heldMs}ms invert=${invertMediaLr.get()})",
            )
        } else {
            val keyCode = mediaKeyCode(key)
            if (keyCode != 0) {
                pressMediaKey(keyCode)
                Log.i(TAG, "wheel media long other raw=$raw → pressKey($keyCode)")
            } else {
                Log.i(TAG, "wheel media long raw=$raw logical=$key ignored")
            }
        }
    }

    /** Knob wheel tick → synthesized scroll on the focused pane center. */
    private fun scrollFocusedPane(wheelDelta: Int) {
        val pane = runCatching { CoreApi.getFocusedPane() }
            .getOrNull()
            .takeIf { it == SplitPane.PRIMARY || it == SplitPane.SECONDARY } ?: SplitPane.PRIMARY
        val w = CarPlayInputBridge.shellWidthPx.coerceAtLeast(1)
        val h = CarPlayInputBridge.shellHeightPx.coerceAtLeast(1)
        val ratio = CarPlayInputBridge.primaryRatio.coerceIn(SplitPane.MIN_RATIO, SplitPane.MAX_RATIO)
        // Pane center in shell coordinates: primary starts at 0, secondary at [ratio].
        val centerShare = if (pane == SplitPane.PRIMARY) ratio / 2f else (1f + ratio) / 2f
        val x = (centerShare * w).toInt().coerceIn(0, w - 1)
        val y = (h / 2).coerceIn(0, h - 1)
        runCatching {
            CoreApi.scrollPane(pane, x, y, wheelDelta, 0)
        }
    }
}
