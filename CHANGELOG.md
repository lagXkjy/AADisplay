# Changelog

## Unreleased

### Changed
- Default settings: `EnableOneUiSplit` and `DisableGoogleMapsOnAa` now default to `true` (fresh installs / missing prefs keys).

### Added
- Recent-task stack UI: explicit close button on each task (phone overlay and AA car panel). Outward swipe-to-remove is unchanged.
- OneUI split-screen support on the AA virtual display (setting `EnableOneUiSplit`, default on):
  - Enables system decorations on the virtual display so OneUI multi-window can stay active.
  - Sets the virtual display windowing mode to freeform and launches non-Home apps as freeform so the OneUI caption/handle bar (drag, resize, enter split) is available.
  - Launching a second app while another non-Home app is foreground uses `FLAG_ACTIVITY_LAUNCH_ADJACENT` (falls back to freeform/fullscreen on failure).
  - Task switches prefer `setFocusedTask` while multi-window is active so split is not collapsed by `moveTaskToFront`.
  - Home/Back PiP cleanup and Home restart skip actions that would tear down split/MW layouts.

### Fixed
- Recent-task stack UI could list Samsung One UI Home (`com.sec.android.app.launcher`) on both the phone stack and the AA virtual display (SECONDARY_HOME). Closing/swiping it killed the shared launcher process. Those system Home tasks (and other bounce-excluded packages) are now filtered out of the recent-task UI.
- AA facet / side menu buttons missing on Android Auto 17.x: `AaUiHook` only matched `gh_coolwalk_vertical_facet_bar`, but canonical vertical-rail layouts often inflate other coolwalk facet hosts (or equivalent content). Now matches multiple facet layout IDs and also detects facet chrome by `status_bar` + launcher icon views, with safer null handling and inject logging.
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
