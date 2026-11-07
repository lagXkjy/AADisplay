package io.github.nitsuya.aa.display.aap

import aap_protobuf.service.inputsource.message.KeyEventOuterClass.KeyEvent
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.carplay.CarPlayRecentsOverlay
import java.util.concurrent.ConcurrentHashMap

/**
 * AAP [KeyEvent] → AADisplay actions, matching [io.github.nitsuya.aa.display.carplay.CarPlayWheelController]:
 * short MEDIA_* → [CoreApi.pressKey] + [AudioManager.dispatchMediaKeyEvent];
 * long NEXT/FF → [CoreApi.swapSplitPanes];
 * long PREV/REWIND → [CarPlayRecentsOverlay.toggle].
 */
class AapInputKeys(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val pressStartMs = ConcurrentHashMap<Int, Long>()
    private val longFired = ConcurrentHashMap.newKeySet<Int>()
    private val longRunnables = ConcurrentHashMap<Int, Runnable>()

    fun reset() {
        for (r in longRunnables.values) handler.removeCallbacks(r)
        longRunnables.clear()
        pressStartMs.clear()
        longFired.clear()
    }

    fun onKeyEvent(ev: KeyEvent) {
        for (k in ev.keysList) {
            val code = k.keycode
            if (k.down) onDown(code) else onUp(code)
        }
    }

    private fun onDown(keyCode: Int) {
        when (keyCode) {
            AndroidKeyEvent.KEYCODE_BACK,
            AndroidKeyEvent.KEYCODE_HOME,
            -> return // handled on up only
            AndroidKeyEvent.KEYCODE_MEDIA_NEXT,
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS,
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND,
            -> {
                pressStartMs[keyCode] = System.currentTimeMillis()
                longFired.remove(keyCode)
                longRunnables.remove(keyCode)?.let { handler.removeCallbacks(it) }
                val r = Runnable {
                    if (pressStartMs[keyCode] == null) return@Runnable
                    if (!longFired.add(keyCode)) return@Runnable
                    fireLong(keyCode, System.currentTimeMillis() - (pressStartMs[keyCode] ?: 0L))
                }
                longRunnables[keyCode] = r
                handler.postDelayed(r, LONG_PRESS_MS)
            }
            else -> {
                if (isMediaKey(keyCode)) {
                    pressStartMs[keyCode] = System.currentTimeMillis()
                }
            }
        }
    }

    private fun onUp(keyCode: Int) {
        when (keyCode) {
            AndroidKeyEvent.KEYCODE_BACK,
            AndroidKeyEvent.KEYCODE_HOME,
            -> {
                pressMediaOrNav(keyCode)
                return
            }
        }
        longRunnables.remove(keyCode)?.let { handler.removeCallbacks(it) }
        val start = pressStartMs.remove(keyCode) ?: run {
            if (isMediaKey(keyCode)) pressMediaOrNav(keyCode)
            return
        }
        val alreadyLong = longFired.remove(keyCode)
        if (alreadyLong) return
        val held = System.currentTimeMillis() - start
        if (held < LONG_PRESS_MS) {
            pressMediaOrNav(keyCode)
            Log.i(AapConsts.TAG, "AapInput short key=$keyCode held=${held}ms")
        } else if (isNextSide(keyCode) || isPrevSide(keyCode)) {
            fireLong(keyCode, held)
        } else {
            pressMediaOrNav(keyCode)
        }
    }

    private fun fireLong(keyCode: Int, heldMs: Long) {
        when {
            isNextSide(keyCode) -> {
                runCatching { CoreApi.swapSplitPanes() }
                Log.i(AapConsts.TAG, "AapInput long NEXT → swap held=${heldMs}ms")
            }
            isPrevSide(keyCode) -> {
                CarPlayRecentsOverlay.toggle()
                Log.i(AapConsts.TAG, "AapInput long PREV → recents held=${heldMs}ms")
            }
            else -> pressMediaOrNav(keyCode)
        }
    }

    private fun pressMediaOrNav(keyCode: Int) {
        runCatching { CoreApi.pressKey(keyCode) }
        if (!isMediaKey(keyCode)) return
        runCatching {
            val am = app.getSystemService(AudioManager::class.java) ?: return@runCatching
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(
                AndroidKeyEvent(now, now, AndroidKeyEvent.ACTION_DOWN, keyCode, 0),
            )
            am.dispatchMediaKeyEvent(
                AndroidKeyEvent(now, now, AndroidKeyEvent.ACTION_UP, keyCode, 0),
            )
        }.onFailure { e ->
            Log.w(AapConsts.TAG, "dispatchMediaKeyEvent($keyCode) failed", e)
        }
    }

    companion object {
        private const val LONG_PRESS_MS = 550L

        private fun isNextSide(code: Int): Boolean =
            code == AndroidKeyEvent.KEYCODE_MEDIA_NEXT ||
                code == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD

        private fun isPrevSide(code: Int): Boolean =
            code == AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                code == AndroidKeyEvent.KEYCODE_MEDIA_REWIND

        private fun isMediaKey(code: Int): Boolean = when (code) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY,
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            AndroidKeyEvent.KEYCODE_MEDIA_NEXT,
            AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS,
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND,
            AndroidKeyEvent.KEYCODE_MEDIA_STOP,
            AndroidKeyEvent.KEYCODE_VOLUME_UP,
            AndroidKeyEvent.KEYCODE_VOLUME_DOWN,
            AndroidKeyEvent.KEYCODE_VOLUME_MUTE,
            AndroidKeyEvent.KEYCODE_MUTE,
            -> true
            else -> false
        }
    }
}
