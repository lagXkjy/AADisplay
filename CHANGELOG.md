# Changelog

## Unreleased

### Changed
- Default settings: `EnableOneUiSplit` and `DisableGoogleMapsOnAa` now default to `true` (fresh installs / missing prefs keys).

### Added
- OneUI Split enabled: car left facet bar gains a **quick restore split** button (above the clean-split-shells control) that manually runs Restore Last Split for the last stable left/right pair (no AppsEdge). Independent of the connect-time `RestoreLastSplit` setting; failure does not fall back to Default Launch.
- **Restore Last Split** (`RestoreLastSplit`, default on): when Android Auto creates a **new** virtual display, restore the last stable OneUI split pair (left/right packages + primary ratio) instead of only opening Default Launch. Snapshot is written to `/data/system/aadisplay_last_split.properties` while split is stable (no AppsEdge). Restore brings both apps as freeform, enters split with `withRecentAllApps=false` (auto-pair, no chooser), then best-effort applies the saved ratio. Soft reconnect (`onReconnected` / delay-destroy) does not re-run restore. Failures fall back to Default Launch. Requires OneUI Split Screen. **Fix:** configured Default Launch (often aliased to Home, e.g. 嘟嘟mini) is no longer filtered out of split-pane detection, so two-sided snapshots can persist and restore; also snapshot on VD destroy and log skip reasons. **Fix (r12):** restore no longer reuses `mLauncherPackageTaskId` (right pane was falsely marked as the left task → `right-missing`); dedicated `bringPackageToVirtualDisplayForRestore` + MAIN/LAUNCHER intent; wait until both panes are freeform before TOC auto-pair. **Fix (r13):** `requestFreeformToSplitForRestore` resolves the real system_server ATMS via LocalServices (was using binder stub → `task not found` / both apps stay freeform, never enter split). **Fix (r14):** StageCoordinator auto-pair filters fullscreen companions (`windowingMode=[1,0]`) — restore left stays fullscreen, only right is freeform→split; rebind also on `onSplitLayoutChangeRequested` (was too late on `onFreeformToSplitRequested` alone); never `ensureFreeform` the organizer root `#3` while split forms. **Fix (r15):** split actually formed but success detection used `dumpsys` (fails in system_server) → false `split-not-formed` → Default Launch (嘟嘟mini) covered the split; detect via local ATMS and never overlay Default Launch after TOC. **Fix (r16):** parallel bring both panes (no fullscreen→cover→split choreography); cancel late `ensureFreeform` after TOC (was demoting organizer and misplacing divider); apply ratio to stage shells after expand, not leaf-only resize. **Fix (r17):** after restore succeeded, 嘟嘟mini self-start triggered `onActivityDismissingSplitTask` and collapsed the VD split; protect restored split ~45s, ignore launcher dismiss/bounce, never resize leaves when stage shells are missing. **Perf (r18):** stop snapshot path from `dumpsys` + Settings.Global on every stack settle (was hitching the VD); debounce 3.5s, file-only hot writes, ratio epsilon skip, mirror Settings only on destroy. **Fix (r18):** restore stuck on `wait-freeform` when Douyin etc. keep `windowingMode=fullscreen` with inset bounds — treat inset as TOC-ready, force freeform via local ATMS before `onFreeformToSplitRequested`, stop multi-delay ensure spam. **Perf (r19):** `getSplitAppTasksOnDisplay` used EZX `getObjectOrNull(intent/mIntent)` which ERROR-logged a full stack per tree node; cleanup/stack paths called it continuously → system_server ~130%+ CPU / load 14+. Quiet field reads + 100ms cache.
- OneUI Split enabled: car left facet bar gains a one-click **clean split shells** button (below quick restore / above Home/Recent/Back) that force-removes all StageCoordinator / split-stage shells (empty or live) on the AA VD, phone, and orphan ATM displays. Manual cleanup treats any non-Home root with ≥2 child task ids as a split shell (covers ATM reporting `#3` as fullscreen with kids=`4,5` on the phone display, not only freeform on the VD). After wipe, clears ensureFreeform suppress, re-applies VD freeform policies, re-ensures inset freeform on remaining VD apps, then **steals** respawned phone empty `#3→#4/#5` onto the AA VD for several seconds (SystemUI recreates killed shells on the phone; those idle stages block `moveFreeformTaskToSplit`). Empty-shell auto cleanup force-removes phone leftovers and stuck/orphan shells, but preserves healthy idle StageCoordinator on the AA VD for the next split entry.
- Recent-task stack UI: explicit close button on each task (phone overlay and AA car panel). Outward swipe-to-remove is unchanged.
- OneUI split-screen support on the AA virtual display (setting `EnableOneUiSplit`, default on):
  - Enables system decorations on the virtual display so OneUI multi-window can stay active.
  - Sets the virtual display windowing mode to freeform and launches non-Home apps as freeform so the OneUI caption/handle bar (drag, resize, enter split) is available.
  - Launching a second app while another non-Home app is foreground uses `FLAG_ACTIVITY_LAUNCH_ADJACENT` (falls back to freeform/fullscreen on failure).
  - While split stages are active, starting another app (recent-task phone tap / swipe-to-VD / `startActivity`) **replaces the focused pane**: remove + forget ownership + suppress reclaim + `LAUNCH_ADJACENT` (OneUI caption close is unusable on the VD).
  - Task switches prefer `setFocusedTask` while multi-window is active so split is not collapsed by `moveTaskToFront`.
  - Home/Back PiP cleanup and Home restart skip actions that would tear down split/MW layouts.

### Fixed
- OneUI caption split on the AA VD putting the freeform app on the **right** with Default Launch (e.g. 嘟嘟mini) on the **left**: stock only sets `withRecentAllApps` when the phone top fullscreen is Home; otherwise StageCoordinator skips AppsEdge and auto-pairs other FREEFORM companions into main/left. AA VD freeform→split now forces `withRecentAllApps=true`, parks companion freeforms onto the phone, and suppresses reclaim so AppsEdge opens with the caption app on main/left.
- AA left facet buttons occasionally missing until USB replug: soft reconnect can rebuild `LayoutInfo` before `GhFacetBar` chrome is attached, so the short ensure window missed the inject. Now also re-arms on facet window attach, extends ensure retries (~8s + poll), and retries when rail inflate is too early.
- OneUI split dead again after shell cleanup / PiP: phone Display 0 idle `#3→#4/#5` occupied global main/side stages so VD `moveFreeformTaskToSplit` failed with `no display`, and kill-only cleanup lost to SystemUI respawn (also force-wiped any shell already on the VD). Now relocates empty phone StageCoordinator roots onto the AA VD (reuse), only removes phone leftovers, preserves healthy idle stages on the VD, and no longer defers `ensureFreeform` solely because those idle shells exist. Relocate/kill is suppressed during caption freeform→split entry / active split stages / AppsEdge (otherwise moving `#3` mid-transition makes the app vanish on click) — **not** while only FREEFORM is on the VD (that idle state is exactly when phone-steal must run). Also do not refresh the split-entry shell guard on every `onTaskWindowingModeChanged` (freeform ensure was permanently blocking steal). After manual wipe, phone-steal must **not** kill respawned phone shells when relocate failed / VD has no StageCoordinator (that kill window is exactly `no display` on the next caption click); use `cmd activity display move-stack` when `moveRootTaskToDisplay` no-ops on fullscreen phone organizers. After split is torn down / re-entered, owned apps nested under phone organizer stages are reclaimed (organizer roots with empty `topActivity` no longer skip leaf reclaim), bounce suppress after shell steal is short so reclaim is not blocked, and asymmetric-collapse / reclaim force-unminimize so the survivor is not left invisible until a stack tap.
- OneUI split shell stuck at freeform inset after 央视影音 PiP: pinned task stayed on the AA VD and StageCoordinator reused inset bounds (`[43,29][676,451]`). Now relocates PiP to the phone display; expand no longer depends on empty `childTaskIds` (uses stage `rootTaskId` / shrunken freeform organizer fallback) and walks nested stage children when detecting split apps.
- OneUI split shell stuck at freeform inset size on the AA VD (e.g. `[43,29][676,451]` on 720×480, ~88%): entering split from inset freeform keeps the StageCoordinator root at `mLastNonFullscreenBounds`. Now expands the organizer parent to the full VD (and scales stages once if still shrunken); also skips bouncing split leaf tasks (`Unknown rootTaskId`). Follow-up: OneUI `RootTaskInfo.childTaskIds` is often empty while live Task still has `#3→#4/#5`, so expand/split detection never saw the shell — resolve via `anyTaskForId`, probe expand on every stack/windowing change, and retry `resizeTask` with `RESIZE_MODE_SYSTEM_FORCED` when plain SYSTEM leaves inset bounds.
- OneUI split dead until SystemUI/reboot: empty StageCoordinator shells on **orphaned ATM displays** (e.g. Display #16, gone from DisplayManager) had `mIsRemovalRequested=true`; `ATM.removeTask` returned `true` but left `#3→#4/#5` in place, so Shell stages stayed occupied and caption split no-op'd / `moveFreeformTaskToSplit` failed with `no display`. Cleanup now verifies the task is actually gone and force-removes stuck/orphan shells via `Task.removeImmediately` / `remove*` (never force-kills live idle StageCoordinator roots on the phone).
- OneUI split on the AA VD: closing one pane often left the survivor stuck in `multi-window` at half width with an empty opposite stage (empty-shell cleanup and `ensureFreeform` both skipped because a split app was still present). Now detects one-app + empty-opposite-stage, confirms past mid-entry (~1.2s, aborts while AppsEdge chooser is up), removes the empty shell, and forces the survivor to inset FREEFORM.
- OneUI freeform caption swipe-down on the AA VD: minimize (`isMinimized=true`) made the app vanish, and stack tap via plain `moveTaskToFront`/`setFocusedTask` could not restore it. Auto-restores true caption minimize (on-screen + minimized/not-visible) only after bounds stay stable (~0.9s) so live caption/border *move* is not treated as hide; restore relaunches FREEFORM keeping existing bounds (no inset snap). After a real width/height change (edge/corner resize), confirm is much shorter (~0.2s) because OneUI often misreads top-corner diagonal shrink as minimize on the tiny VD; overrun bounds are clamped into the display. Stack tap also restores mostly-off / not-visible freeform.
- OneUI split on the AA VD: configured Home/fullscreen launcher staying resumed underneath stole focus, left Shell's `StageCoordinatorSplitDivider` without a buffer (8px seam showed Home), and blocked divider drag so the ratio stuck near 50/50. While split stages are active, push Home behind and keep focus on a split pane.
- OneUI freeform caption maximize (looks like fullscreen) then close from the task stack: reopen stayed maximized because OneUI no-ops `setTaskWindowingMode` on the AA VD (`mode=1` after "success"). Now falls back to `startActivityFromRecents` / re-deliver with `setLaunchWindowingMode(FREEFORM)` (same mechanism as `am start --windowingMode 5`), with pending inset retries and bounce-suppress fixes for move-to-VD.
- OneUI split dying after a video app enters picture-in-picture (e.g. 央视影音 画中画): PiP sets `WINDOWING_MODE_PINNED` and often moves the task to the phone; reclaim/bounce then pulled that pinned window back onto the AA virtual display and left empty stage shells / no freeform caption. PiP tasks are no longer reclaimed; `onActivityPinned` / `onActivityUnpinned` clean empty organizer shells and re-assert freeform display policies so split can be entered again.
- OneUI `moveFreeformTaskToSplit` failing with `no display` after PiP/split abort: empty multi-window stage shells (`#3→#4/#5`) can remain on the **phone** DEFAULT_DISPLAY (not only the AA VD) and occupy main/side stages. Empty-shell cleanup now also scans the phone display, runs on AA connect/reconnect, and matches stage zombies even when `createdByOrganizer` is missing.
- OneUI split dying after AA disconnect/reconnect: StageCoordinator trees can stick on **orphaned ATM displays** (TaskDisplayArea still listed by ATM but missing from DisplayManager — e.g. Display #27 left from a prior VD session) with empty main stage + AppsEdge chooser. Cleanup now discovers those ghost displays via `getAllRootTaskInfos`, force-removes organizer/split/AppsEdge tasks there on connect/reconnect/destroy, tracks them on `onTaskDisplayChanged`, and excludes `com.samsung.android.app.appsedge` from VD reclaim.
- OneUI split reclaim could bounce organizer root/stage tasks (e.g. freeform task `#3`) when they briefly appeared on the phone with a child app as `topActivity`, resetting the divider ratio. Organizer tasks are no longer tracked/bounced; intentional `removeTask` forgets package ownership and suppresses reclaim so a closed pane can be replaced.
- Recent-task stack UI could list Samsung One UI Home (`com.sec.android.app.launcher`) on both the phone stack and the AA virtual display (SECONDARY_HOME). Closing/swiping it killed the shared launcher process. Those system Home tasks (and other bounce-excluded packages) are now filtered out of the recent-task UI.
- AA facet / side menu buttons missing on Android Auto 17.x: `AaUiHook` only matched `gh_coolwalk_vertical_facet_bar`, but canonical vertical-rail layouts often inflate other coolwalk facet hosts (or equivalent content). Now matches multiple facet layout IDs and also detects facet chrome by `status_bar` + launcher icon views, with safer null handling and inject logging.
- AA facet / side-menu buttons disappearing after disconnect/reconnect: AA rebuilds `LayoutInfo` (canonical vertical rail) without re-inflating the coolwalk facet bar, so the inflate-only inject never runs again. Now also injects in-place into the rail’s facet column when the canonical rail layout inflates, and re-scans window roots after `LayoutInfo` (multi-delay) to restore Home/Back/Recent buttons.
  - When `EnableOneUiSplit` is on, apply `setWindowingMode(FREEFORM)` with system decors on connect/reconnect (do not force FULLSCREEN when the setting is off).
  - Non-Home launches use `ActivityOptions.setLaunchWindowingMode(FREEFORM)` + inset `setLaunchBounds`; Home/launcher stays fullscreen.
  - After a task lands on the VD (create / move-to-front / display-changed / post-launch), force `setTaskWindowingMode(FREEFORM)` and inset `resizeTask` when still fullscreen or maximized-looking freeform, with multi-delay retries, so caption appears without a phone↔VD round-trip.
- OneUI caption “split” moving the freeform app onto the phone stack:
  - Track VD tasks and bounce unsolicited `VD → DEFAULT_DISPLAY` moves back onto the virtual display (intentional recent-task `moveTaskId` to phone is suppressed).
  - Also track owned **packages** (e.g. `com.autonavi.amapauto`): OneUI often recreates a new taskId on the phone and opens an Apps/Home chooser (“应用…”); reclaim scans the phone stack on stack/windowing/display changes and pulls owned apps back.
  - Follow-up reclaim passes at 0/400/1000ms for lagged split-stage moves. Phone launcher / SystemUI chooser packages are never bounced.
  - After bounce/reclaim, briefly suppress ensureFreeform (~1.8s) so forcing FREEFORM does not collapse OneUI split/MW; `onTaskWindowingModeChanged` only reclaims (no freeform force). Intentional `moveTaskId` back to VD still ensures freeform.
- Settings appearing “lost” after reboot for hooks (especially `EnableOneUiSplit`):
  - App prefs XML was already saved; SELinux blocks system_server from reading `app_data_file`.
  - On save, copy the same `aadisplay_config.xml` to `/data/system/aadisplay_config.xml` (`system_data_file`, survives reboot; `/data/local/tmp` is wiped on many devices) and load via `XSharedPreferences(File)` when the package path is unreadable.
- `AaMainFragment` crash (`Fragment not attached to a context`) during AA reconnect when registering control receivers after detach; skip register/unregister when detached so steering-wheel / screen-control hooks keep working.
- OneUI split often surviving only until the first AA reconnect / display destroy (needed phone reboot):
  - AA reconnect no longer reinstalls density hooks in a way that clears the VD DPI map mid-session.
  - `ActivityRecord` density pin only rewrites when DPI actually differs (avoids fighting OneUI MW layout).
  - Reconnect re-applies IME / system-decors / freeform windowing / forced VD density policies.
  - `ShellManager` teardown checks binder liveness + death recipient (stops noisy `DeadObjectException` on destroy).
- `removeTask` now uses `IActivityTaskManager.removeTask` return value, and when the last task of a package leaves the virtual display it clears the VD DPI map and package tracking.
- Cross-display task moves between the phone stack and the AA virtual-display stack (recent-task swipe / OneUI move):
  - `moveTaskId` no longer uses `setFocusedTask` after `moveRootTaskToDisplay` (that left moves half-applied and broke MW + density).
  - Virtual-display DPI map is updated immediately on `moveTaskId` and `onTaskDisplayChanged` (mark on VD, clear on phone).
  - Virtual display density is forced via `setForcedDisplayDensityForUser`, and ActivityRecord configuration ensure re-pins VD `densityDpi` for tasks on the VD.

### Known limitations
- Depends on OneUI multi-window policy; non-resizable apps may still fail (Developer option “Force activities to be resizable” helps).
- Enabling system decorations may show status/nav chrome on the virtual display.
- Not a custom split UI; pairing UI and cross-display split are out of scope. Non-Samsung devices are unsupported.
- Restore Last Split ratio is best-effort (`resizeTask`); OneUI may still settle near 50/50. Package-pair restore is prioritized over exact divider position. Snapshot orientation must match the new VD or ratio apply is skipped.

## 0.23.6 (2026-07-26)

### Changed
- Production version updated to:
  - `versionName`: `0.23#17.2-r5`
  - `versionCode`: `3011`
- AADisplay now creates its virtual display with the active Android Auto `TextureView` surface immediately instead of creating a surface-less display and attaching later.
- Default launcher startup now resolves from installed home launchers at runtime when no explicit launch package is configured, avoiding stale hard-coded package defaults.

### Fixed
- Fixed blank AADisplay rendering on Android Auto `17.2` by preserving the `TextureView` surface, attaching it during virtual-display creation, and reattaching it on reconnect.
- Prevented duplicate virtual display creation during rapid Android Auto lifecycle callbacks.
- Made hook preference access resilient under LSPosed by committing preferences synchronously and verifying hook-readable preference files.
- Android Auto hooks now continue with safe defaults if preference access is temporarily unavailable instead of aborting all hook setup.

### Verification
- Built successfully:
  - `:aa-display:assembleRelease`
- Installed and reboot-tested on the connected device.
- Live Android Auto validation confirmed:
  - AADisplay side control panel works
  - on-device Android Auto controls work
  - AADisplay opens and renders launcher content
  - backend virtual display and AA-hosted display both showed the same rendered content

## 0.23.4 (2026-05-23)

### Added
- Separate production toggle for Google Maps-on-phone while Android Auto remains connected:
  - new persisted setting `DisableGoogleMapsOnAa`
  - component-level enable/disable manager for Maps projection services + ghost activity

### Changed
- Production version updated to:
  - `versionName`: `0.23#16.8-r1`
  - `versionCode`: `3006`
- Waze component toggle execution now uses the same success gating strategy as Google Maps (all component operations must succeed before returning success).

### Fixed
- Android Auto UI/resource hook stability:
  - `AaUiHook` now skips optional hooks when required resources are missing instead of asserting/crashing.
  - projection decoration constructor hook now uses compatible constructor matching rather than hard-coded constructor lookup.
- Gearhead phenotype property hook robustness:
  - `AaPropsHook` now resolves string fields dynamically to reduce obfuscation fragility.

### Cleanup
- Removed dead/unused code and assets not used by current production flow:
  - removed `AaControlService`, `AaCarService`, `SettingsActivity`, legacy `LauncherHook`, deprecated pref XML, and orphaned resources.
- Removed obsolete global build-feature flag from `gradle.properties` (now explicitly configured in module build config).

### Evidence / Validation
- Built successfully:
  - `:aa-display:assembleRelease`
  - `:aa-display:lintDebug`
- Verified on connected device app targets:
  - Android Auto `16.8.661854-release`
  - Google Maps `26.20.01.913318892`
  - Waze `5.18.5.6`
- Waze and Maps toggles validated as separate independent controls in app flow.

## 0.23.3 (2026-05-05)

### Added
- Smart-sidebar style controller flow:
  - floating controller now starts minimized by default
  - tap edge handle to open, tap close to hide, drag and snap left/right edge

### Changed
- Keyboard routing on virtual display now enforces local IME policy (`DisplayImePolicy=0`) for on-screen input on AADisplay side instead of fallback-to-phone behavior.

### Fixed
- Removed the non-functional disconnect-type (`A`) button from the floating controller.
- Disconnect countdown now replaces the monitor button slot directly during disconnect flow for a cleaner 2-control vertical stack.

### Verification
- `:aa-display:assembleDebug` passed after controller + IME policy updates.
- Installed successfully to test device for live verification.

## 0.23.2 (2026-05-05)

### Changed
- Release build signing now falls back to debug signing when `KEY_ANDROID` is not present, so maintainers can still produce a release artifact locally without exposing private signing credentials.

### Fixed
- Prevented DHU disconnect teardown from forcing the phone back to launcher/home when AADisplay session closes on the virtual-display home screen.
- Teardown package-stop logic now excludes:
  - AADisplay itself
  - configured launch/home package
  - current foreground package on the primary phone display

### Verification
- Reproduced issue with config where `LauncherPackage` and `HomePackage` were both launcher-based, then validated corrected behavior after patch.
- `:aa-display:assembleDebug` succeeds with this change.
- Installed and prepared for live device retest flow.

## 0.23.1 (2026-05-04)

### Added
- In-app GitHub menu now points to the production fork: `https://github.com/Stashboy/AADisplay`.

### Changed
- Internal AADisplay behavior now syncs `HomePackage` to `LauncherPackage` so task-view home behavior is consistent with selected launch package.
- Sidebar Back key handling now uses Android virtual-display key event semantics (`FLAG_FROM_SYSTEM | FLAG_VIRTUAL_HARD_KEY`) for more reliable back navigation.

### Fixed
- Sidebar Back now works reliably in task-view sessions.
- Backing out to AADisplay home now clears pinned PiP state using the same cleanup strategy as Home (with front-home checks).

### Cleanup / Optimization (Evidence-Based)
- Fixed a real API-compatibility risk in `AADisplayConfig`: replaced Java Stream `.toList()` usage (API 34+) with Kotlin collection operators (safe for min SDK 31).
- Added missing `super.onDestroy()` in `AaControlService` to resolve lifecycle correctness lint error.
- Removed unused settings warning view/resources and unused string bloat in main settings screen.
- Internationalization cleanup for top-right menu title (`GitHub` moved to string resource).

### Verification
- `:aa-display:assembleDebug` succeeds after all changes.
- `:aa-display:lintDebug` re-run confirms resolved issues for:
  - `MissingSuperCall` (AaControlService)
  - `NewApi` (AADisplayConfig stream/toList path)
  - `HardcodedText` (`menu_main.xml` GitHub title)
- Runtime verification completed against Android Auto `16.6` behavior in both DHU and real head unit sessions.

## 0.23.0 (2026-05-04)

### Added
- Dynamic TaskView sizing lock to stabilize UI rendering across different head unit dimensions.
- Better teardown behavior for TaskView sessions to reduce lingering app states on disconnect/exit.
- Android Auto 16.6 hook compatibility updates.

### Changed
- Simplified settings UI to a single screen with only production-relevant options:
  - Auto Open
  - Default Launch Package
  - Delay Destroy Time
- Default GitHub menu flow retained from the main screen (settings gear flow removed).
- Production version string updated to `0.23#16.6-r1`.

### Fixed
- Kernel panic scenario during specific TaskView lifecycle transitions.
- Home/exit transition handling that previously left abnormal PiP-like states.
- Inconsistent YouTube rendering outcomes caused by unstable virtual display dimensions.

### Security / Privacy
- Removed repository-tracked signing key material from source control.
- Added keystore patterns to `.gitignore`.
- Removed machine-specific Gradle JDK path from tracked config.

### Compatibility
- Validated behavior against Android Auto 16.6 and device-side testing with real head unit workflow.
