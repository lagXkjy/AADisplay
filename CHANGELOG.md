# Changelog

## Unreleased

### Fixed
- **手机锁屏后全屏 peel 拉不出 / 长按无响应：** AaDisplay presentation 无 `ALWAYS_UNLOCKED`，Keyguard 会挡住 `touchAaDisplay` 注入，而双 VD App 仍可点。上报 presentation id 时 `setShouldShowWithInsecureKeyguard(true)` + 尽力打上 `FLAG_ALWAYS_UNLOCKED`；锁屏且全屏时改由 system_server 直接解析 peel（退出 / 点按切换 / 长按广播开 Recent），不依赖 presentation 命中。

## 0.24#17.4-r9

### Added
- **虚拟屏多应用栈（每窗最多 3 个）：** Primary / Secondary 不再「选新即杀旧」。应用压入栈，栈顶显示；同栈切换（Recent 点选 / 长按拖到顶部）只 `moveTaskToFront`，无需 close 冷启动。满 3 再加则挤出栈底。Recent 左/中列底部「添加应用」打开选择器（`EXTRA_KEEP_OCCUPANCY`）。`LastSplitStore` 新增有序栈 CSV（底→顶），旧单包 key 仍为栈顶兼容。

### Fixed
- **会话中偶发亮手机屏（优先保三星车机不黑）：** 去掉 VD `ACQUIRE_CAUSES_WAKEUP` pulse（OEM 上易泄漏到主屏）；`userActivity` 只走带 `displayId` 的重载，禁止全局回退。灭屏时仍立即再断言 + 约 3s burst，且手机灭屏期间 heartbeat 缩短为 5s 并用 TOUCH 事件，继续压住三星 `OWN_DISPLAY_GROUP` doze。

- **分屏满幅 VD + crop 回退：** 曾尝试分屏也保持满幅 HU VirtualDisplay 再 TextureView center-crop，结果半窗出现窄条内容 + 大块黑边（HU 截图）。已改回 **VD 缓冲 = 分屏窗格尺寸**（随车机 profile × ratio，非写死分辨率）；去掉 `setDefaultBufferSize(HU)` / crop 矩阵 / 触控映射。QQ 音乐竖屏半窗卡顿需另案处理，不能用满幅裁切牺牲左右分屏布局。

- **全屏 peel 退出后应用分辨率错误：** peel 松手退出时先 `setSplitFullscreen(NONE)`（按进入前 ratio 缩 VD）再 `setSplitRatio(松手 ratio)`，两次 resize 后 OneUI 常把 Window **Requested** 卡在中间宽度（ADB：短信 `Requested 343` 而 VD/frame 已是 `518×480`）。改为退出前先 `setSplitRatio` 暂存到 `mRatioBeforeFullscreen`，再一次性 exit resize；并对两 VD 做与 swap 相同的 1px nudge（`ensureTasksFillDisplay`）。

- **全屏 peel 偶发点不动：** 长按分隔条/peel 在手指仍按下时就 `add` Recent，注入的 UP 打不到 `SplitDividerView`，presentation 指针序列残缺后后续 `touchAaDisplay` 易被丢弃；长按出后台再滑动又能恢复。改为长按只震动标记，**松手 UP 后再开 Recent**；开面板前 `resetGesture` + 清拖动预览。另：`touchAaDisplay` 解析不到 presentation id 时防抖清 ATMS 门闩并广播 `REQUEST_AA_UI_DISPLAY_ID` 让 UI 补报，避免会话内永久哑火。

- **分隔条 swap 后窗体不铺满：** ADB 可见 Secondary 上任务窗口帧仍停在旧宽度（如 386×480，而 VD 已是 436×480）。`moveRootTaskToDisplay` + `VirtualDisplay.resize` 后 OneUI 常不重算 Window frame，且全屏根任务上 `resizeTask` 无效。swap 后对两 VD 做 1px nudge 强制配置下发并 re-front 栈顶；UI 侧乐观镜像 `1-ratio`，`SPLIT_STATE_CHANGED` 带上 `EXTRA_RATIO` 同步 TextureView。

- **全屏背后窗 Recent 置顶不生效：** 第二分屏全屏时点第一分屏栈内应用，列表会置顶，但 peel 切回后仍显示旧应用。原因是背后 VD 的 ATMS 顶滞后，`restoreFocusAfterFullscreen` / `getPanePackage` / persist 刷新都按 ATMS 旧顶把 `PaneAppStack` 写回去。改为全屏 focus restore 走 `promoteStackFronts`（栈顶优先）；`getPanePackage` 在栈顶仍存活时不再被 ATMS 降级；persist 刷新保留 intentional 栈顶；背后窗 `startActivityOnPane` 置顶后把焦点还回可见全屏窗。

- **栈顶记 Google、画面仍是高德：** 三星 OneUI 上 `getAllRootTaskInfosOnDisplay` / dumpsys 常为 **顶→底**（可见任务在前），代码却按 AOSP 习惯用 `lastOrNull` 当栈顶，把埋在底下的 Google Maps 写成 front，并用错误的 `isTaskTopmost` 让失败的 `bringTaskToFront` 误报成功。改为优先认 `RootTaskInfo.visible`，并把列表规范成底→顶后再同步栈 / Recent / persist；高德 `MainMapActivity` 重排不再强加 `CATEGORY_LAUNCHER`（LAUNCHER 仍是 `UsbFillActivity`）。

- **栈顶记高德、画面仍是 Google 地图：** 高德 LAUNCHER 是 `UsbFillActivity`，VD 根任务却是 `MainMapActivity`；`bringTaskToFront` 用 MAIN/LAUNCHER REORDER 对不上现有 task，却仍 `moveToTop` 写栈。改为优先按 task `topActivity` 重排并校验真成顶，失败则清僵尸再冷启，不再把失败切换写成栈顶。
- **同栈切换被写回栈底：** `getAllRootTaskInfosOnDisplay` 为底→顶，但 `getPanePackage` / `refreshPanePackagesFromAtms` 误用 `firstOrNull` 当栈顶，点选切换后 bookkeeping 与 `promote` 会把底层应用抢回前台。改为 `lastOrNull`；`bringTaskToFront` 补 `setFocusedTask` + MAIN/REORDER 回退；Recent 同栈点选/拖顶走 `startActivityOnPane`（front-existing）。

### Changed
- **文档 / 命名对齐：** 手机悬浮 UI 已彻底移除；`DisplayWindow` 重命名为 `DisplaySessionPolicy`（仅 Delay Destroy + keep-awake）。同步 `AGENTS.md`，去掉过时的 `SHOW_PHONE_OVERLAY` 描述。
- **Recent 栈列紧凑 + 点选置顶：** 左/中列每项均分高度，去掉 9:16 高卡片，三应用同屏无需上下滚；整项（含灰底）点击即 `startActivityOnPane` 拉到栈顶并关闭面板（Close 仍只关任务）。
- **Version bump to `0.24#17.4-r9`** (`versionCode` 3065)。用户说明见 `RELEASE_NOTES_0.24-17.4-r9.md`。

### Verify (真机)
1. 左栈依次加 3 个应用，切顶无需重新加载，画面状态保持；三应用同屏可见、无需滚动
2. 点非栈顶应用（图标/标题/灰底均可）→ 立即置顶显示并关掉 Recent
3. 加第 4 个 → 栈底旧应用被关掉，新应用在顶
4. 中↔左滑动移动单应用；满栈挤出正确
5. 分隔条 swap 后两栈整体对调且画面正确；两侧应用窗口铺满各自 VD（无黑边/旧尺寸残留）
6. 断线 &lt;180s 重连 / 冷启动 restore：栈序与栈顶恢复
7. Close 栈顶后自动显示下一应用；关光后出现空窗选择器
8. 同应用不能同时出现在左、中两栈
9. 左栈同时有高德+Google：画面是谁，`LastSplit` left / left_stack 末项就是谁（可用 `settings get global aadisplay_last_split_left` 对照 `am stack list` 的 visible）
10. 第二分屏全屏 → 打开堆栈 → 点第一分屏非栈顶应用 → peel 切回第一分屏：应显示刚置顶的应用
11. 全屏 peel：长按仅震动，松手后才出 Recent；再点 peel 仍灵敏（无「点不动」粘滞）
12. 全屏 peel 拖出分屏：两侧应用铺满各自 VD；`dumpsys window windows` 中 Requested 宽应等于 frame/VD（无 Requested 卡在中间宽度）

## 0.24#17.4-r8

### Changed
- **隐藏桌面图标：** `MainActivity` 改为 `MAIN` + `CATEGORY_INFO`（去掉 `LAUNCHER`）；激活状态页从 LSPosed → AADisplay 打开。
- **移除手机侧悬浮框：** 删除悬浮控制布局与 inflate 路径；会话策略（Delay Destroy 180s、keep-awake）仍在无头路径运行，断开后仍会如期释放双 VD。
- **Version bump to `0.24#17.4-r8`** (`versionCode` 3064)。

## 0.24#17.4-r7

### Changed
- **r6→r7 逻辑收敛（行为冻结）：** peel 命中几何收拢到 `SplitPane.peelHitContains`；`touchAaDisplay` 的 ATMS 1..64 改为会话一次性门闩；Facet rail reclaim 去掉 10s 推迟链，依赖「永不 GONE DecorView」不变量立即折叠 + 一次 layout settle；`reportAaUiDisplayId` 在 surface 就绪时补报。不改贴边 peel / 双 VD 全屏 / GPU 预览 / AutoOpen 重试语义。
- **全屏 peel 拖动手把位置 / 条宽：** 全屏把手只作入口；一旦拖动即 morph 成与分屏相同的通长缝+三点，clip 缝也用同一套 `DIVIDER_DP` 几何。分屏条 elevation 用空 outline，去掉周边 Material 阴影。
- **全屏 peel 把手对齐：** 命中区不再通高/通宽（避免 elevation 投出第二条“分屏条”阴影）；短胶囊与命中框同中心贴边。
- **全屏 peel 短胶囊把手：** 全屏不再画通高/通宽加亮缝（易像坏屏亮线），改为外缘吸附的抽屉式短把手（内侧圆角）+ 三点；深色半透明底 + 浅描边/点，亮暗画面都更好认。两端触摸穿透到全屏 app。点按切换 / 向内拖退出 / 长按最近任务；**贴真左/顶缘**（不再 inset 80dp 悬空）。Coolwalk rail steal 在 peel 命中带改走 `touchAaDisplay` 注入 AaDisplayActivity，避免贴边后点不到。
- **车机合成减负：** 分屏 TextureView 标为不透明；全屏时垫后 pane 用 `INVISIBLE`（不断 Surface）跳过合成，背后导航/直播仍继续渲染。
- **分屏条拖动 GPU 预览：** 拖动中不再每帧改 pane `layoutParams` / TextureView 尺寸；分屏用 scale+translate 预览，全屏 peel 用 `clipBounds`。松手再 layout + 一次 VirtualDisplay.resize。
- **触控注入改为 oneway AIDL：** `touchPane` / `touchPrimaryPane` 不再阻塞 AA / `:car` UI 线程等 `injectInputEvent`；DOWN 不再额外打 `setFocusedPane`（服务端已设）。**安装后需重启**，否则新 client 等不到旧 system_server 的 two-way reply。
- **Coolwalk 栏 steal 缓存全屏：** MOVE 不再同步查询 `splitFullscreenPane`；DOWN 查一次，并用 `ACTION_SPLIT_STATE_CHANGED` 更新缓存。
- **Version bump to `0.24#17.4-r7`** (`versionCode` 3063)。

### Fixed
- **全屏 peel 松手比例对不上：** 过退出阈值后原先一律恢复进全屏前比例，预览却按手指位置画，松手后要二次拖。改为把松手比例 clamp 后写回 UI + `setSplitRatio`（仍先 `setSplitFullscreen(NONE)`）。
- **AutoOpen 调了但进不去 AaDisplay：** 收左侧 rail 时 `applyZeroWidthGone` 沿父链把 `DecorView` 也 GONE，Coolwalk `CarSystemUiControllerService.a()` 对 OEM 启动变成静默空操作。改为禁止折叠 DecorView / window root，并保留 AutoOpen 分档重试窗口（reclaim 不再依赖 10s 推迟）。
- **system_server SIGSEGV（进车机直接重启）：** `SplitPresentationGuard` 对已无 Surface 的 `WindowState` 调 `removeImmediately` → OneUI `SurfaceControl.Transaction.reparent` 空指针。改为先走 `WMS.removeWindow`，并在无 live SurfaceControl / 已 `mRemoved` 时跳过 `removeImmediately`。
- **全屏 peel 触控条进导航栏点不动：** `AaDisplayActivity` 的 presentation VD 是 `FLAG_PRIVATE`（应用 uid），system_server 的 `DisplayManager.getDisplays()` / `getDisplay(id)` 都枚举不到。上报的 id 若再经 `getDisplay` 校验会被误丢，`touchAaDisplay` 一直 “display not found”。改为信任 `reportAaUiDisplayId`；ATMS 扫任务仅作会话一次性兜底（失败即门闩，不在每次 touch 盲扫）。
- **AA `:projection` 崩于 `RailStatusBarFragment` / `status_bar`：** 收左侧 rail 时 `removeView`/重挂载拆掉了 Coolwalk 的 `R.id.status_bar` 容器，FragmentManager 报 `No view found for id …/status_bar`。改为原地 GONE/零宽折叠，不再拆树。
- **全屏记忆进车机只剩「点击选择应用」：** 恢复 `LastSplit` 全屏时，`initViews` 过早把垫后 pane 的 TextureView 设为 `INVISIBLE`，第二个 Surface 永不就绪 → 双 VD 不创建 → 自动拉起记忆应用失败。Surface 未齐前保持双 pane 可见，创建后再隐藏垫后层。
- **部分机型 Coolwalk 菜单栏触控被误吞（r6 回归）：** `:car` 用瘦长几何扫 DisplayManager 时，竖屏手机主屏（如 1080×2340）会被当成 FacetBar 并锁死 `displayId=0`；`CarDisplayId` 反射又扫到 `describeContents()==0`，整屏 HU 触控被 steal 后 `param.result=null`，原栏点不动。改为：禁止 DEFAULT_DISPLAY；优先 named FacetBar；无 LayoutInfo 只用绝对窄条（≤120px）；`CarDisplayId` 只认白名单 accessor；binder 注入失败不吞事件。`setSplitFullscreen` AIDL 挪到接口末尾以免旧 Stub 事务号错位。
- **全屏后媒体无声（抖音 LivePlay）：** 进全屏双 VD resize 触发三星 `ExtraDisplayController.positionChildAt`，垫后 pane 丢失 top-resumed / window focus → 抖音 `silence audio`。全屏切换后对两 pane `moveTaskToFront` + `setFocusedTask`（0/120/400ms）；仅 resize 尺寸真正变化的一侧。

## 0.24#17.4-r6

### Added
- **双 VD 全屏（一显一隐）：** 拖分屏条越过左右/上下边缘阈值后松手，一侧铺满、另一侧叠在背后继续渲染（开车导航全屏 + 影音背后；停车可反过来）。两 VirtualDisplay 都保持满屏缓冲，不销毁背后任务。全屏时分隔条变为贴边 peel（固定左/顶缘，inset 避开 Coolwalk rail）；向内拖 peel 过阈值退出并恢复进入前比例；点按 peel 切换可见全屏 pane；长按仍打开最近任务。`LastSplitStore` 持久化 `fullscreenPane`（ratio 仍存分屏比例）。Coolwalk rail steal 在全屏时注入可见 pane，不再固定 PRIMARY。

### Fixed
- **全屏/分屏左侧原导航栏（Coolwalk rail）触控失效：** FacetBar VD 常保留各车机自己的宽度但窗口 GONE，Coolwalk 仍把该带触控打进去 → InputDispatcher 直接丢弃。steal 改为优先按 **目标 display**（FacetBar / 瘦长 rail 几何）整段截获，再用 **观测到的 rail 宽度**（`content_bounds` / FacetBar VD，不用写死 px、不再 0.9 收缩留死缝）做 x 带兜底；注入前 `rewriteMotionEvent`（uptime + TOUCHSCREEN），全屏仍打可见 pane。

### Changed
- **全屏 peel 把手固定左侧：** 点按切换可见全屏 pane 时分隔条不再左右跳；左右分屏始终贴左缘（inset ~80dp 避开 Coolwalk LHD rail，方便驾驶员够到），上下分屏贴顶缘。向内拖仍退出全屏；全屏时线条/三点略加亮加粗便于找。
- **分屏容器改为 FrameLayout 定位：** 以显式宽高/边距替代 LinearLayout weight，便于全屏叠层与 peel 预览共用同一套 pane 视图。
- **Version bump to `0.24#17.4-r6`** (`versionCode` 3062)。

## 0.24#17.4-r5

### Changed
- **最近任务去掉缩略图：** 不再通过 ATMS `getTaskSnapshot` 拉硬件截图；卡片只保留图标 + 名称 + 灰色底板。打开 Recent 时 Binder 更轻，车机列表更不容易卡。
- **关闭按钮放大居中：** 灰色卡片中央放 56dp 圆形 Close；点卡片空白不再打开任务（只点图标/标题才切换），减少车机误触。
- **去掉 Material / AppCompat：** Main / AA / 悬浮窗改用平台 `Theme.AADisplay`；去掉 `material`、`rikkax.appcompat`，并拦截传递依赖的 `androidx.appcompat`。APK 更瘦，主题路径更短。
- **依赖与打包精简：** 自实现 ViewBinding inflate，去掉 `ViewBindingUtil` 与未使用的 Lifecycle；打包排除 `DebugProbesKt.bin` / `kotlin-tooling-metadata.json`；开启 R8 optimized resource shrinking。
- **清理无效钩子：** 删除无操作的 `isCallerAllowedToLaunchOnDisplay` 补丁；移除 `AaBasicsHook`（Play 安装来源伪装，当前 AA 路径不再需要）。
- **Version bump to `0.24#17.4-r5`** (`versionCode` 3061)。

## 0.24#17.4-r4

### Fixed
- **分屏分隔条两端误触交换：** 分隔条加宽命中区原先盖住虚拟屏四角/边角控件；两端留出 inset 不消费触控，避免点到角上被当成点分隔条而交换分屏。

### Changed
- **减轻日志压力：** 热路径减少噪声日志，降低连接/分屏期间的 log IO。
- **主题逻辑精简：** 收敛多余 theme 资源与包装，Main / AA / 悬浮窗主题路径更直接。
- **移除 `OtherHook`：** 删除无效的状态栏高度补丁，并从 `xposed_scope` 去掉 `com.autonavi.amapauto`；模块仅注入 System Framework + Android Auto。
- **`Utils` 包对齐：** `log` / `logDebug` 归属 `io.github.nitsuya.aa.display.xposed.util`。
- **Version bump to `0.24#17.4-r4`** (`versionCode` 3060)。

## 0.24#17.4-r3

### Fixed
- **Douyin LivePlay covers the other pane + 方控 dead (r3):** LivePlay uses MediaRouter / `createWindowContext(TYPE_PRESENTATION)` to attach a fullscreen presentation onto the sibling AA VD (e.g. task on SECONDARY while PRIMARY/高德 is covered and keys have no focus sink). Drop `VIRTUAL_DISPLAY_FLAG_PRESENTATION` on AA VDs; block foreign presentation attach/`addWindow` in `AndroidHook.PanePresentationGuard`; harden `SplitPresentationGuard` eviction (`removeImmediately`, dynamic delays). Before key inject, evict + bring task to front.
- **方控「下一曲/上一曲」切直播间 (r3):** on live-style top activities, `MEDIA_NEXT`/`MEDIA_PREVIOUS` inject a vertical fling using **current** VD `getRealSize` (split ratio / resize safe) instead of media-session fallback (which was driving QQ 车载音乐). Feed still uses normal media keys. After pane swap, resolve the LivePlay VD across both panes (do not trust `mFocusedPane` alone — focus can stay on 高德 and wrongly drive QQ).

### Changed
- **Version bump to `0.24#17.4-r3`** (`versionCode` 3059).

## Historical notes (pre-r3 / dual-VD era, already shipped)

> 以下条目已随 r2–r3 及更早发版合入；保留作考古，**不是**当前 Unreleased。真未发内容见文件顶部 `## Unreleased`。

### Changed
- **Dead-code sweep:** drop unreachable `RecentTaskColumnAdapter.clearItem`, empty steering-wheel `EXTRA_TYPE==2` arm (old screen-control), unused `rewriteMotionEvent(preserveMeta=true)` path; collapse status-only MainActivity template (no empty AppBar / `activity_base_root_2`, unused Base* init hooks).

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
- Restore Last Split restores ordered per-pane stacks (`*_stack` CSV, max 3) plus tops; divider ratio apply is still best-effort after pane settle.

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
