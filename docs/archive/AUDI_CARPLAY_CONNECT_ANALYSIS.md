# Audi MMI 无线 CarPlay 连不上 — 监测记录与计划

> 日期：2026-10-16  
> 设备：Galaxy Z Flip4（SM-F721B，Android 13）+ Audi_MMI_8280  
> 对照：DiPlay（Receiver）可通；社区「CarPlay 投屏」/CarWith（Source）**别的车机好用，Audi 不太好用**  
> 约束：本轮**只监测 / 记录 / 分析 / 计划，不动代码**  
> 原始抓取：`docs/capture/audi_carplay_20261016_101309/`
>
> **已归档**：一次性监测记录（2026-10-16）；结论已落入 `CarPlayHuProfile` MIB3 路径 / `prepareCarPlayHeadUnit` / `armBluetoothDiscoverable` 等现行实现，仅作历史参考。

---

## 1. 用户问题：是不是要伪装 MAC？

**结论：不是优先项；单独伪装 MAC 不会让车机出现 CarPlay 客户端。**

| 层级 | 车机是否看 | 当前状态 | 是否“改 MAC 就能出菜单” |
|------|------------|----------|-------------------------|
| 蓝牙名称 / CoD | 配对列表展示 | 名称仍是 `Galaxy Z Flip4` | 否（最多像“换了个名字的安卓”） |
| 蓝牙 MAC OUI | 部分 OEM 软过滤 | `80:19:70…`（Samsung） | **存疑增强项**，非首因 |
| iAP2 RFCOMM（…cafe / …caff） | **决定是否走 CarPlay 分支** | Audi 已 BT Connected，但未见对 Audi 的新 iAP2 握手成功 | **否 — 缺的是会话，不是 MAC** |
| Identification / Auth / `0x4300` | 车机 UI “Apple CarPlay” 入口 | Identification 字段为 `AADisplay`/`CarPlaySource`（非 iPhone） | 否 |
| Wi‑Fi handoff + RTSP | 出画 | 当前 `wlan0` DOWN；早先连过 SSID `Carplay` 后 `IP_REACHABILITY_LOST` | 否（更后一层） |

车机菜单出现 “CarPlay / iPhone” 的充要条件，是 **BT 上跑通 iAP2 → `CarPlayAvailability`/`StartSession`**。  
DiPlay 通，说明协议栈对“宽松 Receiver”可用；Audi 仍停在普通手机蓝牙（HFP/A2DP/PBAP），说明 **卡在 iAP2 建立或被车机在 Ident/Auth 层拒绝**，不是“没伪装 Apple MAC”。

---

## 2. 现场快照（adb）

### 2.1 手机侧

| 项 | 值 |
|----|-----|
| BT 名称 | `Galaxy Z Flip4` |
| BT MAC | `80:19:70:AC:42:31`（Samsung OUI） |
| `aadisplay_carplay_channel` | `1`（CarPlay 通道开启） |
| `CarPlayProjectionService` | 前台运行 |
| Host / 编码 | `CarPlayHostActivity` + `CarPlayGlCompositor ~61fps` |
| Wi‑Fi | 监测时 **DISCONNECTED**（`wlan0` DOWN） |
| 早前 Wi‑Fi | ~09:34 连上 SSID **`Carplay`**，IP `10.38.199.25/24`，GW `10.38.199.24`；~10:07 `CMD_IP_REACHABILITY_LOST` |

### 2.2 车机侧（从手机看）

| 项 | 值 |
|----|-----|
| 名称 | `Audi_MMI_8280` |
| 地址 | `60:E6:F0:6E:CB:48` |
| 配对 | Bonded；监测中曾重新配对（bond 10→11→12） |
| ACL | **Connected** |
| 远程 UUID | Handsfree / A2DP Sink / AVRCP / MAP-MNS + **`00000000-deca-fade-deca-deafdecacaff`（iAP2 Accessory）** |
| 策略 | A2DP/HFP/PBAP=100（普通电话配置）；未见 CarPlay UI |

说明：Audi **具备** iAP2 Accessory UUID（…caff），与本模块 `dialBonded` 目标一致；车机理论上支持无线 CarPlay 配件角色。

### 2.3 干扰项

- 另一台 **OnePlus 8** 同时 BT Connected（PAN 等）。
- 日志里仍有 **nowplaying / iAP link ctrl / artwork**，像 **DiPlay 残留会话** 仍占着 RFCOMM；`CarPlayBluetoothLink` 在 inbound 已占用时会 **忽略后续 dial**（见源码注释）。
- Android Auto gearhead 也在听 Audi 的 A2DP 状态变化（`GH.WifiBluetoothRcvr`），与 CarPlay 争用同一 BT 链路的风险需隔离验证。

---

## 3. 期望链路 vs 实测断点

```
期望（无线 Source，见 [CARPLAY_CONNECT.md](../CARPLAY_CONNECT.md) / EXECUTION §14 / CARPLAY_REFS）：
  BT 配对 → RFCOMM(…caff dial 或 …cafe accept)
       → Detect → Link Sync → Ident → Auth
       → 0x4E0E / 0x4E0D / 0x5702 / 0x4300
       → 车机出 CarPlay 客户端 / 发 0x5703 或 0x4301
       → Wi‑Fi 加入车机热点 → RTSP → 出画

DiPlay（已通）：
  通常由 DiPlay dial …cafe → 完整 iAP2 → SSID "Carplay" → RTSP

Audi（当前）：
  ✅ BT 配对 + ACL Connected + 远程有 …caff
  ❌ 监测窗口未见对 60:E6:F0… 的 RFCOMM peer connected/accepted
  ❌ 未见新的 StartIdentification / IdentificationAccepted / Auth / CarPlayAvailability
  ❌ 车机 UI 无 CarPlay 客户端
  ❌ Wi‑Fi 未处于与车机同网（且旧 DiPlay 热点已掉）
```

**第一断点（高置信）：iAP2 RFCOMM 没有与 Audi 真正建链（或建了立刻被旧会话挡住）。**  
MAC / 名称伪装最多影响“车机愿不愿意去 dial …cafe”，属于第二梯队。

---

## 4. 根因假设（按优先级）

### P0 — RFCOMM 未对 Audi 建链 / 被旧会话占用

- `dialBonded` 只在 `CarPlaySourceSession.start()` 时扫一遍 bonded；之后再配对的 Audi **不会自动重 dial**。
- 若 DiPlay 仍占 `clientSocket`，对 Audi 的 dial 会被 `inbound session already active` 直接丢掉。
- 表现：手机侧 GL/nowplaying 很“热闹”，车机侧仍只是普通蓝牙电话。

### P1 — 车机未主动 dial …cafe（身份不像 iPhone）

真车机常见策略：看到“像 iPhone 的设备”才去连 phone UUID（…cafe）。  
当前对外身份：

- 适配器名：`Galaxy Z Flip4`
- MAC：Samsung OUI
- iAP2 Identification（若走到）：`Name=AADisplay`，`Model=CarPlaySource`，`Manufacturer=Nitsuya`

DiPlay 不挑剔；Audi 可能要求 **名称 / Model / ProductPlanUID** 更像 iPhone，或要求 Apple OUI。  
→ **伪装 MAC 落在这一档，且必须与“改 BT 名 + Ident 字段”一起做，才有意义。**

### P2 — Ident / Auth 被拒

即使 RFCOMM 通了，Audi 可能：

- `IdentificationRejected`（字段不像 Apple device）
- Auth 要求真 MFi / 不同挑战流程（本模块对 Accessory 签名不验，但 **Source 侧证书方向** 与真车机是否一致未在 Audi 上验证）

### P3 — Wi‑Fi handoff / 出画（后置）

早前 `Carplay` SSID + `IP_REACHABILITY_LOST` 是 **DiPlay 热点** 路径上的已知 Samsung 坑（`CarPlayWifiHandoff` 注释已写）。  
Audi 热点 SSID/策略不同；在 P0/P1 未过前不必深挖。

---

## 5. 与“伪装 MAC”的关系（明确答复）

1. **现在车机不显示 CarPlay，首要不是 MAC。**  
   证据：Audi 已连上蓝牙，却没有任何 iAP2 里程碑日志指向该地址。
2. **MAC 伪装是可选增强（P1），不是银弹。**  
   目标若做：BT 公共地址改 Apple OUI（如 `F0:XX:…` / 常见 iPhone 段），并同步：
   - `BluetoothAdapter.setName("iPhone")`（或接近真机名）
   - `Iap2Session` Identification：`Name/Model/Manufacturer/Serial` 改为 iPhone 风格
   - `DeviceTransportIdentifier` 使用**真实对外可见**的 BT 地址（避免 Android 隐藏 MAC 时用合成地址与空中不一致）
3. **USB 有线 fake iPhone（`archive/usb-carplay/USB_CARPLAY.md`）的 VID/MAC 伪装与本问题无关** — 本次是无线 Audi MMI。

---

## 6. 下一步监测计划（仍不动代码）

按顺序做，每步留 logcat +（可选）btsnoop：

| 步 | 动作 | 成功判据（logcat `AADisplay_CarPlay`） |
|----|------|----------------------------------------|
| M1 | 关掉 / 远离 DiPlay；断开 OnePlus；只留 Audi | bonded 仅 Audi（+键盘可忽略） |
| M2 | 停再启 CarPlay 投影（或杀进程后重新 CONNECT），**在 Audi 已 bonded 之后**启动 | `RFCOMM connecting to 60:E6:F0…` |
| M3 | 观察 dial / accept | `RFCOMM peer connected/accepted … Audi` → `iAP detect` → `Identification*` → `AuthenticationSucceeded` → `CarPlayAvailability` |
| M4 | 看车机 UI | MMI 出现 Apple CarPlay / 可用设备 |
| M5 | 若 M3 有 `IdentificationRejected` 或无 dial | 再评估 **BT 名 + Ident + Apple OUI MAC**（仍先抓证据再改代码） |
| M6 | 若 M3–M4 过但无画 | 抓 `0x5703`/`0x4301` + Wi‑Fi specifier（与 DiPlay 热点路径对比） |

推荐一条命令（复测时）：

```bash
adb logcat -v threadtime | rg -i 'AADisplay_CarPlay|Audi_MMI|60:E6:F0|deafdecaca|RFCOMM|iAP '
```

抓取目录约定：`docs/capture/audi_carplay_<YYYYMMDD_HHMMSS>/`。

---

## 7. 代码改动计划（批准后再做，本轮不实施）

### 阶段 A — 先打通 Audi RFCOMM（高 ROI）

1. Audi 配对完成 / ACL Connected 后 **重触发 `dialBonded`**（勿只在 session start 扫一次）。
2. 会话占用策略：对非目标 peer（DiPlay）可释放，或 UI 选择“目标车机”，避免 `inbound already active` 挡住 Audi。
3. 日志增强：RFCOMM peer 地址/名称、Ident 摘要、Rejected 原因字段全打出。

### 阶段 B — 身份像 iPhone（含可选 MAC）

1. BT 显示名改为 iPhone 风格。  
2. Identification TLV 改为 iPhone 常见 Name/Model/Manufacturer/ProductPlanUID。  
3. **评估** root 下改 BT 公共地址为 Apple OUI；必须与 `localAddress()` / `0x4E0E` 一致。  
4. 用真 iPhone 连同一辆 Audi 做一次对照（btsnoop），确认车机是 dial …cafe 还是等手机 dial …caff。

### 阶段 C — Auth / Wi‑Fi / 出画

1. 若 Auth 失败：对照真 iPhone btsnoop，核对证书/挑战方向。  
2. Audi 热点 join：复用 `CarPlayWifiHandoff` hold 策略，针对 Audi SSID 验证。  
3. RTSP/分辨率按 Audi 能力协商（与 DiPlay 1280×720 可能不同）。

---

## 8. 社区「CarPlay 投屏」对照（角色 + Audi 特例）

社区俗称 **「CarPlay 投屏」**（本机样本之一：CarWith & Carplay Bridge）= **无线 Source（假 iPhone）**。  
用户反馈：**别的车机好用，Audi 不太好用** → 问题重心不是“社区有没有实现”，而是 **Audi MMI 比一般国产/安卓车机更挑剔**。

| 先例 | 角色 | 与 Audi |
|------|------|---------|
| **CarPlay 投屏 / CarWith** | 无线 Source | **其它车 OK，Audi 差**（用户实测） |
| Magic Box / AI Box（多有线 USB） | Source | 对“宽松/有线”车机成熟；无线 OEM 另论 |
| DiPlay 等 | Receiver（假车机接真 iPhone） | 与“投屏上车”反向，不能当 Audi Source 成功样板 |

CarWith APK 静态（`37ec20b-10`，包名 `com.baidu.carlife.xiaomi`）：iAP2 cafe/caff、Bonjour、`phoneMac` + Shizuku 读 `bluetooth_address`、selected-peer RFCOMM、Auth 不验 Apple 根链。  
→ 说明通用 Source 栈在“好说话的车机”上够用；**Audi 另有门槛**。

### 8.1 为何 Audi 更难（假设，待抓包钉死）

结合本机 `Audi_MMI_8280` + Audi 官方/TSB 行为：

| # | Audi 特有点 | 对投屏/AADisplay 的影响 |
|---|-------------|-------------------------|
| A1 | 入口走 **Audi Smartphone Interface**，不是随便 BT 连上就出 CarPlay 磁贴 | 必须完成 iAP2 → Availability；仅 HFP/A2DP 不够 |
| A2 | 无线会话：**BT 引导 + 车机 5GHz Wi‑Fi**（隐私模式会关 5GHz；部分 MIB 代际无线 CarPlay 本身受限/仅有线） | 投屏在别车用 2.4G/`Carplay` 热点通，在 Audi 上可能卡在 handoff |
| A3 | OEM 更可能查 **BT 身份**（名 / Apple Device ID / EIR），比山寨 Receiver 严 | 解释“投屏别车好用、Audi 差”；MAC/名伪装升为 **Audi 专项**，非通用首刀 |
| A4 | 真 iPhone 流程：MMI 侧选设备 → 车机主动建 CarPlay；BT 连上后常切到 Wi‑Fi 再弱化 BT | Source 必须在车机“选中”窗口内完成 dial/listen，不能只靠 session 启动时扫一次 bonded |
| A5 | 本机当前：Audi **bonded 但常 DISCONNECTED**；ACL 不稳时投屏/AADisplay 都起不了 iAP2 | 先保证稳定 BT ACL，再谈协议 |

### 8.2 计划修正（相对 §6/§7）

1. **不要把“追上 CarWith 通用能力”当成 Audi 银弹**——投屏在 Audi 上也不稳，说明卡点在 **Audi 策略**，不单是 AADisplay 缺功能。  
2. 对照实验改为三路（仍不动代码）：  
   - 真 iPhone → 同一台 Audi（基线：车机 UI 何时出磁贴、是否要 5GHz、BT 名/MAC）  
   - CarPlay 投屏/CarWith → Audi（抓它卡在哪一层）  
   - AADisplay → Audi（对齐同一断点）  
3. 若投屏与 AADisplay 都死在 **无 RFCOMM / 无 Ident**：优先 **Audi 身份门（名/EIR/Apple OUI）+ 车机主动 dial …cafe**。  
4. 若投屏能过 iAP2 但 AADisplay 不能：再 diff selected-peer / Ident 字段 / MAC 上报。  
5. 若都能过 iAP2、都死在 Wi‑Fi：专攻 Audi **5GHz / 隐私模式 / SSID 形态**（与 DiPlay 的 `Carplay` 热点不是同一条路）。

---

## 9. 本轮结论（给后续实现用）

1. 社区「CarPlay 投屏」= Source；**别车好用 ≠ Audi 好用**。  
2. 本机 Audi 问题应按 **OEM 特例** 查，不是简单“抄投屏 / 伪装 MAC”。  
3. 现场仍未见对 Audi 的 iAP2 里程碑；同时 Audi ACL 易掉——先 M1–M3，再身份，再 5GHz handoff。  
4. 最高价值：真 iPhone + 投屏 + AADisplay **三路同车对照抓包**，用断点差定位 Audi 门槛。

---

## 10. Audi 鉴权逻辑调研（相对 §8，2026-10-16）

> 范围：公开规范 / WWDC / Audi TSB / VAG MIB 逆向（mib2q-carplay-rgi）+ 本仓库 Source 行为对照。  
> **仍不动代码**；结论用于修正「Audi 卡在 Auth」的心智模型。

### 10.1 先纠正角色：谁在验谁

无线 CarPlay 上，iAP2 **Accessory Authentication**（`0xAA00…0xAA05`）的方向是：

| 角色 | 谁 | 做什么 |
|------|----|--------|
| Device（Source /「手机」） | 真 iPhone / AADisplay / CarWith | 发 `0xAA00` 要证书 → `0xAA02` 挑战 → 收 `0xAA03` → 发 `0xAA05` |
| Accessory（车机） | Audi MMI / DiPlay | 用 **MFi 协处理器** 出证 + RSA 签挑战 |

Apple 安全文档与社区 iAP2 拆解一致：**验证者是手机，被验的是车机 MFi 芯片**。  
本模块与 CarWith 一样 **不验 Apple 根链、直接 `AA05`**——这只影响「假 Source 是否信车机」，**不会让车机去验手机有没有 MFi**。

因此 §4 P2「Auth 要求真 MFi / Source 侧证书方向」对 **Audi 拒假 iPhone** 基本是误读：

- Audi（真车）**有** MFi（MIB 系常见 I²C 协处理器；MHI2Q 逆向指向 `/dev/i2c8` addr `0x11`）。
- 若 RFCOMM 已通到 Auth，Audi 侧正常应能回 `AA01/AA03`；假 Source 再 `AA05` 即可过这一层。
- **「别车好用、Audi 差」不太可能死在 AA00–AA05 本身**；更可能死在 **Auth 之前的身份/发现门**，或 **Auth 之后的 Wi‑Fi/用户确认/AirPlay 配对**。

本仓库 `Iap2Session`：Source 主动 `0x1D00` → 收配件 Ident → `0x1D02` → `0xAA00…AA05`（不验签）→ `0x4E0E/4E0D/5702/4300`。  
对 DiPlay 已通；对 Audi **尚未出现任何 Ident/Auth 里程碑**（见 §3 / 抓取目录）——说明当前断点仍在 **RFCOMM 之前或刚建链就被挡**，鉴权报文还没轮到。

### 10.2 Audi / VAG 侧「鉴权」实际是多层门控

把「鉴权」拆成 Audi 会拦的层（由外到内）：

```
L0  ASI UI / 用户选设备          ← Audi Smartphone Interface，不是普通 BT 连上就出磁贴
L1  BT 发现身份门（EIR / SDP）   ← 「像不像可做 CarPlay 的手机」
L2  iAP2 Link + Identification   ← 配件 Ident；手机若误回 0x1D01 会露馅
L3  Accessory Auth AA00–AA05     ← 手机验车机 MFi（假 Source 通常不验）
L4  用户确认后才给 Wi‑Fi 凭据    ← WWDC + Audi TSB：确认前不发 SSID/口令
L5  5GHz AP + Apple Device IE    ← 车机热点侧；隐私模式会关 5GHz
L6  AirPlay Pair-Setup / 媒体加密 ← StartSession 之后；FairPlay/配对身份
```

| 层 | 依据 | 对投屏/AADisplay |
|----|------|------------------|
| **L0** | Audi TSB（如 MC-10254054）：经 ASI 配对 → MMI 提示启用无线 CarPlay → **接受后**才经 BT 下发 Wi‑Fi；会话起来后 MMI **关 BT**；热点名常不出现在手机 Wi‑Fi 列表（专用通道） | 仅 bonded/HFP 不够；必须进 ASI「选中/确认」窗口 |
| **L1** | WWDC17-717：HU **查 Apple CarPlay Bluetooth EIR** 才展示 CarPlay 能力设备。VAG MHI2Q 工厂 `btstack`：扫 EIR AD type `0x07`，命中 UUID `ec884348-cd41-40a2-9727-575d50bf1fd3` → wireless CarPlay bit；另查 iAP2 UUID → iAP bit。另有 CarPlay **SDP 发现记录**（无 RFCOMM，仅标记） | AADisplay 目前只挂 `…cafe/…caff` RFCOMM，**未见**注入该 CarPlay EIR UUID / 第二 SDP——解释「Audi 不 dial cafe / 不出磁贴」强于「MFi 失败」。MAC/名/OUI 落在同一档（软过滤叠加） |
| **L2** | 规范：Device 发 `0x1D00`，Accessory 回 `0x1D01`。本模块若收到 peer 的 `0x1D00` 会回自己的 `0x1D01`（`AADisplay`/`CarPlaySource`/`Nitsuya`）——**配件形 Ident**，真车若反向要手机 Ident 会难看 | DiPlay 松；Audi 若走到这步可能 `IdentificationRejected`（待抓包） |
| **L3** | MHI2Q：`phone → Identify/Auth → MFi cert/sign → Wi‑Fi params → Availability → StartSession`；生产路径用硬件 signer | 假 Source 不验签即可；**不是 Audi 专项杀手** |
| **L4** | WWDC：用户确认前 **不要** 给 Wi‑Fi 凭据；Audi TSB 同序 | 投屏在「无确认就狂要 5702」的车机上能糊弄；Audi 可能一直不给 `5703` |
| **L5** | WWDC：5GHz + Apple Device IE；Audi MIB3 BT/Wi‑Fi 在 ConBox High（J949），非 MMI 本体；部分车仅有线 | 与 DiPlay 的 `Carplay` 2.4G 热点路径不同（§8 A2） |
| **L6** | StartSession 后 AirPlay 控制/配对；MHI2Q 强调 `deviceID` / pairing `pi` 一致才发 `0x4301` | 黑屏/断连阶段再查；当前未到 |

### 10.3 和 §8 / CarWith 的对齐

| 观察 | 鉴权含义 |
|------|----------|
| CarWith「别车 OK、Audi 差」且同样 **不验 Apple 根链** | 共同短板不在 L3 验签，而在 **L0/L1（及可能 L4）**——Audi 更严的发现/ASI/确认策略 |
| DiPlay（Receiver）对本机可通 | 证明本栈 L2–L3–handoff 对**宽松配件**成立；不能证明 Audi Source 路径 |
| 本机抓取无 Ident/Auth | 支持「卡在 L0/L1 或 RFCOMM」，**尚无 L3 失败证据** |
| §4 P2 / §7C「对照证书方向」 | 降级：仅当 btsnoop 已见 `AA00` 且出现 `AA04`/超时再查；默认优先 L1 EIR + L0 ASI |

### 10.4 抓包时怎么判定「死在哪一层鉴权」

| logcat / btsnoop 信号 | 判定 |
|----------------------|------|
| 无对 `60:E6:F0…` 的 RFCOMM；Audi 从不 dial `…cafe` | **L0/L1**（身份/EIR/ASI），不是 MFi |
| 有 RFCOMM + detect/link，随即断，无 `0x1D*` | Link/角色，仍非 Auth |
| 有 `IdentificationRejected` / 异常 `0x1D01` 往返 | **L2** |
| 有 `0xAA00` 后 `AuthenticationFailed` / 无 `AA01` | **L3**（少见；真 Audi 应有芯片） |
| 有 `AA05` + `0x4300`，无 `5703`/`4301` | **L4**（未确认）或车机策略拒 handoff |
| 有 `4301`，Wi‑Fi 加不上 / 无 5GHz | **L5** |
| 有 IP + RTSP，Pair-Setup/出画失败 | **L6** |

### 10.5 对计划的修正（叠在 §8.2 上）

1. **不要再把「补真 MFi / 验根链」当 Audi 首刀**——方向反了；假 Source 本来就不该被车机用 AA 流程验。  
2. Audi 专项鉴权优先序：**L1（CarPlay EIR UUID + 可选 Apple DID/名/OUI）→ L0（ASI 选中窗口内 dial/listen）→ L4（等确认再 5702）→ L5**。  
3. 三路对照时，btsnoop 除 RFCOMM 外务必看手机 **EIR AD 0x07** 是否含 `ec884348-…-bf1fd3`（真 iPhone 有、投屏/AADisplay 有无）。  
4. 仅当三路都过 RFCOMM 且出现 `AA04`/IdentReject 时，再回头啃 L2/L3 字段与证书。
