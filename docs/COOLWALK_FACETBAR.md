# COOLWALK_FACETBAR.md — FacetBar / 左轨回收与重连分辨率

Coolwalk 左侧 **FacetBar**（`GhFacetBar` VD + launcher/dashboard 图标列）与 **重连分辨率结算** 的架构说明与坑点备忘。  
运行时总链路见 [EXECUTION.md](EXECUTION.md)；仓库地图见 [AGENTS.md](../AGENTS.md)。

---

## 1. 问题背景

Android Auto Coolwalk 在 HU 左侧保留一条 **竖向导航轨**：

| 层 | 典型表现 | AADisplay 目标 |
|----|----------|----------------|
| **Compositor** | 具名 `GhFacetBar` VirtualDisplay，宽约 80–107px | **饿成 1×H**，合成器不再占槽 |
| **Layout** | `content_bounds` = `Rect(rail,0,HU−rail,H)` | 扩成 `Rect(0,0,fullHU,H)` |
| **View** | `gh_coolwalk_*facet*` 图标列 | GONE + 0 宽，兄弟内容 MATCH_PARENT |
| **Profile** | 分屏 VD 按 **720**（800−80）创建 | 回收后按 **800** 全宽 settle |

四条链路不同步时会出现：**左侧黑条**、**右侧 gutter**、**触控落在死 VD**、**软重连 800/720 来回跳**。

---

## 2. 模块地图

```
gearhead :projection                          gearhead :car
├─ CoolwalkFacetChrome          collapse 视图   ├─ zero rail dimens
├─ AaCoolwalkLayoutHook         LayoutInfo      ├─ AaCoolwalkHuTouchHook  steal 触控
├─ AaCoolwalkProjectionHook     content_bounds  └─ AaCoolwalkCompositorHook  VD starve/expand
├─ AaCoolwalkCompositorHook     VD policy
└─ CoolwalkRailCoordinator ◄── RailEvent ──► (IPC) system_server
                                              ├─ CoolwalkRailStore (Settings.Global 镜像)
                                              └─ DisplayProfileSettle → profile lock

io.github.nitsuya.aa.display (AADisplay 进程)
└─ AaDisplayProcessHook → AaDisplayPresentationResize  (仅 create 时加宽 presentation VD)
```

| 类 | 职责 |
|----|------|
| `CoolwalkRailCoordinator` | gearhead 内 **单一状态机**；`onEvent` → 新 `RailSnapshot` + `RailAction` |
| `CoolwalkRailTypes` | `RailPhase`、`RailSnapshot`、`RailEvent`、`RailAction` |
| `CoolwalkRailMath` | 纯几何（轨宽区间、content_bounds 展开、HU 宽度合并）— **可 JVM 单测** |
| `CoolwalkCompositorPolicy` | VD create/resize 改写：FacetBar→1×H、内容 VD 扩满、Dashboard→1×1 |
| `CoolwalkFacetChrome` | inflate / windowAttach / ensure poll：折叠 facet 列、回收 gutter |
| `CoolwalkRailStore` | `Settings.Global` + `serverSnapshot`（跨进程真值镜像） |
| `DisplayProfileSettle` | system_server：**单一 settle 规则**（取代 grow/shrink 双梯） |
| `AaDisplayPresentationResize` | AADisplay 进程：**仅** `createVirtualDisplay` 时加宽 CarActivity presentation |

---

## 3. 状态机（RailPhase）

| Phase | 含义 |
|-------|------|
| `Bootstrapping` | 冷连，尚无 LayoutInfo |
| `RailPresent` | 观测到活轨宽（VD 或 dimen） |
| `Reclaiming` | 正在 collapse / starve / 扩 content_bounds |
| `FullBleed` | `content_bounds` 连续稳定全宽（`FULL_BLEED_STABLE_THRESHOLD=3`） |
| `ReconnectSettling` | 软重连后丢弃上一车的 HU 真值，等新 LayoutInfo |

**软重连：** `RailEvent.ReconnectStarted` 清空 `fullHuWidthPx` / layout，避免上一台车的 1280 污染本次 800。

---

## 4. 四条回收路径（必须都打通）

### 4.1 View — `CoolwalkFacetChrome`

1. `LayoutInflater.inflate` 命中 facet layout id 或 **status_bar + launcher 图标** 内容特征 → `collapseFacetChromeInPlace`。
2. 命中 canonical rail host → `tryInjectIntoFacetColumn` 或 `scheduleEnsureFacetBar`。
3. `WindowManagerGlobal.addView` → `reclaimLeftGutter` + 晚到 chrome 再 ensure。
4. **ensure 窗口：** 2s / 250ms poll；**inject 成功即停**；不再把 deadline 续满（避免占着 ~107px 的 GhostActivity 宿主永远扫不到）。
5. `reclaimAllWindowGutters`：starve/collapse/reconnect 时扫 **进程内全部窗口根**，不只已打 tag 的那棵。

### 4.2 Compositor — `AaCoolwalkCompositorHook` + `CoolwalkCompositorPolicy`

- 具名 FacetBar / GhFacet / VerticalRail / EdgeColumn：**create 时 `W×H → 1×H`**，并记 `touchRailWidthPx`（给触控，不给 profile）。
- 无名瘦长 VD（≤120px 且高≥3W）：同样饿成 1×H。
- 内容 VD 宽度 < layout 全宽且缺口像 **单条轨**：扩到 `fullHuWidthPx`。
- **禁止**改写名为 `AaDisplayActivity` 的 presentation VD（gearhead 内 resize 会黑屏；见坑点 §6.2）。

### 4.3 Projection 配置 — `AaCoolwalkProjectionHook`

- Hook `content_bounds` / `content_insets` / `pillar_width` → 0。
- `computeExpandedContentBounds` 三种形态：
  - **Form A:** `Rect(rail,0,fullW,H)`
  - **Form B:** `Rect(rail,0,contentRight,H)` 且 `contentRight+rail` = 真全宽
  - **Form C:** `Rect(0,0,contentW,H)` 且已知 `fullHu` − `contentW` 为单轨缺口
- 扩满后广播 `ACTION_COOLWALK_FULL_BLEED` → `AaMainFragment.requestDisplay`。

### 4.4 Profile — `DisplayProfileSettle` + `CoreManagerService`

**单一规则**（`DisplayProfileSettle.settle`）：

```
live FacetBar VD 条带宽度 > 1  →  settle 到 fullHU − rail
条带 ≤ 1（已 starve）          →  若 reported 仍是 content slot，暂保持 reported；
                                 否则 settle 到 fullHU
```

- **live rail** 来自 `DisplayManager` 扫具名 FacetBar VD 的 `physicalWidth`（system_server 可见私有 VD）。
- **不要用 `touchRailWidthPx` 做 profile**（那是 `:car` 触控 steal 带宽，用它会把 profile 锁在 HU−rail）。
- 软重连首次 create 后 **450ms `rail-settle` 重试**（FacetBar 可能晚一拍出现或消失）。
- `reportCoolwalkRailSnapshot` 在 full 变大或 `FullBleed` 时也会触发 settle 重试。

---

## 5. 触控与轨宽分离

| 字段 | 用途 |
|------|------|
| `touchRailWidthPx` | `:car` HU touch steal 的 x 带宽度（`railHitWidthPx`） |
| FacetBar VD `physicalWidth` | profile settle 的 **compositor 条带** |
| `effectiveRailWidthPx` | 设计上恒 0（内容/profile 目标无轨） |

`:car` `AaCoolwalkHuTouchHook` 偷 `x < railHitWidth` 的触控 → `touchPrimaryPane`；时间戳必须用 **uptime**。

---

## 6. 关键坑点（改代码前必读）

### 6.1 打 tag 就停 poll → 左侧黑条复发

早期实现：facet 宿主一 `tag = injected` 就停止 ensure / 全窗 reclaim。  
真正占 107px 的 **GhostActivity 窗口** 可能从未 inflate facet layout，只有瘦长宿主。  
**现规则：** ensure / attach / collapse / starve 都扫全部 `WindowManagerGlobal` 根；inject 成功停 poll；晚到 chrome 靠 LayoutInfo / `windowAttach` 再武装。

### 6.2 在 gearhead resize CarActivity presentation → 黑屏

Car SDK 在 **AADisplay 进程** 按 content 槽分配 encoder Surface。  
在 gearhead 把同名 VD 改成 full HU 但 Surface 仍是 720 宽 → HU 全黑。  
**对策：** `CoolwalkCompositorPolicy` 对 `AaDisplayActivity` VD **不改写**；  
`AaDisplayPresentationResize` 仅在 AADisplay 进程 **createVirtualDisplay 入参** 加宽（读 `CoolwalkRailStore`）。

### 6.3 FacetBar 已 1px 但 `content_bounds` 仍 HU−rail

`:car` 若未 zero rail dimen，`GhLifecycleService` 仍发 `Rect(0,0,HU−rail)`，合成器留空槽。  
**对策：** `:car` 同样 zero dimens + VD starve；`:projection` 扩 `content_bounds`。

### 6.4 `touchRailWidthPx` 误用于 profile

会把 profile 锁在 720 即使 compositor 条带已消失 → 右侧 gutter。  
**对策：** `resolveRailWidthPx()` 只看 live VD；`FullBleed` phase 直接 0。

### 6.5 reported 已是 content slot 时盲目扩到 full HU

FacetBar 已 starve，但 CarActivity presentation 可能仍是 HU−rail。  
此时把 **分屏 VD** settle 到 full HU → 内容 letterbox 在较小 presentation 里，或 resize 该 VD 黑屏。  
**对策：** `DisplayProfileSettle.isContentSlotVsFull` — gap 在轨宽区间内则 **保持 reported**。

### 6.6 跨车 HU 真值污染

上一连接 1280×720 写入 Settings / IPC，换 800×480 车机后仍用 1280 扩 VD。  
**对策：** `ReconnectStarted` 清空；`pickConnectionFullHuWidth` / `isSameHuGeometry` 拒绝不同几何；`absorbExternalFullHu` 不同 HU 不合并。

### 6.7 `mergeFullHuWidth` 误把 720+80+80 当 full

双轨缺口（如 720→880）不是真 HU 变宽。  
**对策：** `isPlausibleRailGap` 限制单轨；`mergeFullHuWidth` 在 `extra ≤ 3×rail` 时取较小值。

### 6.8 瘦长主屏误当 FacetBar（r6 回归类）

竖屏手机 1080×2340 被当 rail VD，`CarDisplayId` 反射扫到 `describeContents()==0` → 整屏触控被 steal。  
**对策：** 禁止 `DEFAULT_DISPLAY`；优先具名 FacetBar；无 LayoutInfo 时仅绝对窄条 ≤120px；`CarDisplayId` 白名单 accessor。

### 6.9 ensure 窗口过长 → 性能 / 逻辑干扰

8s poll 且每次 collapse 续 deadline 会长时间扫窗。  
**现参：** `FACET_ENSURE_WINDOW_MS = 2000`，`FACET_ENSURE_POLL_MS = 250`。

### 6.10 DexKit `content_bounds` / LayoutInfo 未命中

AA 17.x 布局 id 变化 → facet 按钮消失、回收不触发。  
**对策：** 多 layout id + 内容特征检测；DexKit 缓存 key 见 `AaUiHook`；失败看 `AAD_*` 日志。

---

## 7. 日志与验证

| 日志标签 | 看什么 |
|----------|--------|
| `AAD_CoolwalkRail` | `phase=`、`full=`、`touchRail=`、`event=` |
| `AADisplay_CoreManagerService` | `displayProfile locked/relocked(settle|rail-settle)`、`CoolwalkRail snapshot` |
| `AADisplay_AaUiHook` | `starve FacetBar`、`content_bounds expanded`、`collapse facet rail` |
| `AAD_AaDisplayVD` | `presentation VD create W→target`（AADisplay 进程） |

**软重连回归：**

1. `GhFacetBar` 饿死后 profile 稳定全宽；无右侧 gutter / 左侧黑条。
2. 左轨触控仍可注入（starve 后 hit 宽用观测轨宽，非 1px）。
3. 换分辨率车机（如 800 vs 1280）冷连各 settle 一次，不串车。

单测：`CoolwalkRailMathTest`、`CoolwalkCompositorPolicyTest`（`./gradlew :aa-display:testDebugUnitTest`）。

---

## 8. 改动约束

- **勿改** `CoolwalkRailStore` Settings key、`ICoreManager.reportCoolwalkRailSnapshot` 签名。
- **勿在 gearhead** resize `AaDisplayActivity` presentation VD。
- profile 逻辑集中在 `DisplayProfileSettle` + `CoreManagerService.resolveDisplayProfile`；不要在客户端再叠 grow/shrink 梯。
- 新轨宽启发式优先放进 `CoolwalkRailMath` 并补单测。
