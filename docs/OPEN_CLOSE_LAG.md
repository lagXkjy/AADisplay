# OPEN_CLOSE_LAG.md — 应用 Open/Close 链路与卡顿根因

给后续 AI / 开发者用的**打开/关闭应用执行链路与性能分析**。改 Recent、launch、close、栈 promote 前先读本文。  
仓库地图与改动硬规则见 [AGENTS.md](../AGENTS.md)；全链路执行顺序见 [EXECUTION.md](EXECUTION.md)。

路径均相对仓库根。Kotlin 源码默认在 `aa-display/src/main/java/io/github/nitsuya/aa/display/`。

---

## 0. 架构总览

所有 VD 上的真实任务操作都在 **system_server** 的 `SplitDisplayController` 中执行。AA UI 进程（gearhead `:projection/:car`）只通过 **Binder IPC** 触发：

```
SplitAppPickerController / RecentTasksCoordinator / AaMainFragment
    → CoreApi → CoreManager (PMS bridge "AADD")
    → CoreManagerService (system_server)
    → SplitDisplayController.mHandler → ATMS / ActivityManager
    → ACTION_SPLIT_STATE_CHANGED / ACTION_RECENT_TASK_DIRTY 广播回 AA UI
```

**关键设计**：用户操作走 `*Async` + `postUserAction`（插队到 handler 队首）；restore/ensure 走同步 `runOnHandlerBlocking` 全路径。恢复链路由 `SplitLaunchRestore` 的 `SettlementPhase`（`IDLE → RESTORING → VERIFYING`）串行，禁止并行 `scheduleRestoreLastSplit`。

---

## 1. 打开（Open）链路

### 1.1 入口

| 场景 | 入口 | 线程策略 |
|------|------|----------|
| 应用选择器点选 | `SplitAppPickerController.onClick` | 主线程 `hide()` + 乐观占位 → `launchExecutor` 发 IPC |
| Recent 点行 | `RecentTasksCoordinator.openTask` | 主线程 `post { onExit() }` → `mutationExecutor` 发 IPC |
| 连接恢复 | `SplitLaunchRestore.restoreLastSplitNow` | system_server 主 handler **同步**逐包 launch |
| 手机列 → VD 滑动 | `CoreApi.moveTaskIdToPane` | 后台 mutation + 280ms 后 reload |

### 1.2 调用链（用户点选 / Recent）

```
UI: hide_picker / post_onExit
  → CoreApi.startActivityOnPane (gearhead 后台线程)
  → CoreManagerService.startActivityOnPane → startActivityOnPaneAsync
  → postUserAction (cancel debounced persist/reclaim/dirty)
  → startActivityOnPaneOnHandler
       ├─ 栈上已有     → bringTaskToFront
       ├─ 手机 live task → moveRootTaskToDisplay + bringTaskToFront
       └─ 否则         → launchOnDisplay (AaLaunchHelper COLD)
  → schedulePersistSnapshot (debounce 2500ms)
  → notifySplitStateChanged → AaMainFragment 占位/布局
  → mHandler.post { enforceStackFrontAudio }
```

**决策树**（`SplitDisplayController.startActivityOnPaneOnHandler`）：

1. 栈上已有 → `bringTaskToFront`（最快）
2. 手机上有 live task → `moveRootTaskToDisplay` + verify
3. 否则 → `launchOnDisplay`（冷启动，`AaLaunchHelper.Mode.COLD`）
4. 栈满 3 个 → 挤底 `evictPackageFromPane`

### 1.3 已做优化（见 CHANGELOG Unreleased）

- 选择器：先 `hide()` + 乐观占位，IPC 放后台线程
- Recent 点选：先关面板再异步 launch
- 用户路径用 `startActivityOnPaneAsync` + `postAtFrontOfQueue`
- Open 双路径合并为单一 `startActivityOnPaneOnHandler`

---

## 2. 关闭（Close）链路

### 2.1 入口

| 触发 | 路径 |
|------|------|
| Recent × 按钮 | `RecentTasksCoordinator.closeTask` |
| 主 VD 左滑 / 手机列右滑 | `onPrimarySwipeLeft` / `onPhoneSwipeRight` |
| 栈挤底（非用户 close） | `evictPackageFromPane`（只 ATMS remove，不 forceStop） |

**Peel / 分隔条手势不关闭应用**，只调比例/对调/开 Recent。

### 2.2 调用链

```
RecentTasksCoordinator.closeTask / swipe
  → closeTaskOptimistic (乐观 UI，立即 notifyItemRemoved + skipDirtyReloads)
  → ++reloadGeneration (取消 pending debounced reload)
  → CoreApi.removeTask (fire-and-forget, mutationExecutor)
  → removeTaskAsync → postUserAction → removeTaskOnHandler (sync on mHandler)
       ├─ findPackageForTask (3 display 扫描)
       ├─ stacks.removeFromAll + notifySplitStateChanged
       └─ promoteStackFronts (sync on handler)
  → Thread "AADisplay-close":
       ├─ forceStopPackageAsUser (VD 先 forceStop，pane 立刻清)
       └─ ATMS.removeTask (QQ 音乐等可阻塞数秒，故意不在 handler 上)
```

### 2.3 关闭副作用

- `onExplicitPackageClosed` → 本会话不再 restore/backfill
- `schedulePersistSnapshot`（debounce；单窗空时 skip 写盘）
- `SplitTaskStackListener` → `scheduleAtmsSettle` + `schedulePersistSnapshot` + Presentation evict

---

## 3. 卡顿根因（按影响排序）

### 3.1 ATMS 工作在 system_server 主 handler 上串行（HIGH）

即使用 `*Async`，`postUserAction` 内的 handler 方法仍是**同步**执行：

- 冷启动：`startActivityAsUser`（经 `AaLaunchHelper`）
- 置顶：`bringTaskToFront`（多次 ATMS + reorder fallback）
- 关闭开始：`findPackageForTask` 扫描 3 个 display
- 关闭后：`promoteStackFronts` → 每窗 `getAllRootTaskInfosOnDisplay`

**表现**：VD 画面切换滞后于 Recent 行乐观移除；多 App 栈切换时更明显。

### 3.2 TaskStackListener 栈变更扇出（HIGH，部分已合并）

`SplitTaskStackListener.onTaskStackChanged`：

| 调度 | 延迟 | 效果 |
|------|------|------|
| `scheduleAtmsSettle` | 200ms | → `reclaimOwnedPackages` + `refreshPanePackagesFromAtms` + split broadcast + `ACTION_RECENT_TASK_DIRTY` |
| `schedulePersistSnapshot` | 0–2500ms | LastSplitStore 写盘 |
| `SplitPresentationGuard.scheduleEvict` | debounced | foreign Presentation 驱逐 |

notify + Recent dirty 已合并进 `scheduleAtmsSettle`；persist 与 evict 仍独立（正确性需要）。

一次 open/close 在 ATMS settle 期间可能**连续触发多次** `onTaskStackChanged`，形成 handler 队列积压。

### 3.3 Recents 全量快照 IPC（HIGH，Recent 打开时）

`RecentTasksCoordinator.reloadNow` → `CoreApi.getRecentTask()`：

- `RecentTaskProvider.buildSnapshot` + `SplitInputRecents.taskInfoFromRoot`
- 最多 **3×** `getAllRootTaskInfosOnDisplay`（手机 + 双 VD）
- 每 task：`getTaskDescription` + PM icon + 64px downsample
- 结果带 Bitmap **跨进程 parcel**
- UI 侧 `setItems` → **DiffUtil** 增量刷新（已落地；打开时仍全量 IPC 构建快照）

**debounce 叠加**：dirty 200ms + reload 280ms ≈ **480ms**；打开 Recent 时 `onResume` 还会 `reloadImmediate()`。

### 3.4 AvMedia 栈仲裁（MEDIUM–HIGH，音乐/视频栈）

`promoteStackFronts` → `enforceStackFrontAudio` → `getActiveSessions` + pause 埋栈。

### 3.5 AA 主壳布局更新（MEDIUM）

`AaMainFragment` 收 `ACTION_SPLIT_STATE_CHANGED` → `applyOccupancyFromPackages` → 分屏 weight 重算。occupancy 未变时跳过 overlay 刷新；ratio/swap settle 期间 `layoutSettleUntil` 挡 rebind。

### 3.6 目标 App 冷启动（框架层）

`startActivityAsUser` 返回后，进程启动 + 首帧在 VD 上，占打开链路最大绝对时间。

### 3.7 已修复 vs 仍残留

| 问题 | 状态 |
|------|------|
| 主线程 Binder 阻塞（选择器/Recent close） | **已修复** — 后台 executor |
| `removeTask` 在 handler 上阻塞 4–5s | **已修复** — 后台 `AADisplay-close` 线程 |
| Recent 200ms 缩放动画 | **已移除** |
| Recent 全列 `notifyDataSetChanged` | **已修复** — DiffUtil |
| ATMS notify + dirty 双 debounce | **已合并** — `scheduleAtmsSettle` |
| Open 双路径不一致 | **已合并** — `startActivityOnPaneOnHandler` |
| 并行 restore/ensure 双发 launch | **已收敛** — `SettlementPhase` 状态机 |
| `panePackageForDisplay` 读路径不一致 | **已修复** — 走 `getPanePackage` |
| VD 画面切换 vs Recent 行移除不同步 | **仍存** — promote/ATMS 在 handler |
| Recent 打开时全量 IPC 快照构建 | **仍存** — 快照 + Bitmap parcel |

---

## 4. 场景化卡顿画像

### 场景 A：应用选择器点选（Recent 未开）

1. 主线程：`hide()` + 乐观占位 — **快**
2. 后台：Binder → handler 冷启动/置顶 — **中–慢**
3. 广播：壳布局 — **轻–中**
4. TaskStackListener 扇出 — 占 system_server handler

**典型感受**：选择器消失快，VD 画面 0.3–2s 才切。

### 场景 B：Recent 打开/关闭 App（最差）

1. 点 close / 滑动：行立即消失 — **快**
2. VD 切栈顶：`promoteStackFronts` — **慢 200–800ms**
3. dirty + reload：DiffUtil 增量但仍全量 IPC 快照 — **卡顿峰值**
4. 后台 `removeTask` 数秒 — 可能再触发 dirty reload

**典型感受**：Recent 列表「闪一下又卡」、VD 画面比列表慢半拍。

### 场景 C：Recent 点行打开

1. `post { onExit() }` 关 Recent — 与 launch 并行
2. 同场景 A 的 server 路径
3. Recent 关闭 + fragment remove 竞争

---

## 5. 关键文件索引

| 职责 | 文件 |
|------|------|
| Open 编排 | `ui/aa/split/SplitDisplayController.kt` |
| 冷启动 / restore / settle | `ui/aa/split/SplitLaunchRestore.kt` |
| 统一 launch 反射 | `ui/aa/split/AaLaunchHelper.kt` |
| ATMS 置顶/搬迁 | `ui/aa/split/SplitOwnership.kt` |
| Close 核心 | `SplitDisplayController.removeTaskOnHandler` |
| 栈 pop | `ui/aa/split/PaneAppStack.kt` |
| 栈变更监听 | `ui/aa/split/SplitTaskStackListener.kt` |
| Recent UI | `ui/aa/recent/RecentTasksCoordinator.kt` |
| 快照构建 | `ui/aa/split/RecentTaskProvider.kt`, `SplitInputRecents.kt` |
| IPC 入口 | `xposed/CoreManagerService.kt` |
| 选择器 | `ui/aa/split/SplitAppPickerController.kt` |
| 壳 UI 反馈 | `ui/aa/fragment/AaMainFragment.kt` |

---

## 6. 后续优化方向（未落地）

按优先级：

1. **Recents**：dirty 时 patch taskId/icon 而非全量 snapshot 构建
2. **Server**：`bringTaskToFront` 进一步复用单次 display snapshot
3. **Close**：评估 promote 与 forceStop 并行化（注意 AvMedia 顺序）
4. **重连**：将 scattered Runnable 收敛为单一 `SettlementPhase` 扩展（ensure 阶段）
