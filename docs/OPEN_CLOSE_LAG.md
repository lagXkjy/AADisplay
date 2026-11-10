# OPEN_CLOSE_LAG.md — 应用 Open/Close 链路与卡顿根因

给后续 AI / 开发者用的**打开/关闭应用执行链路与性能分析**。改 Recent、launch、close、栈 promote 前先读本文。  
仓库地图与改动硬规则见 [AGENTS.md](../AGENTS.md)；全链路执行顺序见 [EXECUTION.md](EXECUTION.md)。

路径均相对仓库根。Kotlin 源码默认在 `aa-display/src/main/java/io/github/nitsuya/aa/display/`。

> **架构前提（现行）**：Car SDK 壳（`AaMainFragment` / `SplitDividerView` / 原 Recent 面板）已删。  
> UI 是 Host GL 覆盖层：`CarPlayAppPickerOverlay`（空窗选 App）与 `CarPlayRecentsOverlay`（三列最近任务），**直接经 `CoreApi` Binder** 触发；无壳广播、无滑动手势。

---

## 0. 架构总览

所有 VD 上的真实任务操作都在 **system_server** 的 `SplitDisplayController` 中执行：

```
CarPlayAppPickerOverlay / CarPlayRecentsOverlay / CarPlayInputBridge
    → CoreApi → CoreManager (PMS bridge "AADD")
    → CoreManagerService (system_server)
    → SplitDisplayController.mHandler → ATMS / ActivityManager
```

**关键设计**：

- 用户操作（launch / move-front / remove / swap）经 `*Async` → `postUserAction`（`postAtFrontOfQueue` 插队到 handler 队首），避免排在 ratio settle / reclaim 之后。
- `startActivityOnPaneForUser` 是**同步** Binder 调用：picker / Recents 点行走它，server 端在 handler 上跑完再回结果，UI 可据 bool 乐观回退。
- restore / ensure / persist / reclaim 由 `SplitLaunchRestore`（`SettlementPhase`：`IDLE → RESTORING → VERIFYING`）串行，禁止并行 `scheduleRestoreLastSplit`。

---

## 1. 打开（Open）链路

### 1.1 入口

| 场景 | 入口 | 线程策略 |
|------|------|----------|
| 空窗点选（picker） | `CarPlayAppPickerOverlay` → `CoreApi.startActivityOnPaneForUser` | `launchExecutor` 后台线程发 IPC（同步返回 bool） |
| Recents 点行 | `CarPlayRecentsOverlay.launchItem` → `moveTaskToFront` / `startActivityOnPaneForUser` | 主线程关面板后 IPC |
| 连接恢复 | `SplitLaunchRestore.restoreLastSplitNow` | system_server 主 handler **同步**逐包 launch |
| 方控 / HID 换窗 | `moveTaskToFront` | `postUserAction` |

（Recents 无「手机列拖到车机列」手势——`moveTaskIdToPane` 已删；搬任务 = 左/中「添加应用」→ picker 选中 → `startActivityOnPaneForUser`。）

### 1.2 调用链（用户点选）

```
CarPlayAppPickerOverlay.onClick
  → launchExecutor: CoreApi.startActivityOnPaneForUser(pkg, 0, pane)
  → CoreManagerService → SplitDisplayController.startActivityOnPaneForUser（handler 同步）
  → startActivityOnPaneOnHandler
       ├─ 栈上已有 live task   → bringTaskToFront（失败 → 清僵尸 + 冷启）
       ├─ 其它 display 有 live task → vacateOtherPanes + moveRootTaskToDisplay + bring + 校验落位（失败 → 清 + 冷启）
       ├─ 无 live task         → removePackageTasksEverywhere（清 affinity 僵尸）+ launchOnDisplay（COLD）
       └─ 栈满 3（PaneAppStack.MAX_PER_PANE）→ 先 evict 栈底
  → 成功后 stacks.pushToTop + finishLaunchOnPane
  → schedulePersistSnapshot（debounce）
  → promote / enforceStackFrontAudio（AvMediaArbiter 单发声）
```

**决策树要点**（`SplitDisplayController.startActivityOnPaneOnHandler`）：

1. **栈上已有** → `bringTaskToFront` 一次 ATMS walk（`getAllRootTaskInfosOnDisplay` + `findPackageTaskInRoots`）；bring 失败（zombie / LAUNCHER≠topActivity，如高德 `UsbFillActivity`）→ 清任务冷启。**禁止把失败切换写成栈顶。**
2. **手机 / 另一窗有 live 任务** → `moveRootTaskToDisplay`，且必须再校验 live root 真在目标 VD（bring 可能在手机侧成功而 move 被忽略，三星常见）；失败 → 清全部 + 冷启。
3. **无 live 任务** → 先扫 affinity 僵尸再 `launchOnDisplay`（冷启动 = 链路绝对耗时大头，框架层）。
4. 栈满 → 挤底 `evictPackageFromPane`。

---

## 2. 关闭（Close）链路

### 2.1 入口

| 触发 | 路径 |
|------|------|
| Recents × | `CarPlayRecentsOverlay.closeItem` → 本列乐观删行 → `CoreApi.removeTask(taskId)` |
| 栈挤底（非用户 close） | `evictPackageFromPane`（只 ATMS remove，不 forceStop） |

分隔条 / peel / 方控手势**不关闭应用**，只调比例 / 对调 / 开 Recents。

### 2.2 调用链

```
CarPlayRecentsOverlay.closeItem
  → CoreApi.removeTask (fire-and-forget)
  → removeTaskAsync → postUserAction → removeTaskOnHandler (mHandler 同步)
       ├─ findPackageForTask / isTaskOnAaDisplay
       ├─ VD 上任务：forgetOwnership → onExplicitPackageClosed（本会话不再 restore/backfill）
       │    → stacks.removeAll + releaseOwnership + VdDensityPin.clear + untrack
       │    → 空窗 removeChromeTasksOnDisplay；仍有栈 → promoteStackFronts（settleAv=false）
       │    → notifySplitStateChanged + schedulePersistSnapshot
       └─ ATMS.removeTask 放 "AADisplay-close" 线程（QQ 音乐车机等可阻塞数秒，故意不在 handler）
```

---

## 3. 卡顿根因（按影响排序）

### 3.1 ATMS 工作在 system_server 主 handler 上串行（HIGH）

`postUserAction` 内的 handler 方法仍**同步**执行：

- 冷启动：`startActivityAsUser`（`AaLaunchHelper`，COLD）
- 置顶：`bringTaskToFront`（多次 ATMS + reorder fallback）
- 关闭：`findPackageForTask` 扫 3 个 display；`promoteStackFronts` 每窗 `getAllRootTaskInfosOnDisplay`

**表现**：VD 画面切换滞后于 Host Recents 乐观删行；多 App 栈切换时更明显。

### 3.2 TaskStackListener 扇出（HIGH）

`SplitTaskStackListener.onTaskStackChanged` → `scheduleAtmsSettle`（`RECLAIM_DEBOUNCE_MS` debounce）→ `reclaimOwnedPackages` + `refreshPanePackagesFromAtms` + persist + Presentation 驱逐。  
一次 open/close 在 settle 期可能**连续多次**触发 `onTaskStackChanged` → handler 队列积压。reclaim 前必须抬高 `mSuppressReclaimUntil`（restore 8s / 一般 2s，见 EXECUTION §8.3）。

### 3.3 Recents 快照 IPC（HIGH，打开面板时）

`CarPlayRecentsOverlay.refresh` → `CoreApi.getRecentTask()`：

- `RecentTaskProvider.buildSnapshot` + `SplitInputRecents.taskInfoFromRoot`：最多 3× `getAllRootTaskInfosOnDisplay`
- 每 task：`getTaskDescription` + PM icon + 64px downsample → **Bitmap 跨进程 parcel**（手机列取前 8）

### 3.4 AvMedia 栈仲裁（MEDIUM–HIGH）

`promoteStackFronts` → `enforceStackFrontAudio` → `AvMediaArbiter` 单发声（`buriedPlayback.claimSpeakerForFront` + debounce 全局 enforce）。

### 3.5 目标 App 冷启动（框架层）

`startActivityAsUser` 返回后，进程启动 + 首帧在 VD 上，是打开链路最大绝对时间；不可优化，只能保证「先切画面感知」。

### 3.6 已修复 vs 仍残留

| 问题 | 状态 |
|------|------|
| 选择器 / Recents 点击阻塞主线程 | **已修复** — 后台 executor + 乐观 UI |
| `removeTask` 在 handler 上阻塞数秒 | **已修复** — `AADisplay-close` 线程 |
| 并行 restore/ensure 双发 launch | **已收敛** — `SettlementPhase` 状态机 |
| Open 双路径不一致 | **已合并** — 单一 `startActivityOnPaneOnHandler` |
| VD 画面切换 vs Recents 行乐观更新不同步 | **仍存** — promote/ATMS 在 system_server handler |
| Recents 打开时全量快照 + Bitmap parcel | **仍存** — 打开瞬间的 IPC 峰值 |

---

## 4. 场景化卡顿画像

| 场景 | 快 / 慢点 |
|------|-----------|
| 空窗 picker 点选 | 主线程 `hide` 乐观 — 快；后台 ForUser 冷启 — VD 0.3–2s 才切 |
| Recents 点行 | 关面板 + move/launch 并行；同栈切换快，跨 display 搬迁/冷启慢 |
| Recents × 关闭 | 行立即消失 — 快；`promoteStackFronts` 200–800ms；后台 removeTask 数秒可能再触发 settle |
| Recents 打开 | 全量快照 + 3 display 扫栈 + icon downsample — 卡顿峰值 |

---

## 5. 关键文件索引

| 职责 | 文件 |
|------|------|
| Open/Close 编排 | `ui/aa/split/SplitDisplayController.kt` |
| 冷启 / restore / settle / persist | `ui/aa/split/SplitLaunchRestore.kt` |
| launch 反射 | `ui/aa/split/AaLaunchHelper.kt` |
| ATMS 置顶 / 搬迁 / reclaim | `ui/aa/split/SplitOwnership.kt` |
| 栈（每窗 ≤3） | `ui/aa/split/PaneAppStack.kt` |
| 栈变更监听 | `ui/aa/split/SplitTaskStackListener.kt` |
| Recents UI（Host GL） | `carplay/CarPlayRecentsOverlay.kt` |
| 快照构建 | `ui/aa/split/RecentTaskProvider.kt`、`SplitInputRecents.kt` |
| Picker UI（Host GL） | `carplay/CarPlayAppPickerOverlay.kt` |
| IPC 入口 | `xposed/CoreManagerService.kt` |
| 单发声仲裁 | `util/AvMediaArbiter.kt`、`ui/aa/split/SplitBuriedPlayback.kt` |

---

## 6. 后续优化方向（未落地）

1. **Recents**：dirty 时 patch taskId/icon 而非全量 snapshot 构建。
2. **Server**：`bringTaskToFront` 进一步复用单次 display snapshot。
3. **Close**：评估 promote 与 forceStop 并行化（注意 AvMedia 顺序）。
4. **重连**：散落的 settle Runnable 收敛进 `SettlementPhase` 状态机。
