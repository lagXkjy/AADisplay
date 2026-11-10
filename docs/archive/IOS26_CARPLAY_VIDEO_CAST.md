# iOS 26.4+ CarPlay「视频投屏」调研 — 与 AADisplay 卡顿的关系

> 日期：2026-10-16  
> 约束：**只调研 / 写报告，不改代码**  
> 问题：苹果从 iOS 26.4 起开放的 CarPlay 视频能力是怎么做的？能不能改善本仓库无线/有线 CarPlay Source 的**投屏卡顿**？

相关运行时链路：[EXECUTION.md §14](../EXECUTION.md)；协议对照：[CARPLAY_REFS.md](../CARPLAY_REFS.md)。

> **已归档**：一次性调研（2026-10-16），结论：苹果 video-in-car 与 AADisplay 实时 screen 流机制不同，不可照搬；仅作历史参考。

---

## 0. 结论（先看）

| 问题 | 答案 |
|------|------|
| 苹果开放的是什么？ | **停车态下的「隔空播放视频 → 车机 CarPlay 屏」**（内容流），**不是** iPhone 桌面/任意 App 的整屏镜像 |
| 「配合车机投屏 App」是什么？ | 多半是中文生态把 **APTV 类 DLNA 接收壳** 和 **苹果官方 AirPlay video-in-car** 混称；二者机制不同 |
| 能不能直接治 AADisplay 卡顿？ | **不能。** 产品目标是实时交互 UI（VD→编码→CarPlay screen 流）；苹果这条路是缓冲式点播/投片，延迟与交互模型都不匹配 |
| 有没有可借鉴的架构启示？ | **有，但只是思路**：交互流与内容流分流、能力协商门控、无线稳定性优先；不能靠「换用 videoPlayback」替代现有 screen 流 |

**一句话**：苹果解决的是「停车看片」；AADisplay 要的是「任意安卓 App 低延迟可触控出画」。两条路共用 CarPlay/AirPlay 家族协议外壳，**像素怎么产生、怎么缓冲、延迟预算完全不同**——抄苹果「视频投屏」治不了你现在的卡顿。

---

## 1. 时间线与官方口径

### 1.1 版本演进（公开信息交叉）

| 节点 | 内容 |
|------|------|
| WWDC / iOS 26 宣布 | Apple 官宣 **Video in the car**：停车时可在兼容车机上观看视频；对车厂要求集成 **CarPlay + AirPlay video** |
| iOS 26.0 | 多项 CarPlay 新能力落地；**video-in-car 未随 26.0 出货**（媒体共识） |
| iOS 26.4 beta | 二进制出现 `CarPlayVideoPlayback_*`、Apple TV onboarding 等字符串；媒体解读为「在铺轨」 |
| iOS 26.4 零售（heise 等） | **视频播放仍未真正集成进用户可用 CarPlay**；需车机支持 AirPlay 视频；OEM 侧何时跟上不明 |
| iOS 27 | 《CarPlay Developer Guide》 entitlement `com.apple.developer.carplay-video` 标注自 **iOS 27**；WWDC26《Rev up your CarPlay app》明确：已支持 AirPlay 的 App **无需改代码**即可在停车时把视频投到 CarPlay 屏；要在车机上**浏览片库**则需 Video App + CarPlay UI 模板 |
| 2026-09（MacRumors） | 截至当时：**尚无车厂官宣**支持 video browsing / AirPlay video-in-car；仅 iOS 升级不够 |

Apple 用户文档（Support）：*Play videos with CarPlay* — 停车、已连 CarPlay → 在支持的视频 App 或任意支持 AirPlay 的 App 里播放 → 点 AirPlay 选 **CarPlay 显示屏**；车机声明不可播视频时退化为**仅音频**。

Apple 对车厂（Developer / MFi）：*AirPlay video in the car* — 在非驾驶状态下把喜爱的视频投到 CarPlay 显示屏；需在车机系统集成 CarPlay 与 AirPlay video。

### 1.2 开发者两层能力（易混）

```
┌─────────────────────────────────────────────────────────────┐
│ A. AirPlay video → CarPlay 显示屏（「投片」）                   │
│    · 任意已支持 AirPlay 视频的 iPhone App                      │
│    · 用户在手机上选 AirPlay 目标 = 车机屏                     │
│    · 宣称自 iOS 26 体系；实际可用性强依赖车机 OEM             │
│    · 不要求 App 有 CarPlay Video entitlement                  │
└─────────────────────────────────────────────────────────────┘
                              ↑ 真正出画的媒体路径
┌─────────────────────────────────────────────────────────────┐
│ B. CarPlay Video App（「车机上浏览」）                         │
│    · entitlement: com.apple.developer.carplay-video（iOS 27） │
│    · CarPlay framework 模板：在 HU 上浏览/选片                 │
│    · 规范强制：Video App 必须支持 AirPlay video streaming     │
│    · 无 video-in-car 能力的车机：该类 App 在 CarPlay 主屏隐藏 │
│    · 可同时持 Audio + Video entitlement，无视频时仍出现在主屏 │
└─────────────────────────────────────────────────────────────┘
```

用户口语里的「iOS 26.4 原生投屏」≈ **A**（有时被媒体提前到 26.4）；「车机投屏 App」在国内经常指 **第三方 CarPlay 壳 + DLNA**，见 §3。

---

## 2. 苹果怎么做的（机制）

### 2.1 不是整屏镜像

经典 CarPlay 主画（地图/模板 UI）与 **Video in the car** 不是同一条媒体语义：

| | 经典 CarPlay screen / Remote UI | Video in the car（AirPlay video） |
|--|--------------------------------|-----------------------------------|
| 内容来源 | 手机渲染的 **交互 UI 帧**（模板或 Ultra 远程 UI） | **片子/节目** 的媒体资产（URL / HLS 等） |
| 延迟预算 | 低（触控、地图动画） | 高（允许缓冲、ABR） |
| 安全门 | 驾驶可用（模板约束） | **停车才出画**；行驶 → 音频 only |
| 谁选码率 | 手机侧实时编码为主 | 车机常做 ABR；手机可作 localhost HLS 代理 |
| 触控 | HID / 模板控件 | 播放器控件（播停、字幕、音轨等） |

独立协议研究（CarCast / tarplay 字段笔记，非 Apple 公开 PDF）与上述官方叙事一致：

- HU 在 RTSP `GET /info` 能力表里 **opt-in** `videoPlayback`（及关联 `videoPlaybackInfo` / `videoPlaybackAllowed` 等键）
- `videoPlaybackAllowed` 由车机按**停车/行驶**翻转；安全策略在能力应答里，而不是事后再砍流
- 未声明该能力时，`/info` 与旧版兼容 → **不影响**既有 audio / 经典 screen 镜像路径
- 控制面是 AirPlay 风格命令（如 insert 播放队列、setRate、playbackInfo 轮询）；像素侧常见形态是车机向手机要流（例如 **HLS-over-localhost 代理**），车机自己做缓冲与 ABR

要点：**「投屏」在苹果嘴里 = 投一段可缓冲的视频，不是把手机 framebuffer 实时搬到仪表。**

### 2.2 车机侧硬门槛

1. **OEM 集成** AirPlay video + 驾驶状态上报（MFi / 车机固件），不是「装个 App」就完事（原厂 CarPlay）。
2. **停车态**才允许视频出画；不可用时强制音频。
3. 经典 CarPlay 与 CarPlay Ultra 都宣称可接；Ultra 另有「每块屏一路视频流 + 本地 UI 低延迟合成」架构（WWDC24），那是**交互 UI** 的加强，与停车看片仍是不同用例。

### 2.3 App 侧硬门槛（Video App）

《CarPlay Developer Guide》补充规则摘要：

1. App 必须以视频播放服务为主业。
2. **必须**支持 AirPlay video streaming。
3. 无车机 video 能力时，Video 类 App 在 CarPlay 主屏隐藏。

WWDC26：已支持 AirPlay 视频的 App，停车时用户在手机选车机屏即可观看——**这才是「原生」最短路径**；车机浏览 UI 是增强层。

---

## 3. 别和「车机投屏 App」搅在一起

国内常见表述：「iOS 26.4 开放权限 + 车机装投屏 App」。拆开看：

| 形态 | 机制 | 与苹果官方关系 |
|------|------|----------------|
| Apple AirPlay → CarPlay 屏 | 系统 AirPlay 选中 CarPlay display；OEM `videoPlayback` | **官方 Video in the car** |
| CarPlay Video App（Netflix 类等，需 entitlement） | HU 上浏览 + 底层仍走 AirPlay video | **官方**（iOS 27 entitlement） |
| APTV 等「CarPlay 投屏」 | CarPlay 壳 App 内开 **DLNA 接收端**；发送端传的是**播放地址**，不是 iPhone 屏幕镜像；要求本地网络权限；可写死支持某段 iOS 版本 | **第三方**；借 CarPlay 当壳，**不是** Apple 开放的 screen mirror API |

APTV 自述（公开页）：「传送的是视频播放地址，不是把 iPhone 屏幕镜像到车机」；仅 DLNA；仅 AirPlay/Chromecast 的 App 投不过去。

因此：用户说的「原生投屏」若指「抖音/任意 App 画面实时到车机」，**苹果到 26.4/27 也没有开放这条**；开放的是**点播/投片管道**。

---

## 4. 与 AADisplay 现状对照

AADisplay CarPlay Source（见 EXECUTION §14）出画路径：

```
VD(双窗应用) → SurfaceTexture(OES) → CarPlayGlCompositor @供给 fps
  → MediaCodec AVC（Surface / 低延迟键 / IntraRefresh 或长 GOP）
  → ScreenStreamEgress（CarPlay screen / type≈110 数据通道）
  → 车机 / DiPlay 解码
```

已记录的卡顿相关事实（同一文档）：

- 供给帧率受 **Source 进程 cgroup** 影响（后台可掉到个位数 fps）；`CarPlayProcessBoost` 在 CP-live 期提权缓解
- 编码目标约 **1280×720 @60 / 8–16 Mbit**；会话内 60↔30 自适应
- DiPlay 解码帧率自持，**车机需设显示帧率=60**；忽略 Source SETUP fps
- 无线链路抖动、flush 策略、编码器启停竞态等亦会表现为「卡」

| 维度 | 苹果 Video in the car | AADisplay CP Source |
|------|----------------------|---------------------|
| 语义 | 内容播放（片子） | **任意 App UI 实时合成** |
| 帧从哪来 | 预编码资产 / HLS / 代理 | 每帧从 VD+GLES **现编** |
| 缓冲 | 鼓励（readyToPlay、loadedTimeRanges） | 尽量少（触控要跟手） |
| 交互 | 播控 | 分屏触控 / 分隔条 / Recents |
| 安全 | 停车门 | 无苹果停车门（自研 Source） |
| 卡顿主因类型 | ABR / 缓冲不足 / OEM 门 | **编码+合成+调度+无线** |

---

## 5. 能不能改善「投屏卡顿」？

### 5.1 直接搬用苹果方案 — 否

若把产品改成「只投视频 URL / 只缓冲播片」：

- 卡顿可能**看起来**好转（缓冲掩盖抖动），但产品不再是「投任意安卓应用」
- 真车 OEM 的 `videoPlayback` 路径对 **Android Source 伪装** 基本不可达（能力门、身份、FairPlay/DRM、停车态都在苹果–车厂闭环里）
- DiPlay / 白牌 Receiver 吃的是 **screen 流**，不是苹果停车投片命令集

所以：**不能指望「跟 iOS 26.4 一样做视频投屏」来修当前卡顿。**

### 5.2 可借鉴的架构启示（仍属调研，非实施清单）

下列是苹果体系里「为什么看起来更顺」的原因，对应到 AADisplay 只说明**问题分区**，不代表应照抄：

1. **交互流 vs 内容流分流**  
   Ultra：本地 UI 扛低延迟仪表；远程视频流扛富 UI。AADisplay 把**全部交互画面**压在一路实时 AVC 上 → 任何编码/Wi‑Fi 抖动都直接变「卡」。启示：若未来要「车内看片」子功能，应**另开缓冲通道**，不要挤占现有 screen 低延迟路径。

2. **能力协商，而不是硬扛**  
   车机用 `/info` 声明能不能播视频、当前允不允许。AADisplay 侧已有帧率自适应，但仍受 DiPlay「本地帧率设置」支配——卡顿排查应继续分清 **Source 供给不足** vs **Receiver 限帧** vs **链路丢包**。

3. **缓冲是延迟的交易**  
   苹果停车看片用缓冲换流畅；AADisplay 若盲目加大缓冲，触控会糊。卡顿若来自无线突发，应优先链路/码率/GOP/flush，而不是照搬 AirPlay 播放器缓冲模型。

4. **进程与无线优先级**  
   Apple 强调无线稳定性（尤其 Ultra）。AADisplay 已做 `CarPlayProcessBoost`；若卡顿仍在，应继续用日志区分 compositor rate、encoder 输出、egress 写出、对端解码——与「有没有 videoPlayback」无关。

5. **编码形态**  
   Ultra 材料提到 HEVC 等媒体能力增强；那是苹果–OEM 栈。AADisplay 当前 AVC + 固定编码尺寸是为 DiPlay/兼容性；换编码格式属于另一条实验，**不是** iOS 26.4 video-in-car 的直接移植。

### 5.3 对「当前卡顿」的归因优先级（调研建议）

在排除「误以为苹果开放了整屏镜像」之后，卡顿仍应按本仓库已有证据链查：

1. Source `CarPlayGlCompositor rate` 是否稳在目标 fps（cgroup / 热 / 后台）  
2. Encoder 是否掉帧或频繁重置  
3. Wi‑Fi / NCM 链路 RTT、重传、TCP 阻塞（`ScreenStreamEgress` 同步 flush 语义）  
4. Receiver（DiPlay）显示帧率是否锁 60  
5. 内容复杂度（双窗动画 vs 静态）导致的码率尖峰  

苹果 video-in-car **不在这条归因链上**。

---

## 6. 风险与边界说明

- 本报告基于 Apple Support / Developer 公开页、CarPlay Developer Guide entitlement 表、WWDC 公开演讲摘要，以及媒体（9to5Mac、heise、MacRumors）与独立协议观察文章；**非** Apple 内部规范全文。
- 协议字段名（如 `videoPlaybackAllowed`）来自第三方抓包叙述，用于理解形状；**禁止**据此在本仓库实现破解/绕过车厂门控的「假 videoPlayback」投片路径（与项目合规边界一致：自用 Source 兼容，不扩展为攻击能力）。
- iOS 版本号以 2026 年 Apple 年号制为准（26.x / 27）；与国内转述「26.4 已全面可用」可能不符——**以车机 OEM 是否声明 + 真机 AirPlay 列表是否出现 CarPlay 屏为准**。

---

## 7. 参考链接

| 来源 | URL / 位置 |
|------|------------|
| Apple Support — Play videos with CarPlay | https://support.apple.com/guide/iphone/play-videos-with-carplay-xykemxkw6he5/ios |
| Apple Developer — CarPlay（Video in the car） | https://developer.apple.com/carplay/ |
| CarPlay Developer Guide（entitlement / Video 规则） | https://developer.apple.com/download/files/CarPlay-Developer-Guide.pdf |
| WWDC26 — Rev up your CarPlay app | https://developer.apple.com/videos/play/wwdc2026/212/ |
| WWDC24 — Next-gen CarPlay architecture（远程 UI / 本地 UI / 多屏流） | https://developer.apple.com/videos/play/wwdc2024/10111/ |
| 9to5Mac — iOS 26.4 beta 代码线索 | https://9to5mac.com/2026/02/17/ios-26-4-preps-for-carplay-video-feature-code-reveals/ |
| heise — 26.4 零售仍未集成视频 | https://www.heise.de/en/news/CarPlay-in-iOS-26-4-These-new-features-are-available-11241344.html |
| MacRumors — iOS 27 车机支持现状 | https://www.macrumors.com/2026/09/17/which-ios-27-carplay-features-actually-work/ |
| tarplay — videoPlayback / 非镜像 / 缓冲控制面形状 | https://www.tarplay.com/blog/video-on-the-dash.html |
| APTV（第三方 DLNA 壳，对照用） | https://aptv.app/carplay |
| 本仓库出画与卡顿相关 | [EXECUTION.md §14](../EXECUTION.md)、[OPEN_CLOSE_LAG.md](../OPEN_CLOSE_LAG.md)（AA UI 开闭卡顿，非 CP 编码） |

---

## 8. 附录：一句话对照表（给后续 AI）

```
用户以为：iOS 26.4 开放「手机画面 → 车机」原生镜像，可学来治卡顿
实际苹果：停车态 AirPlay「投片」+（27）Video App 浏览；OEM 能力门；缓冲播放
AADisplay：实时 VD 合成 → AVC screen 流 → 可触控
结论：机制不同；不能靠跟苹果视频投屏改善当前投屏卡顿；最多借鉴「分流 / 协商 / 别用缓冲骗交互延迟」
```
