package io.github.nitsuya.aa.display.ui.aa.split

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.UserHandle
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.argTypes
import com.github.kyuubiran.ezxhelper.utils.args
import com.github.kyuubiran.ezxhelper.utils.invokeMethod
import com.github.kyuubiran.ezxhelper.utils.newInstance
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.LastSplitStore
import io.github.nitsuya.aa.display.xposed.hook.AndroidHook
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import io.github.nitsuya.aa.display.xposed.util.Instances

internal class SplitLaunchRestore(private val c: SplitDisplayController) {

    internal val RESTORE_TOKEN = Any()
    internal val VERIFY_RESTORE_TOKEN = Any()
    internal val ENSURE_TOKEN = Any()

    internal var mPersistFirstScheduledAt = 0L

    internal val mDebouncedPersist = Runnable {
        mPersistFirstScheduledAt = 0L
        persistSnapshot(force = false, mirrorSettings = false)
    }

    internal val mDebouncedNotifyState = Runnable {
        if (refreshPanePackagesFromAtms()) {
            notifySplitStateChangedImmediate()
        }
    }

    fun shouldRestoreLastSplitOnConnect(): Boolean {
        // Always on — no settings toggle (pair + ratio restore when snapshot is valid).
        val snap = LastSplitStore.load(c.context.contentResolver) ?: return false
        return resolveLaunchComponent(snap.primaryPackage) != null &&
            resolveLaunchComponent(snap.secondaryPackage) != null
    }

    fun scheduleRestoreLastSplit() {
        c.mHandler.removeCallbacksAndMessages(RESTORE_TOKEN)
        // Next frame — no artificial 400ms wait before launching restored apps.
        c.mHandler.postAtTime({
            if (c.mIsDestroying) return@postAtTime
            restoreLastSplitNow()
        }, RESTORE_TOKEN, SystemClock.uptimeMillis())
    }

    fun restoreLastSplitNow() {
        val snap = LastSplitStore.load(c.context.contentResolver)
        if (snap == null) {
            c.notifySplitStateChanged()
            return
        }
        c.mSuppressReclaimUntil = SystemClock.uptimeMillis() + SplitDisplayController.SUPPRESS_RECLAIM_AFTER_RESTORE_MS
        c.mRatio = SplitPane.clampRatio(snap.primaryRatio)
        c.vd.resizePanesInternal("restore")
        val primaryOk = c.startActivityOnPane(snap.primaryPackage, 0, SplitPane.PRIMARY)
        val secondaryOk = c.startActivityOnPane(snap.secondaryPackage, 0, SplitPane.SECONDARY)
        log(
            SplitDisplayController.TAG,
            "restoreLastSplit primary=${snap.primaryPackage}:$primaryOk " +
                "secondary=${snap.secondaryPackage}:$secondaryOk ratio=${c.mRatio}"
        )
        c.notifySplitStateChanged()
        // Launch returning true only means startActivity was accepted — verify panes stuck.
        scheduleVerifyRestore(snap, attempt = 0)
    }

    fun scheduleVerifyRestore(snap: LastSplitStore.Snapshot, attempt: Int) {
        c.mHandler.removeCallbacksAndMessages(VERIFY_RESTORE_TOKEN)
        val delay = if (attempt == 0) {
            SplitDisplayController.RESTORE_VERIFY_DELAY_MS
        } else {
            SplitDisplayController.RESTORE_VERIFY_RETRY_MS
        }
        c.mHandler.postDelayed({
            if (c.mIsDestroying) return@postDelayed
            runVerifyRestore(snap, attempt)
        }, VERIFY_RESTORE_TOKEN, delay)
    }

    private fun runVerifyRestore(snap: LastSplitStore.Snapshot, attempt: Int) {
        if (attempt == 0) {
            c.mSuppressReclaimUntil = SystemClock.uptimeMillis() + SplitDisplayController.SUPPRESS_RECLAIM_MS
        }
        val primaryDisplay = c.primaryDisplayId
        val secondaryDisplay = c.secondaryDisplayId
        val primaryPresent = primaryDisplay != Display.INVALID_DISPLAY &&
            c.ownership.hasPackageOnDisplay(snap.primaryPackage, primaryDisplay)
        val secondaryPresent = secondaryDisplay != Display.INVALID_DISPLAY &&
            c.ownership.hasPackageOnDisplay(snap.secondaryPackage, secondaryDisplay)

        var retry = false
        if (!primaryPresent) {
            retry = retry || verifyRestorePaneMissing(
                snap.primaryPackage,
                SplitPane.PRIMARY,
                primaryDisplay,
                attempt,
            )
        }
        if (!secondaryPresent) {
            retry = retry || verifyRestorePaneMissing(
                snap.secondaryPackage,
                SplitPane.SECONDARY,
                secondaryDisplay,
                attempt,
            )
        }
        if (retry) {
            scheduleVerifyRestore(snap, attempt + 1)
            return
        }
        if (primaryPresent && secondaryPresent) {
            c.mPanePackages[SplitPane.PRIMARY] = snap.primaryPackage
            c.mPanePackages[SplitPane.SECONDARY] = snap.secondaryPackage
            persistSnapshot(force = true, mirrorSettings = true)
        }
        c.notifySplitStateChanged()
    }

    /**
     * @return true when restore settle may still be in progress and verify should retry (no picker yet).
     */
    private fun verifyRestorePaneMissing(
        packageName: String,
        pane: Int,
        displayId: Int,
        attempt: Int,
    ): Boolean {
        if (displayId == Display.INVALID_DISPLAY) return false
        if (c.ownership.hasPackageOnDisplay(packageName, displayId)) return false
        logDebug(
            SplitDisplayController.TAG,
            "restore verify: pane=$pane missing $packageName attempt=$attempt"
        )
        val ok = c.startActivityOnPane(packageName, 0, pane)
        if (c.ownership.hasPackageOnDisplay(packageName, displayId)) return false
        val canRetry = attempt + 1 < SplitDisplayController.MAX_RESTORE_VERIFY_ATTEMPTS
        if (canRetry) {
            logDebug(
                SplitDisplayController.TAG,
                "restore verify: pane=$pane still missing $packageName ok=$ok → retry"
            )
            return true
        }
        log(
            SplitDisplayController.TAG,
            "restore verify: pane=$pane failed $packageName ok=$ok → picker"
        )
        openPickerForPane(pane)
        return false
    }

    fun scheduleEnsurePanePackages(reason: String) {
        c.mHandler.removeCallbacksAndMessages(ENSURE_TOKEN)
        c.mHandler.postDelayed({
            if (c.mIsDestroying) return@postDelayed
            ensurePanePackages(reason)
        }, ENSURE_TOKEN, SplitDisplayController.ENSURE_PANES_DELAY_MS)
    }

    /**
     * Soft reconnect / surface restore: if remembered pane apps left the VDs, bring them back.
     * When both panes are vacant, fall back to last-split restore (or leave empty for picker).
     */
    fun ensurePanePackages(reason: String) {
        if (c.primaryDisplayId == Display.INVALID_DISPLAY) return
        // During restore/connect settle, do not treat empty ATMS as vacant (would wipe mPanePackages
        // and race restore over the restored left app).
        val settling = SystemClock.uptimeMillis() < c.mSuppressReclaimUntil
        if (!settling) {
            refreshPanePackagesFromAtms()
        }
        val primaryPkg = c.mPanePackages[SplitPane.PRIMARY]
        val secondaryPkg = c.mPanePackages[SplitPane.SECONDARY]
        if (!settling && primaryPkg.isNullOrBlank() && secondaryPkg.isNullOrBlank()) {
            logDebug(SplitDisplayController.TAG, "ensurePanes[$reason]: both empty → restore or idle")
            if (shouldRestoreLastSplitOnConnect()) {
                scheduleRestoreLastSplit()
            } else {
                c.notifySplitStateChanged()
            }
            return
        }
        var relaunched = false
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            val expected = c.mPanePackages[pane]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val displayId = c.input.displayIdFor(pane) ?: continue
            if (c.ownership.hasPackageOnDisplay(expected, displayId)) continue
            logDebug(SplitDisplayController.TAG, "ensurePanes[$reason]: relaunch $expected on pane=$pane")
            if (c.startActivityOnPane(expected, 0, pane)) {
                relaunched = true
            }
        }
        if (relaunched) {
            c.mSuppressReclaimUntil =
                maxOf(c.mSuppressReclaimUntil, SystemClock.uptimeMillis() + SplitDisplayController.SUPPRESS_RECLAIM_MS)
            c.notifySplitStateChanged()
        }
        SplitPresentationGuard.scheduleEvictForeignPresentations(c, "ensure-$reason")
    }

    fun openPickerForPane(pane: Int) {
        try {
            c.context.sendBroadcast(
                Intent(AABroadcastConst.ACTION_OPEN_SPLIT_PICKER).apply {
                    putExtra(AABroadcastConst.EXTRA_PANE, pane)
                }
            )
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "openPickerForPane failed pane=$pane:", e)
        }
    }

    fun notifySplitStateChangedImmediate() {
        try {
            c.context.sendBroadcast(
                Intent(AABroadcastConst.ACTION_SPLIT_STATE_CHANGED).apply {
                    putExtra(
                        AABroadcastConst.EXTRA_PRIMARY_PACKAGE,
                        c.mPanePackages[SplitPane.PRIMARY].orEmpty()
                    )
                    putExtra(
                        AABroadcastConst.EXTRA_SECONDARY_PACKAGE,
                        c.mPanePackages[SplitPane.SECONDARY].orEmpty()
                    )
                }
            )
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "notifySplitStateChanged failed: ${e.message}")
        }
    }

    fun scheduleNotifySplitState() {
        c.mHandler.removeCallbacks(mDebouncedNotifyState)
        c.mHandler.postDelayed(mDebouncedNotifyState, 180L)
    }

    fun launchOnDisplay(
        componentName: ComponentName,
        userId: Int,
        displayId: Int,
    ): Boolean {
        return try {
            AndroidHook.VdDensityPin.markPackageOnVirtualDisplay(
                componentName.packageName,
                displayId
            )
            c.context.invokeMethod(
                "startActivityAsUser",
                args(
                    Intent().apply {
                        component = componentName
                        `package` = componentName.packageName
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        putExtra("displayId", displayId)
                        // MULTIPLE_TASK: force a new root on the target VD when an old task
                        // still exists elsewhere; otherwise OEM task reuse ignores launchDisplayId.
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    },
                    android.app.ActivityOptions.makeBasic().apply {
                        launchDisplayId = displayId
                        try {
                            invokeMethod("setCallerDisplayId", args(displayId), argTypes(Integer.TYPE))
                        } catch (_: Throwable) {
                        }
                    }.toBundle(),
                    UserHandle::class.java.newInstance(
                        args(userId),
                        argTypes(Integer.TYPE)
                    )
                ),
                argTypes(Intent::class.java, Bundle::class.java, UserHandle::class.java)
            )
            c.ownership.trackPackage(componentName.packageName, userId)
            true
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "launchOnDisplay error display=$displayId:", e)
            false
        }
    }

    fun resolveLaunchComponent(packageName: String): ComponentName? {
        val pkg = packageName.trim().takeIf { it.isNotEmpty() } ?: return null
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            val pm = c.context.packageManager
            val ri = if (Build.VERSION.SDK_INT >= 33) {
                val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                pm.resolveActivity(intent, flags)
                    ?: pm.queryIntentActivities(intent, flags).firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_ALL)
                    ?: @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, PackageManager.MATCH_ALL).firstOrNull()
            } ?: return null
            val ai = ri.activityInfo ?: return null
            ComponentName(ai.packageName, ai.name)
        } catch (_: Throwable) {
            null
        }
    }

    fun schedulePersistSnapshot() {
        val now = SystemClock.uptimeMillis()
        if (mPersistFirstScheduledAt == 0L) {
            mPersistFirstScheduledAt = now
        }
        c.mHandler.removeCallbacks(mDebouncedPersist)
        val wait = if (now - mPersistFirstScheduledAt >= 8000L) {
            0L
        } else {
            SplitDisplayController.SNAPSHOT_DEBOUNCE_MS
        }
        c.mHandler.postDelayed(mDebouncedPersist, wait)
    }

    fun persistSnapshot(force: Boolean, mirrorSettings: Boolean) {
        refreshPanePackagesFromAtms()
        val primaryPkg = c.mPanePackages[SplitPane.PRIMARY]?.trim().orEmpty()
        val secondaryPkg = c.mPanePackages[SplitPane.SECONDARY]?.trim().orEmpty()
        if (primaryPkg.isEmpty() || secondaryPkg.isEmpty() || primaryPkg == secondaryPkg) {
            if (force) {
                logDebug(SplitDisplayController.TAG, "persist skip: incomplete panes primary=$primaryPkg secondary=$secondaryPkg")
            }
            return
        }
        val snap = LastSplitStore.Snapshot(
            primaryPackage = primaryPkg,
            secondaryPackage = secondaryPkg,
            primaryRatio = c.mRatio,
        )
        LastSplitStore.save(snap, c.context.contentResolver, mirrorSettings = mirrorSettings || force)
    }

    /** Re-walk ATMS tops so snapshots / empty-pane state match reality after external closes. */
    fun refreshPanePackagesFromAtms(): Boolean {
        var changed = false
        val settling = SystemClock.uptimeMillis() < c.mSuppressReclaimUntil
        val identity = Binder.clearCallingIdentity()
        try {
            for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
                val displayId = c.input.displayIdFor(pane) ?: continue
                val tasks = tryOrNull {
                    Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                }.orEmpty()
                val topPkg = tasks.firstOrNull { info ->
                    val pkg = info.topActivity?.packageName
                    !pkg.isNullOrBlank() && !SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)
                }?.topActivity?.packageName
                val next = when {
                    topPkg != null -> topPkg
                    // During restore/connect settle, keep bookkeeping through empty or
                    // chrome-only ATMS walks (SecondaryDisplayLauncher flash).
                    settling -> c.mPanePackages[pane]
                    // Not settling: empty or only launcher/systemui → vacant.
                    else -> null
                }
                if (c.mPanePackages[pane] != next) {
                    c.mPanePackages[pane] = next
                    changed = true
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return changed
    }
}
