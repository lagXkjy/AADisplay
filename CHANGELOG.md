# Changelog

## Unreleased

## 0.24#17.4-r14

### Added
- **手机蓝牙键鼠 → 焦点窗 VD：** `PhoneHidRedirect`（system_server）。AA 会话活跃时偷物理键盘 / 鼠标事件并 `inject` 到焦点 VirtualDisplay（鼠标主键按触控注入）；Delay Destroy 期间归还手机。方控媒体键路径不变。折叠屏：光标会先画在合盖外屏 / 展开主屏，需 `setVirtualMousePointerDisplayId` 绑到 AA VD + 隐藏实体屏光标；绝对坐标按 `event.displayId` 缩放。W7023：AA VD 缺 Input viewport（touch NONE）时 override 仍落主屏——加 `SUPPORTS_TOUCH` + hook `setDisplayViewports` 注入窗 viewport + `forceHideCursor`。双窗：光标在壳画布连续移动，可停在分隔带（不传送到另一 VD）；分隔带拖比例 / 长按堆栈 / 点按对调走 `touchAaDisplay`；堆栈打开后触控继续打壳。键盘：Ctrl+方向/WASD 经壳改比例再 settle VD，Ctrl+R/Tab 开堆栈，Ctrl+S 对调。中键切换对侧窗。壳上 `HidCursorOverlayView` 自绘指针。

### Changed
- **Recent 逻辑重构：** VD 列以 `PaneAppStack` 为顺序唯一源（ATMS 只补 taskId/图标）；新增 `RecentTaskProvider` / `RecentTaskActions` / `RecentTasksCoordinator`；拖拽结束走 `reorderPaneStack` IPC 持久化整栈；操作后重载快照、去掉跨列乐观更新；已打开时再按 Ctrl+R/长按只刷新不关闭；ATMS 变更经 `ACTION_RECENT_TASK_DIRTY` 防抖刷新。
- **Recent 回归修复：** `RecentTaskProvider` 读路径不再 `trimToAlive` 误清栈，并补 ATMS 在途任务；关闭恢复乐观移除 + 过滤 `mExplicitlyClosedPackages`；× 按钮防 `ItemTouchHelper` 抢触控；`ensurePanePackages` 跳过已关闭包；用户点选/选择器启动时清除关闭标记；Recent「添加应用」改直接调 `AaMainFragment.showAppPickerFromRecent`。
- **全屏下点选即切侧：** Recent / 应用选择器 `startActivityOnPaneForUser` 若当前全屏在对侧，promote/launch 成功后直接 `setSplitFullscreen` 到目标窗。
- **Recent VD 列点空白关面板：** 左 / 中 / 右列空白 tap 均可 `onExit`（不再仅右列）。
- **AutoOpen 短链失败兜底：** full-bleed 短链耗尽且仍未 `AA_DISPLAY_SHOWN` 时武装长 AutoOpen；`IllegalStateException` / `NullPointerException` 均按控制器 not-ready 处理。
- **Version bump to `0.24#17.4-r14`** (`versionCode` 3074)。用户说明见 [docs/archive/RELEASE_NOTES_0.24-17.4-r14.md](docs/archive/RELEASE_NOTES_0.24-17.4-r14.md)。

### Fixed
- **LineageOS Android 16 分屏 VD 与手机 PowerGroup 绑死：** `PaneDisplayGroupForce` 将 AA pane LogicalDisplay 拽出 default DisplayGroup 0（否则 `OWN_DISPLAY_GROUP` / `ALWAYS_UNLOCKED` 被忽略，keyguard / ColorFade 同步到车机窗）；挪组后就地同步 `mOverrideDisplayInfo.displayGroupId`（勿清 `mInfo`，A16 `DisplayInfoProxy` 赋 null 会重启 system_server）。
- **虚拟屏软键盘高度为 0（仅「收起键盘」）：** 蓝牙硬键盘连接时会话期临时打开 `show_ime_with_hard_keyboard`（销毁后恢复）；去掉 `OWN_FOCUS`；IME policy 仅 FALLBACK→LOCAL，不改写 HIDE/INVALID；壳芯片只认 VD 上真实 IME 窗（不再信 `mInputShown`）。
- **「收起键盘」点到空格：** HU 触控仍落 TextureView——芯片矩形内改走 `hideIme`；蓝牙鼠标经 `HidSplitLayout` 芯片几何同样收键盘。
- **Recent / 显式关闭不再 forceStop：** close / swipe-off 仅 ATMS `removeTask`；`forceStopPackageAsUser` 保留给 Delay Destroy（180s）`onDestroy` teardown。
- **冷启动 / 主线程忙时长按分隔条误对调：** 长按 Recent 以输入 `heldMs≥550ms` 为准（Handler 超时可补震）；去掉 480–550ms fallback tap-swap，避免假长按被交换。
- **分隔条点按交换 / 拖动 settle 抖动：** tap-swap 窗口 420→480ms，长按前（<550ms）无 slop breach 也交换；ratio 微变优先 swap 而非 settle；点按交换设 `ratioSettling` + `swapInFlight` 避免重复 layout/rebind；拖动松手 Shell-first layout → `setSplitRatio`，同步清 GPU 预览，去掉 300ms occupancy / 600ms settling。
- **分屏比例 settle 错位 / 黑边：** Shell 与 VD 共用 `SplitPane.dividerPx`；ratio-settle 仅对 **变窄** 的 pane 做 WM nudge（减黑边且避免双 pane 1px 抖动风暴）。
- **Open/Close 交互卡顿（精简热路径）：** ATMS 栈变更的 notify + Recent dirty 合并为单次 `scheduleStackSettle`（200ms）；`AaMainFragment` occupancy 未变时跳过 overlay 刷新；Recent 列 `DiffUtil` 替代全列 `notifyDataSetChanged`；显式 close 后跳过一次 debounced dirty reload（乐观 UI 已更新）。
- **应用记忆恢复失效（Recent 重构回归）：** VD 显式关闭只记 `mExplicitlyClosedPackages` + debounced persist，不再 `LastSplitStore.dropPackage` 清 durable 快照；手机列关闭不再误伤分屏记忆。用户点选/Recent 启动不再 cancel 进行中的 restore/ensure。`onDestroy`/`verify` 的 force persist 跳过 ATMS refresh，避免断连时空 VD trim 栈导致写盘 skip。
- **拖分屏条 VD 内应用分辨率重建卡顿：** ratio settle Shell-first；TextureView 尺寸变化不再触发 `setPaneSurface`→ensure；`ratioSettling` 缩至 180ms 仅挡 rebind；resize 期间跳过 `onTaskMovedToFront` promote。
- **拖动分屏 / Recent 拖拽栈顺序乱（如汽水抢顶）：** `refreshPanePackagesFromAtms` 不再按 ATMS visible top 覆写栈顶；merge 保留 intentional 顺序（resize 时 ATMS 漏报 buried）；`onTaskMovedToFront` 改 `promoteStackFronts`；ratio settle suppress 延至 2s；Recent 拖拽 reorder 改同步 IPC。
- **Recent 关闭 VD 要等数秒才消失：** `removeTask` 改后台执行；显式 close 记入会话内 `mExplicitlyClosedPackages`，backfill/restore 跳过；close 路径跳过同步 Av 仲裁。incomplete persist 仅 skip 不清快照。
- **应用选择器点选闪「请选择应用」 / 卡顿：** 点选后保持乐观占位至 launch 确认；先 `hide()` + 乐观占位再 `runIO` 启动；Binder/广播仍为空时忽略 downgrade。
- **交换分屏卡顿：** `swapPanes` 不再在 handler 上同步跑两侧 `ensureTasksFillDisplay`，改 80ms 后异步 settle；AvMedia 仲裁同样延后。
- **分隔条点按偶发不交换 / Recent 卡顿 / 长按误对调：** 放宽 tap-swap；未满长按超时的假长按不再交换；Recent 点选先关面板再异步 launch；图标 IPC 缩到 64px、手机列最多 8 条。
- **退出全屏后中间分屏条点不上（回归修复）：** 恢复同步 `exitFullscreen` + `applySplitLayoutWeights(force)`，并 `requestLayout`。
- **蓝牙鼠标跨 VD 跳中心 / 分隔条跳变：** `HidCursorOverlayView` 自绘指针，`forceHideCursor` + 各 VD 藏 OS sprite；不再向 pane 注入 `HOVER_MOVE`；悬停不按分隔带扩区抢路由，左键才命中 seam/peel。

### Verify（r14）
1. 手机蓝牙键鼠：窗内可点；分隔带拖比例 / 短按交换 / 长按 Recent；Ctrl+WASD / S / R；中键切窗；Delay Destroy 后键鼠归还手机
2. 全屏时在 Recent / 选择器点对侧应用，画面切到该侧全屏
3. Recent 左/中列空白 tap 可关面板
4. 偶发冷连不进分屏场景：短链后仍能靠 AutoOpen 长兜底进入
5. LineageOS A16：息屏 / 解锁后车机分屏窗不被手机 ColorFade / keyguard 拖死
6. 选应用 / 交换 / Recent 关闭无明显数秒卡顿；断线记忆仍可恢复
7. 蓝牙硬键盘接入时，VD 内输入框能弹出完整软键盘；点「收起键盘」收起（不点到空格）；断开会话后 `show_ime_with_hard_keyboard` 恢复

## Pre-r13 unsectioned notes

以下条目在 r13 打点时已在树中，但当时仅做了版本号 bump，未单独成节；保留作技术对照。

### Removed
- **藏车机媒体壳图标：** 删除 `AaClusterMediaIconHideHook`（不再 hook `queryIntentServices` 过滤本包壳）。全屏投影下桌面列表本就会闪，隐藏收益低且增加 hook 面。

### Changed
- **音视频粘性焦点（r16）：** AvMedia（音乐 ∪ 视频）正在播放时不因地图/浏览器压顶或空闲 Av 栈顶而自动丢发声权；仅在**停止播放**、**移出栈**、或**另一 AvMedia 开始 PLAYING** 时让出。同窗 `SplitBuriedPlayback` 仅在栈顶 Av 正在播时 pause 埋栈。**栈恢复 / ensure / 双窗 promote** 走 `enforceSingleSounder`（立即 + 延迟重试），多 Av 同恢只留一个发声；`resumeFront` 若已有其它 Av 在播则跳过。清理：去掉 soft-idle 选主 / 仅音乐埋栈启发式、`eligibleControllers` 无用 layout 参数、重复 isPlaying/isIdle、无调用方的 `CoreManagerService.buriedPackagesOnAaDisplays`。
- **音视频互斥 + 仪表三源重定义（r15）：** AvMedia = 音乐 ∪ 视频（抖音）。`AvMediaArbiter.pickWinner` 只保留一个发声源（焦点窗正在播的栈顶优先，否则三源 / 其它音乐 / 视频）；`pauseLosers` 停其它 PLAYING。仪表只跟 QQ 车载 / HD / 汽水里**正在播放**的那一个（`pickClusterSource`）；视频抢到发声权时清空仪表。三源彼此互斥。
- **同窗叠栈：** 栈顶 AvMedia **正在播放**时对埋栈 AvMedia 显式 `pause`；非 Av / 空闲 Av 压顶时埋栈音视频可续播。
- **FacetBar 重构文档与试验代码清理：** 新增 [docs/COOLWALK_FACETBAR.md](docs/COOLWALK_FACETBAR.md)（状态机、四路回收、profile settle、坑点）；移除 `ReconnectSizingTrace`、未使用的 `:car` presentation resize 广播、`CoolwalkRailMath` 死代码；`AaDisplayPresentationResize` 改为 AADisplay 进程懒加载 `DrawingSpec` hook 入口；同步 [EXECUTION.md](docs/EXECUTION.md) §5.4。
- **仪表歌词补进度包 +1s：** `pushPlaybackNow` 在 Store 外推整秒上再 **+1s**（clamp duration）；`getPlaybackState` 不加。见 `docs/CLUSTER_LYRIC_CLOCK.md` §5。
- **仪表歌词 T2-A 外推整秒补包：** `pushPlaybackNow` 用 `ClusterLyricStore.extrapolatePosition` 当下整秒克隆 Gearhead `AaPlaybackState`，不再重放 `play_l` 过期快照（失败 fallback T1）。见 `docs/CLUSTER_LYRIC_CLOCK.md` §5。
- **仪表歌词时钟文档：** [docs/CLUSTER_LYRIC_CLOCK.md](docs/CLUSTER_LYRIC_CLOCK.md) 更新为 **T2-A** 当前落地。
- **分屏应用选择器加速：** launchable 列表进程内缓存 + 会话预热；图标懒加载；「最近」改用 `LastSplitStore`/occupancy 包名，不再拉完整 `recentTask` Bitmap IPC。
- **热路径减负（歌词 + 触控）：** 同句歌词跳过 Settings.Global 三写（靠 5s `touch` keepalive）；LRC 按 mediaId+blob 缓存解析，300ms tick 只二分取句；`:cluster` 不再观察 `updated_ms`；`injectInputEvent` 缓存 `InputEvent.setDisplayId` Method。
- **r11-T→r12 审计收敛：** 去掉试验叠层——重连缩窗仅服务端 `shrink-auto`（删客户端 900/1700ms `lastCreate` bust）；soft-reconnect 同 profile 跳过 VD resize；AutoOpen 梯子收为 `0/1.5/5/12/24s` 且 `REARM_GAP≥末档`，保留 car-connected kick；`AaClusterLyricEgressHook` 仅改写 `aadisplay.cluster:` 壳 MEDIA_ID；歌词 `warmStart` 不再每句触发；Allowlist unknown-sources pref 仅在 pkg-bool 未命中时回退。
- **仪表歌词 / 封面 / 进度热路径：** 进度 Settings 去重（seek / 状态变化立刻写，其余最多 1s）；tick 上 `getActiveSessions` 1s 节流；Egress 200ms Fresh/Progress 缓存 + Compat `getString` Method 缓存；LRC unwrap/`mBundle` 缓存；封面 JPEG 后台线程 + `inSampleSize` + 原子 rename；快句歌词 pending flush。
- **仪表歌词主路径：** `ClusterLyricMirror` → `ClusterLyricStore` → `:cluster` `ClusterLyricMediaService` Title；Egress 为壳会话兜底。优先包 QQ 车载 / HD / 汽水（`com.luna.music`）。
- **仪表横条隐形媒体壳（免未知来源）：** `ClusterLyricMediaService` + `AaMediaAllowlistHook`（本包 pkg-bool）；Dashboard starve + Presentation 隐藏保留。
- **AutoOpen 事件驱动：** 武装后立即 + 稀疏 CAMS 重试；Hook SysUi car-connected 立刻再踢 `a(Intent)`；仅 `AA_DISPLAY_SHOWN` 停。
- **DexKit 方法坐标缓存：** gearhead 冷启缓存命中跳过 `libdexkit` 扫包；失败回退 live DexKit。
- **少打 ATMS：** `ensureTasksFillDisplay` / `bringTaskToFront`（press-key、promote）复用同一次 display snapshot。
- **热路径日志降级：** 成功与诊断用 `logDebug`；错误仍 `log`。
- **MediaCarApp 与 Dashboard UI 拆分：** 保持 MediaCarApp 出站；Dashboard VD starve + Presentation 隐藏保留。
- **重连分辨率单一结算：** `DisplayProfileSettle` 取代 grow/shrink confirm；有活 FacetBar 条带用 `HU−rail`，否则全宽；450ms rail-settle 重试。
- **左轨 ensure 窗口收敛：** FacetBar 回收 poll 从 8s/400ms 收为 2s/250ms；inject 成功即停；collapse/starve 只做一次全窗 reclaim，不再把 deadline 续满。晚到 chrome 仍靠 LayoutInfo / `windowAttach` 再武装。

### Fixed
- **操作手机时被伪熄屏强制锁屏：** `DisplaySessionPolicy` 挂钩 `PowerManagerService.userActivity(Internal)`，仅 `DEFAULT_DISPLAY` 的 TOUCH/BUTTON/ACCESSIBILITY 重置伪熄屏计时；车机 / VD 触控仍只保活虚拟屏，不续手机 idle。手机主屏前台为用户应用（如地图）时直接跳过 `goToSleep`，回到桌面 / SystemUI 后才按超时伪熄屏。
- **QQ 车载约 45s 灰屏重建：** 手机息屏心跳 `surfaces-ready` 把永久 LAUNCHER（`AppStarterActivity`）误判为 splash 并 cold relaunch；`isPackageFrontStaleOnReconnect` 去掉 AppStarter/Loading/Launcher 启发式，并对 MAIN/LAUNCHER 组件豁免。
- **仪表歌词 clear no-session：** `qqmusiccar` 无 MediaBrowserService/APP_MUSIC，`MusicAppClassifier` 判非音乐导致 `AvMediaArbiter` 选不中；已知歌词源包强制算音乐，metadata 在 duration≤0 时仍认 artist/album；布局未命中时回退到正在播放的会话；无赢家时不再对所有 AvMedia 群 pause。
- **埋栈音乐被强制停播：** 同窗地图/浏览器压在音乐上时不再 `MediaSession.pause` 音乐类会话（按能力识别，非包名白名单）；仪表歌词仍可绑定埋栈音乐源。
- **软重连左侧黑条（1280×720 / gresolution）：** `:projection` 已把 `content_bounds` 扩到全宽 1280，但 gearhead 仍用 content slot `DrawingSpec`（1173=1280−107）建 Presentation / 分屏 VD，左侧留下 107px 合成器空槽。补 hook `ResourcesImpl` **long** 资源 ID 版 dimen（`:projection` 重启后 rail dimen 不再回弹）；新增 `CoolwalkDrawingSpecWiden` 在 gearhead（`:car`/`:projection`）把 `DrawingSpec` 扩到已观测全宽；`content_bounds` reclaim 后若 presentation 已按 content slot 创建则 `scheduleFullBleedRelaunch`；已 reclaim 的重复 `content_bounds` 幂等跳过（只改 rect，不再叠 reclaim / AutoOpen，避免 `:car` 主线程 ANR）。
- **左侧导航栏黑条重连复发：** FacetBar 窗口一打 tag 就停掉回收，真正占着 ~107px 的 GhostActivity 宿主从未被扫到。改为每次 ensure / attach / collapse / starve 都扫进程内全部窗口；inject 成功即停 poll，晚到 chrome 靠 LayoutInfo / `windowAttach` 再武装。
- **左侧黑条重连后不消（content 1305 / HU 1412）：** `:car` 未把轨宽 dimen 打成 0，`GhLifecycleService` 仍发 `Rect(0,0,HU−rail)`；FacetBar 已饿成 1px，合成器留下 107px 空槽。`:car` 同样 zero dimens + VD starve/expand；`content_bounds` 把内容槽扩回已观测全宽。
- **仪表进度双外推：** 壳 session 写入原始采样 + `positionAtElapsedMs`；Egress `getPlaybackState` 写入已外推位置 + `elapsedRealtime()`；位置不超过 duration。
- **仪表换句时间反复（T2-A）：** `pushPlaybackNow` 改为 Store 外推整秒克隆 `AaPlaybackState`，消除 T1 快照 push 导致的 `0:29→0:30→0:29` 回退（窗前 natural 包若仍打架见 T2-B）。
- **封面 recycled bitmap：** Egress 解码缓存只丢引用不 `recycle`；session 封面一律 `ARGB_8888` copy。
- **歌词 MSM 空启动卡死：** `MediaSessionManager` 为空时 `started=false` 并 2s/10s 重试（上限 8）。
- **快句歌词被 200ms 节流丢掉：** 间隔内记下最新一句，到期 flush。
- **QQ / 汽水同时后台抢 Now Playing：** 双方都报 PLAYING 时，`onSessionsChanged` 与 tick 共用 1.5s freshness 死区，避免会话列表抖动闪烁；`unbind` 取消上一源的 120s `pausedClear`；跨源（QQ↔汽水）无封面时立刻丢掉旧 JPEG，同包仍保留 2s 晚到封面窗口。
- **长前奏封面切换慢：** LRC tick 在曲目尚无 JPEG 时每 1.5s 节流重试拉图（歌名不变 / 前奏滚词也能出封面）；`:cluster` 封面 revision 变化时不再走 lyric-only，确保 HU 收到带封面的完整 MediaInfo。
- **断开重连分辨率 800/720 反复错位：** 具名 `GhFacetBar` VD 饿成 `1×H`（触控仍用观测轨宽），与 `content_bounds` 扩满共用全宽真值；服务端按 rail 观测结算 profile。
- **方控长按对调：** 长按上一曲/快退开 Recent；长按下一曲/快进换分屏；去掉长按播放/暂停。
- **仪表横条歌名兜底过期：** 播放中定期 touch `aadisplay_cluster_np_updated_ms`。
- **奥迪仪表「未知专辑」：** 壳 session 补 `DISPLAY_DESCRIPTION` + Egress 向 HU/GAL `MediaInfo` 注入 `aadisplay_cluster_np_album`（Store 有专辑名但出站 album 参数为空）。

### Verify（仪表歌词 + 重连）
1. Soft-reconnect（含 1280×720 gresolution）：`content_bounds` 扩满全宽；`DrawingSpec`/profile 稳定 1280（非 1173 content slot）；日志可见 `displayProfile relocked(settle|rail-settle)` / `starve FacetBar` / `DrawingSpec ctor widen`；无左侧 107px 黑条
2. 冷连 AutoOpen：仍能进 AaDisplay；connected kick 后无 100ms 级刷屏重试
3. QQ 车载/HD/汽水横条歌词随句切换（快句不丢）；同句 hold 时 Settings 标题无每 300ms 刷、进度最多约 1s 一写；三源同时只一个播、仪表跟正在播的源；播抖音时音乐停且仪表清空；媒体列表出现壳图标可接受
4. 方控短按仍控真实播放器（Egress 不再改写 QQ Title）
5. QQ 与汽水同时后台：只跟正在播的源；切到汽水未出封面时不残留 QQ 封面；QQ 车载↔HD 同曲仍可晚到封面；暂停 120s 清空不会误清刚切过去的源
5b. AA 顶栏与奥迪仪表歌词随句刷新；换句不闪 0:00；无 `0:29→0:30→0:29→0:32` 式时间反复；真切歌才重置
5c. 音视频互斥：音乐播时抖音停；抖音播时音乐停；分屏一窗音乐一窗视频时只一个发声
6. 分屏栈切换 / Recent 置顶 / 触控滑动无明显变慢
7. 左轨触控仍能注入 AaDisplay（starve 后 hit 带宽用观测轨宽）

## 0.24#17.4-r10

### Added
- **FRX 必装 Google 应用绕过：** `AaFrxRequiredAppsHook`（`:projection` + `:car`）将 Google App / Maps / TTS 的 FRX 安装与版本检查强制判为就绪。
- **导航占位页禁用：** `AaNavFallbackHook` 仅禁用 `NavigationFallbackCarActivityService`，避免缺 Maps 时占位布局崩进程。
- **Coolwalk 空媒体卡抑制：** `AaUiHook` 将名为 `Dashboard` 的 VirtualDisplay（create/Builder/resize）饿成 1×1；`AaMediaPlaceholderHook` 禁用 `MediaCarAppService`，并在 `addView` 前隐藏残留 Presentation 窗口。

### Fixed
- **手机锁屏后全屏 peel 拉不出 / 长按无响应：** AaDisplay presentation 无 `ALWAYS_UNLOCKED`，Keyguard 会挡住 `touchAaDisplay` 注入，而双 VD App 仍可点。上报 presentation id 时 `setShouldShowWithInsecureKeyguard(true)` + 尽力打上 `FLAG_ALWAYS_UNLOCKED`；锁屏且全屏时改由 system_server 直接解析 peel（退出 / 点按切换 / 长按广播开 Recent），不依赖 presentation 命中。

### Changed
- **Version bump to `0.24#17.4-r10`** (`versionCode` 3066)。用户说明见 [docs/archive/RELEASE_NOTES_0.24-17.4-r10.md](docs/archive/RELEASE_NOTES_0.24-17.4-r10.md)。

### Verify (真机)
1. 未装 / 未更新 Google App、Maps、TTS 时，AA FRX 仍能过安装检查并进入车机界面
2. 缺 Maps 时不再落入导航占位页导致 `:car` 崩
3. 冷连接 / 重连后不再长时间闪空媒体卡（Dashboard VD）；媒体卡区域保持干净
4. 手机锁屏 + 全屏 peel：可拖出退出 / 点按切换 / 长按开 Recent；解锁后 peel 仍正常

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
- **Version bump to `0.24#17.4-r9`** (`versionCode` 3065)。用户说明见 [docs/archive/RELEASE_NOTES_0.24-17.4-r9.md](docs/archive/RELEASE_NOTES_0.24-17.4-r9.md)。

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


## Historical notes (pre-r3)

Shipped archaeology moved to [CHANGELOG_ARCHIVE.md](CHANGELOG_ARCHIVE.md).
