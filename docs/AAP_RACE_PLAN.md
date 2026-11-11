# AAP 通道竞态扫描 → 修复记录（已完成）

扫描范围：`aap/`（Session / FGS / USB / wireless / 音频 / NP / 输入 / 证书）、`egress/`（编排）、`AapDisplayBridge`、vendor `AapSession` 回调线程语义。场景：待机（CPU/USB suspend）、唤醒（车机/手机/蓝牙）、通道切换（AAP⇄CarPlay）、拔插重连（USB/无线）。

## 发现与修复（全部已实施）

### 高优先（真竞态 / 泄漏 / 僵尸会话）

| ID | 位置 | 问题 | 修复 |
|----|------|------|------|
| **A1** | `AapSourceSession.callbacks.onClosed` | main 延迟清理块**无条件**改实例字段（`aap`/`transport`/`display`/`activeVideo`）并停共享对象（`audioEgress`/`nowPlaying`/`videoBitrateCtrl`）。新会话（USB 软重挂 / 重插 / 无线重拨）先于清理绑定时，清理会**杀掉新会话**。 | 清理块身份门：`aap !== session` → 整块跳过（日志 `onClosed cleanup skipped — superseded`）。`reportLive(false)` 移到 onClosed 顶部无条件执行；kill 标志（`closedByStall`/`closedByTransportGap`）在 status 分支**读后**重置，防泄漏到下一会话的状态判定。 |
| **A2** | `AapSourceSession.aap` / `.transport` | 跨线程无锁读写、无可见性保证。 | `@Volatile`。 |
| **A3** | `connectDevice` / `startAap` / `startOnTransport` | check-then-bind 窗口 + 无条件夺权：`startAap` 对**异 transport** 一律 `tearDownSession("rebind")` 换绑，活会话被后到者夺走；同 transport 重复 attach 重开 TLS。 | `startAap` 三闸：同 transport 幂等 true；异 transport 且会话未死 → 关新 transport、保活会话、false；`!running`/`detached` → 关新 transport、false。`connectDevice` 检查+绑定同临界区；`startOnTransport` 证书提取移出锁、gate+running+bind 单锁块。 |
| **A4** | `connectDevice` 尾部 | 无线建连中（可达 60s+）用户切通道 → `stop()` 后仍 `startAap` → **僵尸 AAP 会话**抢走刚武装的 CarPlay 共享 VD。 | 检查+绑定进同一 `synchronized(this)`（含 `detached`）；所有拒绝出口 close 未用 transport。 |
| **A5** | `AapUsbProbe.probeOnce` | 打开了 AOA fd 但无人持有（FGS 未 arm / 通道切走）→ fd + drain 线程泄漏。 | 收尾：`!hasActiveTransport()` 时 close。 |
| **A6** | `armWireless` | BT ACL 抖动产生**并行**无线建连：双 RFCOMM/Wi‑Fi join 抢同一车机 AP（单 radio 互踢），两者皆死。 | `dialDevice` 统一入口 + `wirelessConnectGate` 串行化（starter / 初始扫描 / 重拨全部走它）。 |
| **A7** | `onClosed` 无线分支 | 会话死但 BT 仍连（AP 掉、HU 重启、车睡眠边缘）→ 无自动重拨，只能用户手动。 | `scheduleWirelessRedial`：3s 后重拨，每 episode 上限 3 次（onRunning/stop/start 重置计数）；`btStillConnected` 反射探活。 |
| **A8** | `armWireless` | 权限缺失时 `autoStarter` 残留 → 监听永久短路，授权后不恢复。 | 注册失败（权限）置 `autoStarter = null`，下次 `start()` 重新 arm。 |
| **A9** | `videoListener.onVideoStart` | reader 线程懒建 `AapDisplayBridge`（`display` 字段）与 main 清理竞争 → 孤儿 GL/编码器/VD 管线。 | 懒建 + `startFor` 移入 `main.post`（与清理同 looper），闭包内复检 `running`/`detached`/`activeVideo`。 |
| **A10** | FGS `usbDetachReceiver` | FGS 收 ATTACHED 广播 OEM 相关；被吞且 Activity 未拉起时探针窗口不再 re-arm → 重插无人响应。 | session 侧 USB receiver 补动作 → FGS companion `onUsbAccessoryAttached()`（serviceRef）→ 满足 channel/armed/非 live/无 transport 时重开 20s 探针窗；DETACHED 清门。 |
| **A11** | FGS `attachTransport` | 丢弃 `pendingTransport` 未 close → fd + drain 线程泄漏。 | 丢弃时 close（同对象除外——即将绑定的不关）。 |
| **A12** | `AapDisplayBridge.displayId` / `vdCreateRetries` / `compositor` / `video` | Binder 回调线程写、main 读，非 volatile。 | `@Volatile`；`stop()` 在主 looper 上同步执行 teardown（防止排队 stop 越过新会话的 start）。 |
| **A13** | `onServiceDiscovery` / `onVideoStart` | reader 线程同步 Binder（`syncTouchBridgeLayout` → `getSplitRatio` 等）→ USB 下即 NONCE-GAP 风险源。 | 改投 `inputHandler`。 |
| **A15** | FGS `tearDown` vs 在途 `attachTransport` | FGS 被拆（切通道）后，stale 实例引用上的在途绑定复活**孤儿僵尸会话**（无 FGS、仍占共享 VD）。 | `AapSourceSession.detached`：FGS teardown 先置位再 stop；`startAap`/`startOnTransport`/`connectDevice`/`dialDevice` 全部拒绝；`start()` 清除（reconnect 复用同实例）。`stop()` 的 teardown 进同一把锁。 |
| **A16** | `startAap` 内 `PhoneCertStore.pick` | `transport = t` 在前，pick 抛异常（AA 更新中证书文件半写）→ 泄漏已绑定无会话的 transport。 | pick 移到 `transport = t` 之前。 |

### 审计中修复的自引缺陷

- `closedByStall` 重置顺序（首次实现误提前到 status 分支之前，破坏 `else if (closedByStall && wasUsb)`）→ 已修正为读后重置。
- `aap-scan-connected` 线程绕过 dial gate → 并入 `dialDevice`。
- `AapDisplayBridge.displayId` 漏 volatile → 已补。
- `attachTransport` 恒返回 true 掩盖拒绝 → 传播 `startOnTransport` 布尔结果（文档同步）。

## 真机验证（R5CT929A5VA，三星折叠屏，Android 13，debug 包）

无车机接入，覆盖可达路径：

- ✅ AAP arm：`ensureArmedCurrent → channel=3` → FGS → session → armWireless + 20s 探针窗（无配件，正常空转）。
- ✅ **通道切换 AAP→CarPlay**：`switchTo(1)`（gen 去重生效）→ `AAP stop (await)` → `tearDown stop-intent` → `tearDownSession stop` → FGS destroy；**全程无 `startAap` 复活**（detached/running 闸生效）；`reportCarPlaySessionLive false` 成对；CarPlay 侧正常武装并拨打已配对 Audi MMI（车端 BT 未响应，预期）。
- ⏳ 需车机：onClosed 身份门 / 无线重拨 / USB 软重挂 / 重插 gate（代码路径已审计，建议下次连车时留意日志：`onClosed cleanup skipped`、`wireless redial n/3`、`startAap reject`、`USB attached (session rx)`）。

## 回归注意事项

- 无 AIDL / 持久化契约 / LSPosed scope 变更；无 system_server 侧改动（无需重启设备）。
- 行为变化：无线死链后**新增**至多 3 次自动重拨（3s 间隔）；USB 探针收尾会 close 无人持有的 transport；`startOnTransport` 对异 transport 不再夺权（改为拒绝，依赖既有探针窗自愈）。
