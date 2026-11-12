# 最小代码面审计（可安全删减清单）

日期：2026-02-19 · 范围：`aa-display/src`（133 文件）+ `lib-stub` · `vendor/aare-aap` 为 vendor 派生协议库（13 文件 / 2934 行，含 license 头），**不在删减范围**。

方法：全仓词法引用扫描（3 轮传递消解）+ AIDL/资源/属性语法（Kotlin `.foo` ≙ `getFoo()`）人工复核。
**本文件只是清单，未改任何代码。** 每项落地前仍须按 AGENTS.md §5 过全局链路评估 + `:aa-display:assembleDebug` + 真机联调。

## A. 高置信死代码（零引用，✅ 已于 2026-02-19 删除，assembleDebug 通过）

落地时连带清理的传递孤儿：`SPEECH_CHANNEL_MASK`（仅声明）、`PLAYBACK_CAPTURE_USAGES`（仅 KDoc 引用）、重复 KDoc 块、2 个孤立 import（`PixelFormat`/`AudioPlaybackCaptureConfiguration`）、`RtspSession` 的 `onCadenceGap = null` 赋值。Audi modesChanged 互斥警告浓缩为 realign 块上一行注释，防后人加回钩子。

| # | 位置 | 内容 | 说明 |
|---|------|------|------|
| A1 | `xposed/carplay/CarPlayAudioCapture.kt:1280` | `openPlaybackCaptureRecord(...)`（~40 行） | 全仓 0 调用；playback-capture 路径已弃用（现走 RemoteSubmix + field-poke bind） |
| A2 | 同上 `:1325` | `releaseSeedRemoteSubmix(seed)` | 0 调用 |
| A3 | 同上 `:1335` | `attachProjectionVirtualDisplay(proj, handler)`（~50 行） | 0 调用。注意其内部调用 `releaseProjectionVirtualDisplay()`——后者另有 4 处**活**调用，勿连带删 |
| A4 | 同上 `:1960` | `ensureRemoteSubmixDeviceSeedLocked()`（~25 行） | 0 调用；KDoc 自述 "field-poke bind no longer depends on this"，调用点已在早前重构移除 |
| A5 | `carplay/CarPlayRtspSession.kt:1937` | `align16(v)` 单行 | 0 调用 |
| A6 | `carplay/CarPlayBonjour.kt` | `fun startDiscovery() = startCtrlDiscovery()` 别名 | 0 调用（"predates the AirPlay split" 遗留） |
| A7 | `carplay/CarPlayAacAudioEgress.kt:76` | `@Volatile var onCadenceGap` 钩子 + `:236` 的 invoke 块 | 全仓唯一赋值是 `RtspSession` 的 `= null`，`?.invoke()` 永假 → 字段 + 调用块均死 |
| A8 | `res/values/ids.xml` | `drag_last_x` / `drag_last_y` / `pane_was_focused` | 0 引用（代码与 res 互引均无） |

小计净删 **174 行**（含 AGENTS.md 准则 +1）。删除 A1–A4 后 `projectionVirtualDisplay`/`projectionImageReader` 字段永不被赋值、`releaseProjectionVirtualDisplay()` 变 no-op —— 属**二期**清理（触及 4 处活 teardown 调用点，需单独真机回归 AAP/CP 断连路径），不与 A 批混删。

## B. 已删：5 个 AIDL 方法全链（✅ 2026-02-19 删除，assembleDebug 通过）

`ICoreManager.aidl` 42 → 37 方法。删除链：AIDL 声明 + `CoreManager` 客户端 shim + `CoreManagerService` override + 传递孤儿：

- `SplitDisplayController`：`moveTaskIdAsync` / `moveTaskIdToPaneAsync` / `moveTaskIdOnHandler` / `reorderPaneStack` / `reorderPaneStackOnHandler`（-103 行）
- `SplitOwnership.clearPanePackageForTask`（唯一调用者在上面，连带删）
- `DisplayDpiStore` HU-报告密度持久化整对：`saveLastHuReported`/`loadLastHuReported`/`loadHu*`/`parseHuReportedDpi`/`cachedHuReportedDpi`/`invalidateCache`/两个 key（-81 行；旧设备残留的 `HuReportedDensityDpi` prop / Settings 值无人再读，无害）
- `CoreManagerService.mLastReportedHostDensityDpi` 字段；`resolveReportedHostDensityDpi` 退化为 `return clientReported`

保留：`controller.setFocusedPane`（`touchPane` 活路径）。⚠️ ordinal 前移：Binder 两端同 APK 且改后需重启，无跨版本风险；`moveTaskId`（Recents 滑到手机）当前无调用入口，如未来恢复需重新加回 AIDL 链。

## C. 复核为活代码、勿删（扫描疑似项）

- `CoreApi.kt`（顶层 `val`）、`xposed/CoreManager.kt`、`xposed/util/Utils.kt`（顶层 `fun log/logDebug` 被广泛 import）
- `lib-stub/` 全部 9 个隐藏 API stub（`IInputManager`/`DisplayManagerHidden`/`ActivityManagerHidden`/`ITaskStackListener`/`IActivityTaskManager`/`TaskSnapshot`/`InputFilter`/`IWindowManager`/`PowerManagerHidden`）——逐一确认在 `Instances.kt`/`SplitVdLifecycle.kt`/`SplitTaskStackListener.kt`/`PhoneHidRedirect.kt` 使用
- 全部 12 个非 values 资源（5 个初扫疑似项均被 layout/manifest/mipmap 互引证实存活）
- `EgressChannel` LEGACY_PREFS 迁移、`HeadUnitNetwork.joinLegacy`（SDK<Q 分支，minSdk 33 下为死分支但属防御性 compat，删前自定夺——**本次未列入**）
- `CarPlayAacAudioEgress` 诊断计数器（silenceSends 等，文件内有周期性读取）

## D. 备注：`assets/xposed_init`

manifest 已补 `xposedinit` meta-data（修复 LSPosed 显示 Legacy）。资产文件 `src/main/assets/xposed_init` 此后 LSPosed 不再读取，仅对原版 Xposed 有意义；类名双写一处，属标准兼容写法，保留不动。

## 结论

代码库整体很紧：**133 个源码文件零整文件死代码**，可安全删减集中在 CarPlay 音频捕获的一次重构残留（~130 行）。无结构性冗余可合并；后续按 AGENTS.md §5.4 维持"新增前找既有实现"即可。

## E. 2026-11 轮新发现（CarPlayAudioCapture 拆分 + 泄漏修复，详见 [CARPLAY_AUDIOCAPTURE_SPLIT.md](CARPLAY_AUDIOCAPTURE_SPLIT.md)）

| # | 位置 | 内容 | 状态 |
|---|------|------|------|
| E1 | `CarPlayAudioCapture.tryUpgradeToAudioPolicyRenderLocked` 失败分支 | **Bug，已修**：`renderArm.projection`（register-only）已赋入字段，失败回滚只释放 record×2/policy，projection 未 `stop()`；`enableLocked` 用局部 null 覆盖字段 → system 侧滞留至 binder 死亡。回滚补 `stop()`+置空。该分支当前不可达（arm 无条件置 `audioPolicyRenderUnavailable=true`），属潜伏风险修复，现活路径零影响 | ✅ 已修复（assembleDebug/Release 通过） |
| E2 | `CarPlayAudioCapture.ignoringProjectionStop` | 只写不读 flag（声明 + 5 对 true/false 写 = 11 行；其守护的 MediaProjection onStop 回调链早已不存在） | ✅ 2026-11 已删（只写无消费者，零行为影响） |
| E3 | `CarPlayAudioCapture` `projectionVirtualDisplay`/`projectionImageReader` 字段 + `releaseProjectionVirtualDisplay()` | A3（`attachProjectionVirtualDisplay`）删除后字段永不被赋值；现存 5 处调用点（enableLocked×2 / startMediaRecordLocked / disableLocked / tryUpgrade）全 no-op | ✅ 2026-11 已删（2 字段 + no-op 函数 + 5 调用点 + 2 import，净 -29 行；调用点全 no-op 故零影响，下一轮真机可顺带过 CP 断连） |

## F. 2026-12 全量重扫（140 文件 / 43.7k 行，✅ 已删，assembleDebug 通过）

方法：全仓词法引用扫描（fun/val/var 945 候选 + 顶层类/嵌套类 87 候选 + AIDL 39 方法）逐一人工复核。
整类/整文件零死代码（`XposedInit`/`AapUsbAccessoryActivity`/`ExtractError.*`/`ConsumeResult.Packet` 等均为 manifest/限定名误报）；AIDL 初扫 4 个“零引用”方法实为 `CoreApi.xxx` Kotlin 属性语法（≙ `getXxx()`，词法不可见）的活调用，全部保留；资源（drawable/strings/xml）全部有引用。真死代码仅 6 处小残留：

| # | 位置 | 内容 | 状态 |
|---|------|------|------|
| F1 | `aap/AapDisplayBridge.kt` | `val videoWidth/videoHeight get() = width/height` 别名 | ✅ 已删（-2 行） |
| F2 | `egress/ChannelOrchestrator.kt` | `fun isSwitchInProgress()` getter | ✅ 已删（`switchInProgress` flag 本身有活读，保留） |
| F3 | `xposed/CoreManagerService.kt` | `DisplayProfile.isLandscape` 计算属性 | ✅ 已删 |
| F4 | `xposed/carplay/CarPlayAudioCapture.kt` | `fun isDesired()` getter + KDoc | ✅ 已删（`desired` flag 活，保留） |
| F5 | `aap/cert/AaApkKeyExtractor.kt` | `Candidate.saltOff`/`certOff` 两字段 + 2 处构造参数 | ✅ 已删（从未被读；`cand.dex/salt/enc` 活） |

合计净 **-12 行**，不触及 Binder 契约 / ordinal / 反射 / 持久化 key，零行为影响。

## G. 2026-12 轮：`ClusterArtStore` Bitmap 缓存死链（✅ 已删，assembleDebug 通过）

历史遗留：早期消费方曾直接取 Bitmap，后统一为 JPEG 字节跨进程（`loadJpegBytes` / `CoreApi.getClusterArtJpeg`），Bitmap 解码缓存整链成孤儿。

| # | 位置 | 内容 | 状态 |
|---|------|------|------|
| G1 | `ClusterArtStore.loadBitmap(resolver)` + `loadBitmap(context)` | 全仓 0 调用 | ✅ 已删 |
| G2 | `cachedBitmap` / `cachedRevision` 字段 + `evictBitmapCache()`（+ `clear()` 内调用） | 仅 G1 读写 | ✅ 已删 |
| G3 | `readRevision` / `artFile` 可见性 `fun` → `private fun` | 0 外部引用（消费方自读 Settings key / 常量） | ✅ 已降 |
| G4 | `METADATA_KEY_DISPLAY_ICON` 常量 | 0 引用（仅 `_URI` 变体存活；顺带清理） | ✅ 已删 |

净 **-52 行**。消费链不变：`CoreManagerService` → `loadJpegBytes`；`AapNowPlaying` / `CarPlayNowPlaying` 自读 `SETTINGS_ART_MEDIA_ID` / `SETTINGS_ART_REVISION`；Settings.Global key 与 `/data/system/aadisplay_cluster_art.jpg` 文件契约均未动。

## H. 2026-12 轮：CarPlayAudioCapture 过期链路清理（✅ 已删，assembleDebug 通过）



背景：`01a9e88`（音频通道恢复 REMOTE_SUBMIX）arm 时**无条件**置

`audioPolicyRenderUnavailable=true`，但 watchdog 的 ring 溢出分支仍走 in-place RENDER

升级（`88533d6` 把 `11ac603` 引入的 force rearm 恢复替换掉了）⇒ 升级门恒关、整条

RENDER 升级链路不可达；同时 11ac603 的溢出恢复（force rearm）随之静默丢失。



| # | 内容 | 状态 |

|---|------|------|

| H1 | `tryUpgradeToAudioPolicyRenderLocked`（~90 行；唯一调用方即恒死的溢出升级分支） | ✅ 已删 |

| H2 | `audioPolicyRenderUnavailable` 字段 + arm 置 true / disarm 置 false / 升级置 false 三写点 | ✅ 已删 |

| H3 | watchdog 溢出分支：恒死 in-place 升级 → **恢复 11ac603 原行为** force rearm（teardown + 重建 tap + 清 ring；REARM_MIN_GAP_MS 限速、清环后 dropDelta 归零，自愈限频） | ✅ **本轮唯一行为变化**（旧行为为 no-op） |

| H4 | `mediaProjection` 字段（现行 arm 恒 null）+ `openSpeechRecord` 读字段 | ✅ 已删（改传 null ⇔ 现值，等价；Acquire 仍经 `CarPlayAudioPolicy.openSpeechMix` 活用） |

| H5 | `startMediaRecordLocked` 休眠 playback-capture fallback + Pair 第二值（恒≡入参） | ✅ 已删（签名改直返 record） |

| H6 | 不可达 "audio-policy-render" 分支：watchdog render 静音日志、`dropStandaloneSpeechPolicy` 的 render 共享判据、applyArmMute render 分支 | ✅ 已删 |

| H7 | 过期注释/KDoc：`Never deferred-upgrade away`、`Next session may retry RENDER`、`to retry audio-policy-render`、`A projection left over`、类头 RENDER/APC deferred-upgrade 段等 | ✅ 改写 |



保留（有意 dormant，未动）：`CarPlayPhoneMute` 整机 + 类内 `playback-capture` mute 分支

（split 文档明确保留的 dormant 策略，另见 [CARPLAY_AUDIOCAPTURE_SPLIT.md](CARPLAY_AUDIOCAPTURE_SPLIT.md) §3.3）。



| # | 遗留记录 | 说明 |

|---|---------|------|

| H8 | `CarPlayAudioPolicy.openRenderArm` + `AudioPolicyRenderArm` | H1 删除后全仓**零引用** helper。是否删除待与 split 文档 unused-helpers 保留决策一并自定夺，本轮未动（跨文件） |



⚠️ 回归重点（H3 行为变化）：无线 CarPlay 音乐播放 + system_server 负载尖峰（ring 溢出

≥50KB/30s）→ 应见 `force rearm` 日志 + ≤1 次短重建，**不得**循环 rearm。
