package io.github.nitsuya.aa.display.xposed.carplay

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * cgroup top-app boost for the AADisplay app while a CarPlay Source session is live.
 *
 * In CarPlay-only mode the app renders the split shell and pumps video/audio/NowPlaying;
 * at background importance the cgroup scheduler starves the encoder (observed 2-4 fps
 * supply in background vs 11-15 foreground — docs/OPEN_CLOSE_LAG.md). system_server moves
 * the app pid into the top-app cgroup while CarPlay is live and back to foreground on
 * stop; a short watchdog re-asserts (some OEMs reparent into `cpuset:/abnormal` within
 * seconds if only `cpu` is boosted).
 *
 * Best-effort: any failure (OEM cgroup layout, SELinux) only disables the boost — the
 * session keeps running without it. This is a system capability (like VirtualDisplay
 * creation / the audio capture tap), NOT part of the CarPlay protocol stack, which stays
 * in the app process under `carplay/` (EXECUTION.md §14 / §15 #17).
 */
object CarPlayProcessBoost {
    private const val TAG = "AAD_CpProcBoost"

    private const val TARGET_PACKAGE = "io.github.nitsuya.aa.display"
    /** OEMs may reparent to `cpuset:/abnormal` within a few seconds; 30s was too slow. */
    private const val WATCHDOG_PERIOD_MS = 3_000L
    private const val CGROUP_V2_ROOT = "/sys/fs/cgroup"

    private val enabled = AtomicBoolean(false)
    private val installed = AtomicBoolean(false)
    private var activityManager: ActivityManager? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile
    private var lastAppliedPid = -1
    @Volatile
    private var logOnceMissing = false
    @Volatile
    private var loggedControllers = false

    /** Idempotent install from [io.github.nitsuya.aa.display.xposed.CoreManagerService.systemReady]. */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        activityManager = context.getSystemService(ActivityManager::class.java)
        val t = HandlerThread("aad-cp-proc-boost").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        log(TAG, "installed (idle)")
    }

    /** Driven by [io.github.nitsuya.aa.display.xposed.ICoreManager.reportCarPlaySessionLive]. */
    fun setBoost(live: Boolean) {
        if (live == enabled.get()) return
        enabled.set(live)
        val h = handler ?: return
        if (live) {
            applyNow()
            scheduleTick(h)
        } else {
            h.removeCallbacksAndMessages(null)
            if (lastAppliedPid != -1) {
                moveTo("foreground", lastAppliedPid)
                log(TAG, "boost off pid=$lastAppliedPid → foreground")
                lastAppliedPid = -1
            }
        }
    }

    private fun scheduleTick(h: Handler) {
        h.postDelayed(
            {
                if (!enabled.get()) return@postDelayed
                applyNow()
                scheduleTick(h)
            },
            WATCHDOG_PERIOD_MS,
        )
    }

    private fun applyNow() {
        val am = activityManager ?: return
        val pid = try {
            am.runningAppProcesses
                ?.firstOrNull { it.processName == TARGET_PACKAGE }?.pid ?: -1
        } catch (e: Throwable) {
            -1
        }
        if (pid == -1) {
            if (!logOnceMissing) {
                logOnceMissing = true
                log(TAG, "app process not found — boost skipped (app restarted?)")
            }
            return
        }
        logOnceMissing = false
        val moved = moveTo("top-app", pid)
        if (moved && pid != lastAppliedPid) {
            log(TAG, "boost on pid=$pid → top-app (cpu+cpuset+freezer)")
        }
        lastAppliedPid = pid
    }

    /**
     * Writes [pid] into the given Android cgroup group across the controllers that matter
     * for encode throughput on hybrid OEM layouts:
     * - `cpu` (`/dev/cpuctl` or v2) — share / weight
     * - `cpuset` (`/dev/cpuset`) — some OEMs park FGS in `abnormal` (few CPUs only); must
     *   reassert `top-app` or supply sticks at ~15 fps with 400ms gaps
     * - `freezer` — leave OEM `abnormal` freezer so the process is not periodically frozen
     *
     * Best-effort: success on any primary controller counts; missing paths are ignored.
     */
    private fun moveTo(group: String, pid: Int): Boolean {
        val candidates = buildList {
            add(File("$CGROUP_V2_ROOT/$group/cgroup.procs"))
            add(File("/dev/cpuctl/$group/tasks"))
            add(File("/dev/cpuctl/$group/cgroup.procs"))
            add(File("/dev/cpuset/$group/tasks"))
            add(File("/dev/cpuset/$group/cgroup.procs"))
            // Root freezer clears OEM "abnormal" membership; foreground freeze group is rare.
            if (group == "top-app") {
                add(File("/dev/freezer/tasks"))
                add(File("/dev/freezer/cgroup.procs"))
            } else {
                add(File("/dev/freezer/$group/tasks"))
                add(File("/dev/freezer/$group/cgroup.procs"))
            }
        }
        var anyOk = false
        var lastErr: Throwable? = null
        val okPaths = mutableListOf<String>()
        for (file in candidates) {
            if (!file.exists()) continue
            try {
                file.writeText("$pid\n")
                anyOk = true
                okPaths.add(file.path)
            } catch (e: Throwable) {
                lastErr = e
            }
        }
        if (!anyOk && !logOnceMissing) {
            val paths = candidates.joinToString { it.path }
            log(
                TAG,
                "cgroup $group write failed (boost unavailable) tried=[$paths] err=${lastErr?.message}",
            )
            logOnceMissing = true
        } else if (anyOk && !loggedControllers) {
            loggedControllers = true
            logDebug(TAG, "cgroup $group wrote pid=$pid via ${okPaths.joinToString()}")
        }
        return anyOk
    }
}
