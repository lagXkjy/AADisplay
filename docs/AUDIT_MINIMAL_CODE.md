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
- `CarPlayChannel` LEGACY_PREFS 迁移、`HeadUnitNetwork.joinLegacy`（SDK<Q 分支，minSdk 33 下为死分支但属防御性 compat，删前自定夺——**本次未列入**）
- `CarPlayAacAudioEgress` 诊断计数器（silenceSends 等，文件内有周期性读取）

## 结论

代码库整体很紧：**133 个源码文件零整文件死代码**，可安全删减集中在 CarPlay 音频捕获的一次重构残留（~130 行）。无结构性冗余可合并；后续按 AGENTS.md §5.4 维持"新增前找既有实现"即可。
