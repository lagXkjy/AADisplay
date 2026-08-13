# Changelog

## 0.24#17.4-r3

### Fixed
- **Douyin LivePlay covers the other pane + 方控 dead (r3):** LivePlay uses MediaRouter / `createWindowContext(TYPE_PRESENTATION)` to attach a fullscreen presentation onto the sibling AA VD (e.g. task on SECONDARY while PRIMARY/高德 is covered and keys have no focus sink). Drop `VIRTUAL_DISPLAY_FLAG_PRESENTATION` on AA VDs; block foreign presentation attach/`addWindow` in `AndroidHook.PanePresentationGuard`; harden `SplitPresentationGuard` eviction (`removeImmediately`, dynamic delays). Before key inject, evict + bring task to front.
- **方控「下一曲/上一曲」切直播间 (r3):** on live-style top activities, `MEDIA_NEXT`/`MEDIA_PREVIOUS` inject a vertical fling using **current** VD `getRealSize` (split ratio / resize safe) instead of media-session fallback (which was driving QQ 车载音乐). Feed still uses normal media keys. After pane swap, resolve the LivePlay VD across both panes (do not trust `mFocusedPane` alone — focus can stay on 高德 and wrongly drive QQ).

### Changed
- **Version bump to `0.24#17.4-r3`** (`versionCode` 3059).

## Unreleased

### Changed
- **Low-risk hygiene:** drop unused `Application` (never in Manifest), dead Gradle deps (`hidden:compat`, `coroutines-jdk8`, empty test deps, KSP srcDir), unused ATMS stub methods; align lib-stub hidden stub to 4.4.0; docs sync (`AGENTS` util map).
- **Drop dead Auto Open flag:** `AaUiHook.mAutoOpen` was always set `true` after prefs removal; remove the fake gate (behavior unchanged: always arm retries).
- **Dead-code cleanup:** remove orphaned `ACTION_SCREEN_CONTROL` (+ Car/`AACarUtil` path), unused floating-controller buttons (`ib_expand`/`ib_extinguish`), idle `toggleDisplayPower`/`displayPower` IPC, and unused `Application.App`.
- **OSS hygiene cleanup:** remove dead ScreenOffReplace / `AndroidHook.Power` / `DisplayPowerCompat`; drop unused restore `manual` API and LastSplitStore landscape/sideBySide reads; rename `FuckAppUseApplicationContext` → `VdDensityPin`; refresh Known limitations for dual-VD (below).
- **Version bump to `0.24#17.4-r2`:** target Android Auto 17.4; remove MainActivity GitHub menu link.
- **Dead-code sweep (post rail-touch cleanup):** remove no-op `AaDpiHook` (DexKit load with empty hook body); drop orphaned IPC (`printLog`, `startTaskId`, `getVersionCode`, `getUid`, `restoreLastSplit` manual path); rename `touchHost` → `touchPrimaryPane` (inject PRIMARY pane VD, not host display); extract shared `RecentTaskUiHelper` for AA + phone overlay recent-task columns; stop writing unused `landscape`/`sideBySide` in `LastSplitStore`; remove `ServiceProxy` per-IPC logging and voice-assist no-op stub.
- **Drop i18n string resources:** no multi-locale plan; keep only `app_name` / `xposeddescription` in `strings.xml`, hardcode Chinese UI text in layouts/code (same as existing toasts).
- **Drop Disable Google Maps on AA + App-process su:** remove `GoogleMapsOnAaManager`, libsu, Root Privilege UI, and one-tap reboot via `su`. MainActivity is activation status only; device still needs Root/LSPosed for the module itself.
- **Drop dead ShellManager + unused prefs deps:** remove no-op `ShellManagerService` / `IShellManager` bind path on VD create/destroy; drop `preference-ktx`, `material-preference`, `androidx.media`, and preference theme attrs; trim unused `IsSystemEnv` / `RomUtil` OEM helpers.
- **Dead-code sweep:** remove no-op `AaPropsHook`, unused template TipUtil/`BaseSimpleAdapter`/`RunIOCatching`, unused Utils helpers, unused strings, and unused `SplitAppPickerController.isShowing`.
- **Remove settings prefs entirely:** drop Delay Destroy UI (hardcode 180s), delete `AADisplayConfig` / `SharedPreferencesAccess` / XSharedPreferences mirror path. Auto Open, Restore Last Split, ForceRightAngle, IME policy, etc. are code constants; MainActivity is status-only.
- **Always-on Auto Open / Restore Last Split:** remove the settings toggles (not useful as opt-outs). Behaviors are hard-on: Auto Open always arms; Restore Last Split runs when a valid snapshot exists.
- **Post dual-VD maintainability cleanup:** drop dead `testCode` IPC, unused `IGNORE_RECENT_PACKAGE`, and unused `AaUiHook` density/facet-bar field; rename last-split Snapshot API to primary/secondary (persisted keys unchanged); clarify Samsung chrome vs OneUI StageCoordinator comments; split `SplitDisplayController` into focused modules (`SplitVdLifecycle`, `SplitLaunchRestore`, `SplitOwnership`, `SplitTaskStackListener`, `SplitInputRecents`).
- **Dead-code slim after dual-VD split:** drop unused `ic_aa_*` drawables, empty `styles`/`attrs`, never-registered `CoreBroadcastReceiver`, unused `GenericMotionView`, orphaned `CloseLauncherDashboard` / `EXTRA_RATIO`, no-op `AaUiHook` click-hijack (`hookBaseClick` / `FinallyListener`), and legacy aliases (`mDisplayId`, `getDisplayId`, unused activity/power wrappers). Docs point at `SplitDisplayController` instead of removed `AaVirtualDisplayAdapter`.
- **Slim AA rail + divider stack + all launchable apps:** keep AA on the vertical-rail layout family; reclaim the left black gutter by collapsing the rail/facet chain and expanding content siblings (not just hiding icons). Long-press a live pane to replace its app. Thin divider inspired by OneUI look with three-dot handle (tap opens recent tasks, drag adjusts ratio). Recent-task stack is three columns (primary pane / secondary pane / phone). App picker lists all MAIN/LAUNCHER apps including non-resizeable; add `QUERY_ALL_PACKAGES` + launcher `<queries>` for package visibility. Exit AADisplay via disconnect or phone controls (rail launcher is hidden).

### Fixed
- **Left rail strip still untouchable after reclaim (r44–r49):** ADB on SM-W7023 + AA 17.4 showed Coolwalk still routes HU `x∈[0,80]` to `GhFacetBar` (separate VD). Prior strategies (View relay, `injectTouchEvent` hook, host-display inject) were removed after proving non-functional on Samsung InputDispatcher / private host VD. **Working path (r47–r49):** steal LHD `x<rail` at `:car` `CarActivityManagerService` HU touch dispatch → `ICoreManager.touchPrimaryPane` → PRIMARY pane VD inject (rail width from live FacetBar/`content_bounds`, else ~10% of LayoutInfo HU width; RHD out of scope). Allow gearhead uid in `BridgeService` so `:car` can obtain the CoreManager binder.
- **Recent-task stack hard to dismiss:** empty-tap dismiss was limited to the phone column so VD taps could pick a swipe target, which made the panel feel stuck. Empty tap on phone still closes; first empty tap on a VD column selects the move target, second tap on that already-focused column closes (same on AA panel and phone overlay). Divider / recent-button toggle unchanged.
- **Black panes after AA unplug/replug (Delay Destroy, r43):** soft reconnect with keep-locked display profile skipped `DisplayWindow.onResume`, so the Delay Destroy countdown was not cancelled and released both VDs under a still-live AA split UI (black content, operable divider/picker). Always cancel delay-destroy and rebind/kick surfaces on soft reconnect.
- **Secondary pane cannot re-pick after close (Alook DLNA / empty VD, r42):** after the app finishes, OWN_CONTENT_ONLY VDs stay fully empty (no SecondaryDisplayLauncher), so `getPanePackage` kept stale `mPanePackages` and hid “Tap to choose”. Align vacant detection with ATMS refresh (clear outside settle). Also ignore empty/zombie root tasks when relocating — `bringTaskToFront` on `Activities=[]` no-ops; sweep affinity zombies then relaunch with `MULTIPLE_TASK`.
- **App picker “no reaction” for apps already on phone/other pane (e.g. Alook DLNA on secondary, r41):** `startActivityOnPane` now relocates an existing root task onto the target VD (`moveRootTaskToDisplay`) instead of relying on `NEW_TASK` + `launchDisplayId` alone (Samsung often ignores the display and leaves the car pane unchanged). Cold launches also use `FLAG_ACTIVITY_MULTIPLE_TASK`; launcher resolve uses `MATCH_ALL`.
- **Cold AA start hitch (r40):** skip root Shell prefs-mirror on AA `initViews` (publish only on settings save; skip cp when mirror already current); notify AA after dual VD create before Samsung `freezeDisplayRotation`/keep-awake; defer phone `DisplayWindow` inflate until after `onAvailableDisplay`; start Restore Last Split next-frame (drop 400ms wait); Auto Open first try 400ms; split-state broadcast carries pane packages so empty overlays avoid extra Binder polls.
- **Split divider drag jitter (r39):** drag `onRatioChanged` was calling `CoreApi.setSplitRatio` every MOVE → `VirtualDisplay.resize` + Samsung `freezeDisplayRotation` (walks all DisplayContents, 600–900ms/call on system_server main). Now drag only updates LinearLayout weights; VD resize runs once on settle. Skip redundant orientation re-freeze on resize; raise resize throttle to 120ms.
- **Recent-task swipe-off snapped back to VD (r38):** `moveTaskId(…, false)` ran on a Binder/IO thread while `TaskStackListener` reclaim ran on the main handler, so ownership/suppress were not visible and `reclaim[stack]` pulled the app back ~200ms later (seen live: Settings cleared from VD density map then immediately `reclaim → display=17`). Now marshal move/remove onto the controller handler, `@Volatile` suppress, cancel pending reclaim before move-off, and skip re-arming reclaim during suppress.
- **Narrow split pane letterbox (top/bottom black bars, r37):** side-by-side secondary (or shrunk primary) is often taller than wide (e.g. 278×480). Landscape apps / keep-awake `SCREEN_ORIENTATION_LANDSCAPE` rotated that VD to `ROTATION_90` (logical 480×278) while the TextureView stayed physical W×H. Now `setIgnoreOrientationRequest` + `freezeDisplayRotation(0)` per pane, and keep-awake uses `NOSENSOR`.
- **Empty pane stuck after swipe-off / close (Samsung SecondaryDisplayLauncher):** vacant VDs keep `com.sec.android.app.launcher` Secondary HOME, so `getPanePackage` / ATMS refresh previously preserved stale `mPanePackages` and hid “Tap to choose an app”. Treat bounce-excluded chrome as vacant (outside restore settle), clear chrome tasks after move/remove off a pane, and raise empty-overlay elevation/clickability.
- **Auto Open stuck on AA home until manual tap:** `CarSystemUiControllerService.a(Intent)` swallows "Unable to start activity" when the controller is not ready; a single 1s attempt then permanently blocked retries. Now arms spaced Auto Open retries (1.2/4/8s), stops once AADisplay resumes (broadcast) or `AaActivityService` is already running, and resolves the start method with a static-Intent fallback.
- **Auto Open retry jank / app picker hitch:** avoid repeating start after AADisplay is up; load launcher apps + icons off the main thread when opening the picker.

- **Recent-task stack move off VD left empty pane unable to pick apps:** `moveTaskId(…, false)` forgot ownership but left stale `mPanePackages`, and never broadcast `ACTION_SPLIT_STATE_CHANGED`. AA `getPanePackage` then preferred that bookkeeping over empty ATMS, so “Tap to choose an app” stayed hidden. Now clear pane occupancy (and notify/persist) like `removeTask`, and replace previous pane content when swiping a phone task onto the focused VD pane.

- **AA left rail gutter not reclaimed on dual-VD Coolwalk (r30–r35):** View-only collapse hid `GhFacetBar` icons but AA still reserved `fullWidth − railWidth` for content (e.g. 800−80). Zero gearhead rail-column dimens and shrink/expand VDs from live `LayoutInfo` dp. **r31–r33:** Coolwalk publishes `content_bounds=Rect(rail,0,fullW,fullH)` from `GhLifecycleService` (`:projection`); expand that region and zero `pillar_width` / left `content_insets`. **r34:** do not scale LayoutInfo/rail math by the phone `DisplayMetrics.density` — HU VDs are density-160 so dp≈px. **r35:** after reclaim, host is full width but display-profile lock kept split at the old content width (720) leaving a right gutter — allow monotonic size grow on soft reconnect so panes resize to the new HU width.

- **Empty-pane “Tap to choose an app” overlay stuck over live apps:** AA Binder `getPanePackage` could see a transient empty ATMS walk and clear `mPanePackages`, so overlays never hid after restore/pick. Prefer bookkeeping over destructive empty queries (`clearCallingIdentity`), default both empty overlays to gone, and retry occupancy sync after create/pick.

### Changed
- **Remove Default Launch / Home (嘟嘟mini) path:** dual-VD split no longer needs a launcher home. Dropped AA facet Home button, phone-mirror Home button, `startLauncher` IPC, Default Launch Package settings, and connect-time default-launch fallback (empty panes stay empty for the in-shell picker; Restore Last Split unchanged). Removed `com.ss.squarehome2` from xposed scope.

### Fixed
- **Left pane not restored after split restore / soft reconnect (r29):** replacing a pane app left the old package in `mVdPackages`, so reclaim pulled Home/Default Launch (e.g. 嘟嘟mini) onto the focused (often left) pane and overwrote the restored app; snapshot then saved the wrong left package. Now clear unused ownership on pane replace, never reclaim Home/Launcher, suppress reclaim after restore and verify/relaunch missing sides, and on soft reconnect / surfaces-ready re-ensure pane packages (or re-run last-split restore when both panes are empty).

### Changed
- **Custom dual VirtualDisplay split polish (r28):** sync AA empty overlays / divider ratio with system_server via `getPanePackage` / `getSplitRatio` + `ACTION_SPLIT_STATE_CHANGED`; restore failures open the in-shell picker for the missing side; app picker shows Recent then All; phone mirror follows live divider ratio and stacks in portrait; ATMS refresh for pane bookkeeping; wire `DisplayImePolicy`; distinguish Home vs Default Launch packages. **Fix:** break resize feedback loop that caused constant AA shell jitter — ignore `surface-size` reconnects, never re-apply ratio from stack broadcasts, skip no-op VD resize/reconnect, suppress reclaim during ratio resize.
- **Custom dual VirtualDisplay split (r27):** replaced Samsung OneUI StageCoordinator/freeform split with a vendor-independent dual-VD shell. AA UI hosts two `TextureView`s + drag divider + in-shell app picker; system_server `SplitDisplayController` creates/resizes two trusted virtual displays, launches fullscreen apps per pane, persists/restores PRIMARY/SECONDARY packages + ratio via `LastSplitStore`, and mirrors both panes on the phone overlay. Removed `EnableOneUiSplit`, `SystemUiSplitHook`, `AaVirtualDisplayAdapter` OneUI path, and SystemUI xposed scope. Auto restore on connect via `SplitLaunchRestore`; app picker is in-shell.

### Changed (prior)
- **Perf / maintain:** display pipeline — demote remaining hot-path skip/routine adapter logs to `logDebug` (no LSPosed/`XposedBridge` IO); `CoreManagerService.touch` injects on the Binder thread without per-MOVE `runBlocking` (with `clearCallingIdentity` so IMS still sees system uid — Binder-thread inject without it threw `INJECT_EVENTS` and broke divider drag / all VD touch); reuse adapter `mHandler` instead of ad-hoc `Handler(Looper.getMainLooper())`; shared `rewriteMotionEvent` for AA + phone mirror touch rewrite; create-path config dump is key-count only. OneUI split/reclaim/restore behavior unchanged.
- **Perf:** cut system_server hitching from OneUI split connect/stack storms — hot-path no-ops (`no empty stage roots`, phone-steal skips, ensureFreeform skips, windowing-mode chatter) no longer write LSPosed/`XposedBridge` logs; empty StageCoordinator miss cached ~700ms; phone-steal orphan discovery skipped when no suspect TDAs; `stack-changed`/`windowing-mode` coalesce expand + asymmetric follow-up re-arms (kick instead of reset 0..7s/0..2.4s chains every event). Behavior of steal/cleanup/restore unchanged.
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
- **Empty StageCoordinator miss-cache stampede bricks freeform/split (r26):** perf miss-cache (~700ms) was refreshed on every `before-ensure` miss, so continuous ensureFreeform storms never re-scanned phone `#3→#4/#5`. Empty shells stayed on DEFAULT_DISPLAY / VD, `forceWindowingMode` stayed `ok=false` (apps stuck `mode=1`), caption/restore split died. Miss cache no longer extends while active; stale organizer `topActivity` no longer hides empty `#4/#5`; when FREEFORM still fails, wipe empty shells and retry once.
- **ensureFreeform demoted StageCoordinator `#3` (r25):** after reconnect, OneUI stamps a stale app `topActivity` (e.g. Douyin) on the organizer root. `recoverFreeformOnVirtualDisplayApps` / weak `isSplitOrganizerOrStageTask` then scheduled inset FREEFORM on `#3`, fighting `expandSplitShell` and leaving apps stuck `mode=fullscreen` so caption/restore split die until soft reboot. Now detect `#3→#4/#5` via multi-child / stage-child heuristics (same as cleanup), skip organizers in recover/manual-cleanup/package lookup, and never fall back to returning the organizer as the app task id.
- **Auto Restore Last Split can brick caption split (r24):** when TOC runs but split never forms, code used to finish as `ok-undetected` and arm 45s protect while apps stayed fullscreen — `ensureFreeform` then fails until soft reboot. Now verify real split stages; on failure recover freeform and clean empty shells (no long protect). Also call local-ATMS `forceTaskWindowingModeOnVd` when binder `setTaskWindowingMode` no-ops (common on OneUI AA VD).
- **Last-split snapshot captured AppsEdge as a pane (r23):** mid freeform→split, ATMS walk could save `高德|AppsEdge` instead of the real right app. Filter chooser/system packages in `findOrderedSplitAppSidesOnVd` and reject them in persist.
- **Last-split snapshot file unwritable / stale pair forever (r22):** `aadisplay_last_split.properties` was `root:root 0644` (e.g. after adb/su touch), so system_server could update Settings.Global but not the file; `load()` preferred the stale file → quick-restore always 高德+抖音. Now load prefers Settings, always mirror Settings on save, and retry file write after delete.
- **Last-split snapshot still not saving (r21):** debounce was firing, but `getOrderedSplitSides` returned `sides=0` on nested OneUI `#3→#4/#5` (RootTaskInfo walk miss — same gap restore already worked around). Persist now falls back to local ATMS `findOrderedSplitAppSidesOnVd`, and force-saves after restore/replace settle.
- **Quick restore / last-split snapshot not updating (r20):** after clearing split and forming a new pair, destroy → quick-restore still brought 高德+抖音. Trailing 3.5s debounce was starved forever by Home (嘟嘟mini) fullscreen + split focus-guard `stack-changed` storms, so `/data/system/aadisplay_last_split.properties` never rewrote. Now cap postpone at 8s, force-save after pane replace / VD destroy (AppsEdge no longer blocks force), and mirror Settings on pair changes.
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
- Requires Root + LSPosed with at least **System Framework** and **Android Auto** in scope.
- Custom dual VirtualDisplay split is vendor-independent; some OEMs still need compatibility handling for cross-display `moveRootTaskToDisplay`, empty-VD chrome (e.g. SecondaryDisplayLauncher), and OWN_DISPLAY_GROUP power/doze behavior.
- Non-resizable apps may letterbox or fail to fill a narrow pane (Developer option “Force activities to be resizable” can help).
- Restore Last Split prioritizes package pair over exact divider ratio; ratio apply is best-effort after pane settle.

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
