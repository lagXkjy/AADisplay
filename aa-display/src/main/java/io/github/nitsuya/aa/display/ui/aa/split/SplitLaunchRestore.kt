package io.github.nitsuya.aa.display.ui.aa.split

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.SystemClock
import android.view.Display
import com.github.kyuubiran.ezxhelper.utils.tryOrNull
import io.github.nitsuya.aa.display.util.AABroadcastConst
import io.github.nitsuya.aa.display.util.AaSystemBroadcast
import io.github.nitsuya.aa.display.util.LastSplitStore
import io.github.nitsuya.aa.display.util.PmResolveCache
import io.github.nitsuya.aa.display.xposed.hook.VdDensityPin
import io.github.nitsuya.aa.display.xposed.util.log
import io.github.nitsuya.aa.display.xposed.util.logDebug
import io.github.nitsuya.aa.display.xposed.util.Instances

internal class SplitLaunchRestore(private val c: SplitDisplayController) {

    private enum class SettlementPhase { IDLE, RESTORING, VERIFYING }

    private var settlementPhase = SettlementPhase.IDLE

    internal val RESTORE_TOKEN = Any()
    internal val VERIFY_RESTORE_TOKEN = Any()
    internal val ENSURE_TOKEN = Any()

    internal var mPersistFirstScheduledAt = 0L

    internal val mDebouncedPersist = Runnable {
        mPersistFirstScheduledAt = 0L
        persistSnapshot(force = false, logSettingsFailures = false)
    }

    /** ATMS settle: reclaim + refresh pane bookkeeping, then notify AA UI + Recent. */
    internal val mDebouncedAtmsSettle = Runnable {
        c.ownership.reclaimOwnedPackages("stack")
        if (refreshPanePackagesFromAtms()) {
            notifySplitStateChangedImmediate()
        }
        try {
            AaSystemBroadcast.toAaDisplay(
                c.context,
                Intent(AABroadcastConst.ACTION_RECENT_TASK_DIRTY),
            )
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "recent dirty broadcast failed: ${e.message}")
        }
    }

    fun shouldRestoreLastSplitOnConnect(): Boolean {
        // Always on — no settings toggle (pair + ratio restore when snapshot is valid).
        val snap = LastSplitStore.load(c.context.contentResolver) ?: return false
        return resolveLaunchComponent(snap.primaryPackage) != null &&
            resolveLaunchComponent(snap.secondaryPackage) != null
    }

    fun scheduleRestoreLastSplit() {
        if (settlementPhase != SettlementPhase.IDLE) {
            logDebug(
                SplitDisplayController.TAG,
                "scheduleRestoreLastSplit skipped phase=$settlementPhase",
            )
            return
        }
        settlementPhase = SettlementPhase.RESTORING
        c.mHandler.removeCallbacksAndMessages(RESTORE_TOKEN)
        // Next frame — no artificial 400ms wait before launching restored apps.
        c.mHandler.postAtTime({
            if (c.mIsDestroying) return@postAtTime
            restoreLastSplitNow()
        }, RESTORE_TOKEN, SystemClock.uptimeMillis())
    }

    /** Bookkeeping + broadcast snapshot fronts before ATMS launch (connect-settle poll). */
    fun prefillRestoreFromSnapshot(): LastSplitStore.Snapshot? {
        val snap = LastSplitStore.load(c.context.contentResolver) ?: return null
        if (resolveLaunchComponent(snap.primaryPackage) == null ||
            resolveLaunchComponent(snap.secondaryPackage) == null
        ) {
            return null
        }
        val (primaryStack, secondaryStack) = filteredRestoreStacks(snap)
        c.stacks.setStackBottomToTop(SplitPane.PRIMARY, primaryStack)
        c.stacks.setStackBottomToTop(SplitPane.SECONDARY, secondaryStack)
        logDebug(
            SplitDisplayController.TAG,
            "prefillRestore primary=${c.mPanePackages[SplitPane.PRIMARY]} " +
                "secondary=${c.mPanePackages[SplitPane.SECONDARY]} " +
                "pStack=$primaryStack sStack=$secondaryStack"
        )
        return snap
    }

    /** True when both AA VDs have no live user root (soft reconnect may need restore). */
    fun bothPanesVacantOnDisplays(): Boolean {
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            val displayId = c.input.displayIdFor(pane) ?: return false
            val tasks = c.ownership.normalizeRootTasksBottomToTop(
                tryOrNull {
                    Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                }.orEmpty()
            )
            val hasUser = tasks.any { info ->
                val pkg = info.topActivity?.packageName
                !pkg.isNullOrBlank() && !SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)
            }
            if (hasUser) return false
        }
        return true
    }

    private fun filteredRestoreStacks(
        snap: LastSplitStore.Snapshot,
    ): Pair<List<String>, List<String>> {
        val primaryStack = snap.primaryPackagesBottomToTop()
            .filter { it.trim().isNotEmpty() && it !in c.mExplicitlyClosedPackages }
        val secondaryStack = snap.secondaryPackagesBottomToTop()
            .filter { it.trim().isNotEmpty() && it !in c.mExplicitlyClosedPackages }
        return primaryStack to secondaryStack
    }

    fun restoreLastSplitNow() {
        val snap = prefillRestoreFromSnapshot()
        if (snap == null) {
            settlementPhase = SettlementPhase.IDLE
            c.notifySplitStateChanged()
            return
        }
        settlementPhase = SettlementPhase.RESTORING
        c.mSuppressReclaimUntil = SystemClock.uptimeMillis() + SplitDisplayController.SUPPRESS_RECLAIM_AFTER_RESTORE_MS
        val snapRatio = SplitPane.clampRatio(snap.primaryRatio)
        val liveRatio = SplitPane.clampRatio(c.mRatio)
        // Soft reconnect: controller may still hold the live ratio while the snapshot lagged.
        c.mRatio = if (
            c.mPrimary != null && c.mSecondary != null &&
            kotlin.math.abs(snapRatio - liveRatio) >= 0.01f
        ) {
            liveRatio
        } else {
            snapRatio
        }
        c.mRatioBeforeFullscreen = c.mRatio
        c.mFullscreenPane = SplitPane.FULLSCREEN_NONE
        c.vd.resizePanesInternal("restore")
        val (primaryStack, secondaryStack) = filteredRestoreStacks(snap)
        c.notifySplitStateChanged()
        logDebug(
            SplitDisplayController.TAG,
            "restoreLastSplitNow start pStack=$primaryStack sStack=$secondaryStack"
        )
        var primaryOk = false
        for (pkg in primaryStack.dropLast(1)) {
            primaryOk = c.startActivityOnPane(pkg, 0, SplitPane.PRIMARY) || primaryOk
        }
        var secondaryOk = false
        for (pkg in secondaryStack.dropLast(1)) {
            secondaryOk = c.startActivityOnPane(pkg, 0, SplitPane.SECONDARY) || secondaryOk
        }
        val primaryFront = primaryStack.lastOrNull()
        val secondaryFront = secondaryStack.lastOrNull()
        when {
            primaryFront != null && secondaryFront != null -> {
                launchRestoreFrontsParallel(primaryFront, secondaryFront) { pOk, sOk ->
                    finishRestoreLastSplit(
                        snap,
                        primaryStack,
                        secondaryStack,
                        primaryOk || pOk,
                        secondaryOk || sOk,
                    )
                }
            }
            primaryFront != null -> {
                primaryOk = c.startActivityOnPane(primaryFront, 0, SplitPane.PRIMARY) || primaryOk
                finishRestoreLastSplit(snap, primaryStack, secondaryStack, primaryOk, secondaryOk)
            }
            secondaryFront != null -> {
                secondaryOk = c.startActivityOnPane(secondaryFront, 0, SplitPane.SECONDARY) || secondaryOk
                finishRestoreLastSplit(snap, primaryStack, secondaryStack, primaryOk, secondaryOk)
            }
            else -> finishRestoreLastSplit(snap, primaryStack, secondaryStack, false, false)
        }
    }

    /**
     * Cold-start both pane fronts on worker threads so ATMS is not serialized on [mHandler].
     * Bookkeeping is already seeded via [prefillRestoreFromSnapshot].
     */
    private fun launchRestoreFrontsParallel(
        primaryFront: String,
        secondaryFront: String,
        onComplete: (primaryOk: Boolean, secondaryOk: Boolean) -> Unit,
    ) {
        val primaryDisplay = c.input.displayIdFor(SplitPane.PRIMARY)
        val secondaryDisplay = c.input.displayIdFor(SplitPane.SECONDARY)
        val primaryComp = resolveLaunchComponent(primaryFront)
        val secondaryComp = resolveLaunchComponent(secondaryFront)
        if (primaryDisplay == null || secondaryDisplay == null ||
            primaryComp == null || secondaryComp == null
        ) {
            c.mHandler.post { onComplete(false, false) }
            return
        }
        val results = BooleanArray(2)
        val latch = java.util.concurrent.CountDownLatch(2)
        fun launchPane(
            threadName: String,
            pkg: String,
            component: ComponentName,
            displayId: Int,
            slot: Int,
        ) {
            Thread({
                try {
                    c.ownership.removePackageTasksOnDisplay(pkg, displayId)
                    results[slot] = launchOnDisplay(component, 0, displayId)
                } catch (e: Throwable) {
                    log(SplitDisplayController.TAG, "restore parallel launch failed pkg=$pkg:", e)
                    results[slot] = false
                } finally {
                    latch.countDown()
                }
            }, threadName).start()
        }
        launchPane("AADisplay-restore-P", primaryFront, primaryComp, primaryDisplay, 0)
        launchPane("AADisplay-restore-S", secondaryFront, secondaryComp, secondaryDisplay, 1)
        Thread({
            try {
                latch.await()
            } catch (_: InterruptedException) {
            }
            c.mHandler.post {
                if (c.mIsDestroying) return@post
                logDebug(
                    SplitDisplayController.TAG,
                    "restore parallel fronts primary=$primaryFront:${results[0]} " +
                        "secondary=$secondaryFront:${results[1]}"
                )
                onComplete(results[0], results[1])
            }
        }, "AADisplay-restore-wait").start()
    }

    private fun finishRestoreLastSplit(
        snap: LastSplitStore.Snapshot,
        primaryStack: List<String>,
        secondaryStack: List<String>,
        primaryOk: Boolean,
        secondaryOk: Boolean,
    ) {
        c.stacks.setStackBottomToTop(SplitPane.PRIMARY, primaryStack)
        c.stacks.setStackBottomToTop(SplitPane.SECONDARY, secondaryStack)
        primaryStack.lastOrNull()?.let { pkg ->
            c.mExplicitlyClosedPackages.remove(pkg)
            c.ownership.markOwnership(pkg, c.input.displayIdFor(SplitPane.PRIMARY) ?: return@let)
        }
        secondaryStack.lastOrNull()?.let { pkg ->
            c.mExplicitlyClosedPackages.remove(pkg)
            c.ownership.markOwnership(pkg, c.input.displayIdFor(SplitPane.SECONDARY) ?: return@let)
        }
        val promotePanes = buildList {
            if (primaryStack.isNotEmpty()) add(SplitPane.PRIMARY)
            if (secondaryStack.isNotEmpty()) add(SplitPane.SECONDARY)
        }
        if (promotePanes.isNotEmpty()) {
            c.ownership.promoteStackFronts(promotePanes, settleAv = false)
        }
        if (SplitPane.isFullscreenPane(snap.fullscreenPane)) {
            c.setSplitFullscreen(snap.fullscreenPane)
        }
        c.buriedPlayback.scheduleEnforceSingleSounder("restore")
        log(
            SplitDisplayController.TAG,
            "restoreLastSplit primary=${snap.primaryPackage}:$primaryOk " +
                "secondary=${snap.secondaryPackage}:$secondaryOk " +
                "pStack=$primaryStack sStack=$secondaryStack ratio=${c.mRatio} " +
                "fullscreen=${c.mFullscreenPane}"
        )
        c.notifySplitStateChanged()
        settlementPhase = SettlementPhase.VERIFYING
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
        val wantPrimary = snap.primaryPackage.trim().isNotEmpty() &&
            snap.primaryPackage !in c.mExplicitlyClosedPackages
        val wantSecondary = snap.secondaryPackage.trim().isNotEmpty() &&
            snap.secondaryPackage !in c.mExplicitlyClosedPackages
        val primaryPresent = !wantPrimary || (
            primaryDisplay != Display.INVALID_DISPLAY &&
                c.ownership.hasPackageOnDisplay(snap.primaryPackage, primaryDisplay)
            )
        val secondaryPresent = !wantSecondary || (
            secondaryDisplay != Display.INVALID_DISPLAY &&
                c.ownership.hasPackageOnDisplay(snap.secondaryPackage, secondaryDisplay)
            )

        var retry = false
        if (wantPrimary && !primaryPresent) {
            retry = retry || verifyRestorePaneMissing(
                snap.primaryPackage,
                SplitPane.PRIMARY,
                primaryDisplay,
                attempt,
            )
        }
        if (wantSecondary && !secondaryPresent) {
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
            // Prefer snapshot order but drop ghost buried entries that never landed.
            val primaryStack = snap.primaryPackagesBottomToTop()
                .filter { it !in c.mExplicitlyClosedPackages }
            val secondaryStack = snap.secondaryPackagesBottomToTop()
                .filter { it !in c.mExplicitlyClosedPackages }
            trimStackToDisplayAlive(SplitPane.PRIMARY, primaryStack)
            trimStackToDisplayAlive(SplitPane.SECONDARY, secondaryStack)
            if (wantPrimary) {
                c.ownership.promoteStackFronts(listOf(SplitPane.PRIMARY), settleAv = false)
            }
            if (wantSecondary) {
                c.ownership.promoteStackFronts(listOf(SplitPane.SECONDARY), settleAv = false)
            }
            c.buriedPlayback.scheduleEnforceSingleSounder("restore-verify")
            persistSnapshot(force = true, logSettingsFailures = true)
        }
        settlementPhase = SettlementPhase.IDLE
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
        if (packageName.trim() in c.mExplicitlyClosedPackages) return false
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

    /** Soft reconnect: early + late ensure — WM/ExtraDisplayController settle asynchronously. */
    fun scheduleReconnectEnsurePasses() {
        scheduleEnsurePanePackages("reconnect")
        c.mHandler.postDelayed({
            if (c.mIsDestroying) return@postDelayed
            ensurePanePackages("reconnect-late")
        }, ENSURE_TOKEN, 1200L)
    }

    private fun isSoftReconnectReason(reason: String): Boolean = SplitPane.isSoftReconnectReason(reason)

    /**
     * Soft reconnect: one pane may look vacant in ATMS while the durable snapshot is still valid
     * (common when only the left app left the VD during AA disconnect / VD resize).
     */
    private fun backfillVacantPanesFromSnapshot(reason: String) {
        val snap = LastSplitStore.load(c.context.contentResolver) ?: return
        data class PaneSnap(val pane: Int, val front: String, val stack: List<String>)
        for (entry in listOf(
            PaneSnap(SplitPane.PRIMARY, snap.primaryPackage, snap.primaryPackagesBottomToTop()),
            PaneSnap(SplitPane.SECONDARY, snap.secondaryPackage, snap.secondaryPackagesBottomToTop()),
        )) {
            val current = c.mPanePackages[entry.pane]?.trim()?.takeIf { it.isNotEmpty() }
            if (current != null) continue
            val other = if (entry.pane == SplitPane.PRIMARY) SplitPane.SECONDARY else SplitPane.PRIMARY
            val otherPkgs = c.stacks.packagesBottomToTop(other).toSet()
            // Drop packages already owned by the healthy other pane so setStackBottomToTop
            // does not strip them via cross-pane exclusivity.
            val filtered = entry.stack.filter { pkg ->
                val p = pkg.trim()
                p.isNotEmpty() && p !in otherPkgs && p !in c.mExplicitlyClosedPackages
            }
            val pkg = (filtered.lastOrNull() ?: entry.front).trim().takeIf { it.isNotEmpty() }
                ?: continue
            if (pkg in otherPkgs || pkg in c.mExplicitlyClosedPackages) continue
            if (resolveLaunchComponent(pkg) == null) continue
            logDebug(
                SplitDisplayController.TAG,
                "ensurePanes[$reason]: backfill pane=${entry.pane} from snapshot pkg=$pkg",
            )
            val stackToApply = if (filtered.isNotEmpty()) filtered else listOf(pkg)
            c.stacks.setStackBottomToTop(entry.pane, stackToApply)
            c.mPanePackages[entry.pane] = pkg
            if (c.stacks.front(other) != null) {
                c.ownership.promoteStackFronts(listOf(other))
            }
        }
    }

    /**
     * After restore verify: apply [desiredBottomToTop] then drop packages with no live root
     * on this pane's display (ghost buried entries from a partial restore).
     */
    private fun trimStackToDisplayAlive(pane: Int, desiredBottomToTop: List<String>) {
        val displayId = c.input.displayIdFor(pane) ?: return
        if (displayId == Display.INVALID_DISPLAY) return
        val tasks = c.ownership.normalizeRootTasksBottomToTop(
            tryOrNull {
                Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
            }.orEmpty()
        )
        val alive = c.ownership.snapshotUserRootTasks(tasks)
            .mapNotNull { it.packageName?.trim()?.takeIf { p -> p.isNotEmpty() } }
            .toSet()
        val filtered = desiredBottomToTop
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in alive }
        if (filtered.isNotEmpty()) {
            c.stacks.setStackBottomToTop(pane, filtered)
        } else {
            c.stacks.trimToAlive(pane, alive)
        }
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
        if (isSoftReconnectReason(reason)) {
            backfillVacantPanesFromSnapshot(reason)
        }
        val primaryPkg = c.mPanePackages[SplitPane.PRIMARY]
        val secondaryPkg = c.mPanePackages[SplitPane.SECONDARY]
        if (!settling && primaryPkg.isNullOrBlank() && secondaryPkg.isNullOrBlank()) {
            logDebug(SplitDisplayController.TAG, "ensurePanes[$reason]: both empty → restore or idle")
            if (shouldRestoreLastSplitOnConnect() && settlementPhase == SettlementPhase.IDLE) {
                scheduleRestoreLastSplit()
            } else {
                c.notifySplitStateChanged()
            }
            return
        }
        var relaunched = false
        for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
            val stackPkgs = c.stacks.packagesBottomToTop(pane).ifEmpty {
                listOfNotNull(c.mPanePackages[pane]?.trim()?.takeIf { it.isNotEmpty() })
            }
            if (stackPkgs.isEmpty()) continue
            val displayId = c.input.displayIdFor(pane) ?: continue
            // Capture intentional front before any startActivityOnPane (which moveToTop-s).
            val stackFront = c.stacks.front(pane)?.trim()?.takeIf { it.isNotEmpty() }
                ?: c.mPanePackages[pane]?.trim()?.takeIf { it.isNotEmpty() }
                ?: stackPkgs.lastOrNull()
            for (pkg in stackPkgs) {
                if (pkg.trim() in c.mExplicitlyClosedPackages) continue
                val isFront = pkg == stackFront
                if (c.ownership.shouldSkipRelaunchOnDisplay(pkg, displayId, reason, isFront)) {
                    continue
                }
                // Soft-reconnect splash: bring alone is a no-op — drop then cold start.
                if (isFront &&
                    isSoftReconnectReason(reason) &&
                    c.ownership.hasPackageOnDisplay(pkg, displayId) &&
                    c.ownership.isPackageFrontStaleOnReconnect(pkg, displayId)
                ) {
                    logDebug(
                        SplitDisplayController.TAG,
                        "ensurePanes[$reason]: stale front $pkg on pane=$pane → cold relaunch",
                    )
                    c.ownership.removePackageTasksOnDisplay(pkg, displayId)
                } else if (c.ownership.hasPackageOnDisplay(pkg, displayId)) {
                    logDebug(
                        SplitDisplayController.TAG,
                        "ensurePanes[$reason]: order drift $pkg on pane=$pane front=$isFront → bring",
                    )
                }
                logDebug(SplitDisplayController.TAG, "ensurePanes[$reason]: relaunch $pkg on pane=$pane")
                if (c.startActivityOnPane(pkg, 0, pane)) {
                    relaunched = true
                }
                // Buried relaunch lands as front — restore intentional picture immediately.
                if (!isFront && !stackFront.isNullOrBlank()) {
                    c.stacks.moveToTop(pane, stackFront)
                    c.ownership.promoteStackFronts(listOf(pane), settleAv = false)
                }
            }
            // Final front + demote buried for picture; sticky Av pause if front is PLAYING.
            if (!stackFront.isNullOrBlank()) {
                c.stacks.moveToTop(pane, stackFront)
                c.ownership.promoteStackFronts(listOf(pane), settleAv = false)
            }
            c.ownership.enforceStackFrontAudio(pane)
        }
        // Cross-pane / multi-Av ensure: only one sounder after relaunch settle.
        c.buriedPlayback.scheduleEnforceSingleSounder("ensure-$reason")
        if (relaunched) {
            c.mSuppressReclaimUntil =
                maxOf(c.mSuppressReclaimUntil, SystemClock.uptimeMillis() + SplitDisplayController.SUPPRESS_RECLAIM_MS)
            c.notifySplitStateChanged()
        }
        SplitPresentationGuard.scheduleEvictForeignPresentations(c, "ensure-$reason")
    }

    fun openPickerForPane(pane: Int) {
        try {
            AaSystemBroadcast.toAaDisplay(
                c.context,
                Intent(AABroadcastConst.ACTION_OPEN_SPLIT_PICKER).apply {
                    putExtra(AABroadcastConst.EXTRA_PANE, pane)
                },
            )
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "openPickerForPane failed pane=$pane:", e)
        }
    }

    fun notifySplitStateChangedImmediate() {
        try {
            AaSystemBroadcast.toAaDisplayAndGearhead(
                c.context,
                Intent(AABroadcastConst.ACTION_SPLIT_STATE_CHANGED).apply {
                    putExtra(
                        AABroadcastConst.EXTRA_PRIMARY_PACKAGE,
                        c.mPanePackages[SplitPane.PRIMARY].orEmpty()
                    )
                    putExtra(
                        AABroadcastConst.EXTRA_SECONDARY_PACKAGE,
                        c.mPanePackages[SplitPane.SECONDARY].orEmpty()
                    )
                    putExtra(AABroadcastConst.EXTRA_FULLSCREEN_PANE, c.mFullscreenPane)
                    putExtra(AABroadcastConst.EXTRA_RATIO, c.mRatio)
                },
            )
        } catch (e: Throwable) {
            logDebug(SplitDisplayController.TAG, "notifySplitStateChanged failed: ${e.message}")
        }
    }

    fun resetSettlement() {
        settlementPhase = SettlementPhase.IDLE
    }

    /** Debounced after ATMS stack settles (reclaim + refresh + dirty). */
    fun scheduleAtmsSettle() {
        c.mHandler.removeCallbacks(mDebouncedAtmsSettle)
        c.mHandler.postDelayed(mDebouncedAtmsSettle, SplitDisplayController.RECLAIM_DEBOUNCE_MS)
    }

    fun launchOnDisplay(
        componentName: ComponentName,
        userId: Int,
        displayId: Int,
    ): Boolean {
        return try {
            VdDensityPin.markPackageOnVirtualDisplay(
                componentName.packageName,
                displayId,
            )
            val ok = AaLaunchHelper.startActivityOnDisplay(
                context = c.context,
                component = componentName,
                userId = userId,
                displayId = displayId,
                mode = AaLaunchHelper.Mode.COLD,
                addLauncherCategory = true,
            )
            if (ok) {
                c.ownership.trackPackage(componentName.packageName, userId)
            }
            ok
        } catch (e: Throwable) {
            log(SplitDisplayController.TAG, "launchOnDisplay error display=$displayId:", e)
            false
        }
    }

    fun resolveLaunchComponent(packageName: String): ComponentName? {
        val pkg = packageName.trim().takeIf { it.isNotEmpty() } ?: return null
        PmResolveCache.get(pkg)?.let { return it }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
            val pm = c.context.packageManager
            val flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            val ri = pm.resolveActivity(intent, flags)
                ?: pm.queryIntentActivities(intent, flags).firstOrNull()
                ?: return null
            val ai = ri.activityInfo ?: return null
            val cn = ComponentName(ai.packageName, ai.name)
            PmResolveCache.put(pkg, cn)
            cn
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Explicit Recent/stack close: remember [packageName] for this session so
     * surfaces-ready backfill / restore do not resurrect it. Durable snapshot is
     * left intact for reconnect memory (filtered at restore/backfill).
     */
    fun onExplicitPackageClosed(packageName: String) {
        val pkg = packageName.trim()
        if (pkg.isNotEmpty()) {
            c.mExplicitlyClosedPackages.add(pkg)
        }
    }

    /** Immediate ratio-only durable write (no ATMS / full snapshot). */
    fun persistRatioNow() {
        val ratio = if (SplitPane.isFullscreenPane(c.mFullscreenPane)) {
            SplitPane.clampRatio(c.mRatioBeforeFullscreen)
        } else {
            SplitPane.clampRatio(c.mRatio)
        }
        LastSplitStore.saveRatioOnly(ratio, c.context.contentResolver)
    }

    /** Flush durable snapshot when AA disconnects but VDs may survive Delay Destroy. */
    fun flushPersistOnAaDisconnect() {
        persistRatioNow()
        persistSnapshot(force = true, logSettingsFailures = false)
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

    fun persistSnapshot(force: Boolean, logSettingsFailures: Boolean) {
        // On destroy/verify, ATMS may already show empty VDs — do not trim stacks first.
        if (!force) {
            refreshPanePackagesFromAtms()
        }
        // Prefer stack fronts so a transient ATMS-only top cannot poison the durable snapshot.
        val primaryPkg = (c.stacks.front(SplitPane.PRIMARY) ?: c.mPanePackages[SplitPane.PRIMARY])
            ?.trim().orEmpty()
        val secondaryPkg = (c.stacks.front(SplitPane.SECONDARY) ?: c.mPanePackages[SplitPane.SECONDARY])
            ?.trim().orEmpty()
        if (primaryPkg.isEmpty() || secondaryPkg.isEmpty() || primaryPkg == secondaryPkg) {
            if (force) {
                logDebug(SplitDisplayController.TAG, "persist skip: incomplete panes primary=$primaryPkg secondary=$secondaryPkg")
            }
            return
        }
        val primaryStack = c.stacks.packagesBottomToTop(SplitPane.PRIMARY).ifEmpty { listOf(primaryPkg) }
        val secondaryStack = c.stacks.packagesBottomToTop(SplitPane.SECONDARY).ifEmpty { listOf(secondaryPkg) }
        val snap = LastSplitStore.Snapshot(
            primaryPackage = primaryPkg,
            secondaryPackage = secondaryPkg,
            // Persist the split ratio even while fullscreen (not 0/1).
            primaryRatio = if (SplitPane.isFullscreenPane(c.mFullscreenPane)) {
                SplitPane.clampRatio(c.mRatioBeforeFullscreen)
            } else {
                c.mRatio
            },
            fullscreenPane = c.mFullscreenPane,
            primaryStack = primaryStack,
            secondaryStack = secondaryStack,
        )
        LastSplitStore.save(snap, c.context.contentResolver, logSettingsFailures = logSettingsFailures || force)
    }

    /** Re-walk ATMS tops so snapshots / empty-pane state match reality after external closes. */
    fun refreshPanePackagesFromAtms(): Boolean {
        var changed = false
        val settling = SystemClock.uptimeMillis() < c.mSuppressReclaimUntil
        val identity = Binder.clearCallingIdentity()
        try {
            for (pane in intArrayOf(SplitPane.PRIMARY, SplitPane.SECONDARY)) {
                val displayId = c.input.displayIdFor(pane) ?: continue
                val tasks = c.ownership.normalizeRootTasksBottomToTop(
                    tryOrNull {
                        Instances.iActivityTaskManager.getAllRootTaskInfosOnDisplay(displayId)
                    }.orEmpty()
                )
                val userPkgsBottomToTop = c.ownership.snapshotUserRootTasks(tasks)
                    .mapNotNull { it.packageName?.trim()?.takeIf { p -> p.isNotEmpty() } }
                    .distinct()
                // Prefer visible root; after normalize, last user task is the front.
                val topPkg = tasks.firstOrNull { info ->
                    c.ownership.isRootTaskVisible(info) &&
                        info.topActivity?.packageName?.let { pkg ->
                            pkg.isNotBlank() && !SplitChromePackages.BOUNCE_EXCLUDED.contains(pkg)
                        } == true
                }?.topActivity?.packageName
                    ?: tasks.lastOrNull { info ->
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
                if (!settling && userPkgsBottomToTop.isNotEmpty()) {
                    // Keep intentional PaneAppStack front (Recent 置顶) when ATMS on a behind VD
                    // still reports the old top. Merge instead of replace so demoted buried
                    // packages missing from the ATMS walk are not wiped from bookkeeping.
                    val previousFront = c.stacks.front(pane)
                    c.stacks.mergeAliveKeepingOrder(pane, userPkgsBottomToTop)
                    if (!previousFront.isNullOrBlank() && c.stacks.contains(pane, previousFront)) {
                        c.stacks.moveToTop(pane, previousFront)
                    }
                    c.ownership.enforceStackFrontAudio(pane)
                } else if (!settling && next == null) {
                    c.stacks.trimToAlive(pane, emptyList())
                }
                // Prefer stack front for persist; fall back to ATMS top only when stack empty.
                val booked = c.stacks.front(pane) ?: next
                if (c.mPanePackages[pane] != booked) {
                    c.mPanePackages[pane] = booked
                    changed = true
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return changed
    }
}
