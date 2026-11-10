# AADisplay-Split-Lite

[![基于](https://img.shields.io/badge/基于-Nitsuya%2FAADisplay-blue?logo=github)](https://github.com/Nitsuya/AADisplay)
[![分支参考](https://img.shields.io/badge/参考-Stashboy%2FAADisplay-blue?logo=github)](https://github.com/Stashboy/AADisplay)
![Xposed Module](https://img.shields.io/badge/Xposed-Module-blue)
![Android 13+](https://img.shields.io/badge/Android-13%2B-brightgreen?logo=android)

让手机上的 App **出现在车机屏幕上**，左右分屏、用方向盘或触控操作。  
基于 [Nitsuya/AADisplay](https://github.com/Nitsuya/AADisplay) / [Stashboy/AADisplay](https://github.com/Stashboy/AADisplay) 的精简版。

**你可以用两种方式连车机：**

| 方式 | 适合谁 |
|------|--------|
| **Android Auto（AAP）** | 车机支持 Android Auto；手机装过 AA 时**默认**走这条（本模块直接说 AAP，**不依赖** gearhead 钩子；装 AA 主要为了自动提取证书） |
| **无线 CarPlay** | 车机支持 CarPlay（例如 DiPlay 等盒子）；**手机没装 Android Auto 也能用**。目前只在 **Audi MIB3（3874 / 4026）** 与 **DiPlay** 上测过 |

当前版本：**1.0** · 更新说明见 [CHANGELOG.md](CHANGELOG.md)

---

## 先看能不能用

请逐条对照：

1. 手机系统 **Android 13 或更高**（**缺一不可**）
2. 手机已 **Root**，并装好 **LSPosed**（或同类 Xposed 框架）（**缺一不可**）
3. **走 Android Auto**：手机装过 **Android Auto**，车机支持 AA
4. **走无线 CarPlay**：车机支持 CarPlay，且能和手机 **蓝牙配对**（**不要求**手机装 Android Auto）

没 Root / 没 LSPosed → 本模块装了也不会生效，请先完成这两步再往下看。

---

## 能做什么（白话）

- 车机上左右 **两个窗口**，各自跑不同 App（比如左地图、右音乐）。
- 每个窗口最多同时「挂着」**3 个** App，随时切换，不用每次重开。
- 中间那条 **三个小点** 可拖动调左右大小；拖到边可以变成单侧全屏。
- 用 **方向盘** 切歌、换左右窗、打开应用列表。
- 有的车上，仪表盘横条能显示 **QQ 音乐 / 汽水** 的歌词。
- 可选 **无线 CarPlay**：声音从车机喇叭出，手机自己静音（电话铃声/通话仍走手机）。

**刻意做简单的地方：**

- 没有一堆设置项（断开后约 **3 分钟**内再连，画面和应用尽量还在）。
- **桌面上没有图标**——要打开模块状态页，去 **LSPosed → AADisplay**。
- 不用再装嘟嘟、氢桌面之类第三方桌面。

---

## 安装（按顺序做）

### 1. 装好 APK

把本模块的 APK 装到手机上（自己编译见文末「自己编译」；或使用你拿到的安装包）。

### 2. 在 LSPosed 里打开模块

1. 打开 **LSPosed**
2. 找到 **AADisplay**（或本模块名称），打开开关
3. 「作用域」里勾选：**系统框架**（System Framework）——AAP / CarPlay 都只要这一项；**不要**再勾 Android Auto
4. **重启手机**（这一步很重要，不重启往往显示「未激活」）

### 3. 确认已经激活

1. 打开 **LSPosed → AADisplay**（记住：没有桌面图标）
2. 看状态是否为 **已激活**
3. 若提示 **需要重启** → 再重启一次手机

### 4a. 只用无线 CarPlay（车机没有 AA / 手机没装 AA）

手机**没有**装 Android Auto 时，状态页会默认走 **CarPlay**（看不到「Android Auto」选项），一般不用再改通道。

> **真机范围：** 无线 CarPlay 目前只测过 **Audi MIB3（软件 3874 / 4026）** 与 **DiPlay**；其它车机未验证。  
> **奥迪配对方向：** 必须由 **车机发现并配对手机**，不要在手机蓝牙设置里主动搜车 / 配对车机。

1. 打开状态页，允许 **蓝牙 / 附近设备** 权限（若弹出）
2. 确认状态类似「等待车机连接」
3. 在 **车机** 上用 **无线 CarPlay** 发现并连接本机（首次可能弹出配对码 **3939**）；奥迪务必走车机侧发起，勿用手机去配车
4. 用 DiPlay 盒子时：盒子里 **设置 → 显示 → 帧率** 调成 **60**（新装常常是 30，画面会顿）

车机连上后同样是左右分屏；某一侧是空的，就点空白处选 App。日常操作见下文「无线 CarPlay 多说几句」和「日常怎么用」里的分屏 / 方向盘说明。

### 4b. 第一次用 Android Auto（AAP）

1. 手机装好 **Android Auto**（用于自动提取 CarService 证书；原厂 Google 库车机需要）
2. 打开状态页，确认通道是 **Android Auto（AAP）**，证书卡片显示已提取
3. 允许蓝牙权限；无线连车机，或 USB 按车机 / DHU 说明连接
4. 正常时会出左右分屏；某一侧是空的，就 **点一下空白处**，选要打开的 App

> 公开构建**不再**走 gearhead「原路」投屏，也**不必**为了出画去勾 LSPosed 里的 Android Auto。  
> 更细的证书 / DHU 说明见 [docs/AAP_NATIVE.md](docs/AAP_NATIVE.md)。

手机**已装** Android Auto、但想改用 **无线 CarPlay**：打开状态页，把出口通道改成 **CarPlay**，再在 **车机** 上发起无线 CarPlay 配对（首次可能弹出配对码 **3939**）。奥迪 MIB3：只能由车机发现手机，不要用手机配对车机。

---

## 日常怎么用（Android Auto / AAP）

### 选 App、换 App

- 空窗口 → **点空白** → 选出 App。
- 左右两边互不影响；**同一个 App 不能同时开在左右两边**。
- 每个窗口最多挂 3 个 App；再加新的，会把最旧的那个挤掉。

### 中间三个小点（分屏条）

| 你怎么做 | 结果 |
|----------|------|
| **按住拖动** | 调左右窗口大小；拖到很靠边 → 变成单侧全屏 |
| **轻点一下** | 左右窗口对调；已经是全屏时 → 换成看另一侧 |
| **长按，再松手** | 打开「最近应用」三列列表 |

注意：长按要 **松手之后** 才会打开列表，按住不放不会开。

### 「最近应用」三列列表

长按中间小点并松手后会出现：

| 列 | 是什么 |
|----|--------|
| 左边一列 | 左窗口里挂着的 App |
| 中间一列 | 右窗口里挂着的 App |
| 右边一列 | 手机自己屏幕上的任务 |

常用操作：

- **点某一行** → 那个 App 显示到前台，列表关掉
- 点 **×** → 关掉这个任务
- 左/中列下面的 **「添加应用」** → 往这个窗口再挂一个 App（手机上已开着的同名 App 会直接搬进来）
- 右列（手机）点某一行 → 在**手机自己**的屏幕切到该 App（不搬进车机）

### 拔掉线 / 断连之后

- 大约 **3 分钟（180 秒）** 内再连上，左右窗口和应用往往还在。
- 超过这个时间，可能要重新选 App；若模块记住了上次布局，会尽量自动恢复。

### 方向盘

| 操作 | 效果 |
|------|------|
| **短按** 播放/暂停、上一曲、下一曲等 | 控制 **当前正在看的那个窗口** 里的 App |
| **长按** 下一曲 / 快进 | 和「点一下分屏条」一样：对调左右，或全屏时换另一侧 |
| **长按** 上一曲 / 快退 | 打开 / 关掉「最近应用」列表 |

### 手机上的蓝牙鼠标 / 键盘（可选）

键鼠要连在 **手机** 上（不是车机 USB），并且车机这边已经连着、分屏在用。

- 在窗口里移动、点左键 ≈ 用手指点屏幕
- 光标停在中间分隔条上再拖 = 调左右大小；短按 = 对调；长按再松手 = 打开列表
- 鼠标中键 = 跳到另一侧窗口
- 键盘：`Ctrl + 方向键/WASD` 微调比例，`Ctrl + S` 对调，`Ctrl + R` 或 `Tab` 打开列表

### 音乐和仪表歌词

支持把歌词推到部分车型的仪表横条 / AA 顶栏，目前跟这几个播放器（**同时只跟正在播的那一个**）：

- QQ 音乐车载
- QQ 音乐 HD
- 汽水

一边播抖音一边播音乐会互相抢声音：同一时间只让一个出声。打开地图、浏览器 **不会** 自动把正在播的音乐掐掉。

---

## 无线 CarPlay 多说几句

出口是 **CarPlay** 时（手机没装 AA 会自动默认；有 AA 则在状态页手动切换）：

- **目前只测过 Audi MIB3（3874 / 4026）与 DiPlay**；其它车型/盒子未保证可用
- **奥迪无线配对**：只能由 **车机发现手机并配对**，不要在手机上搜车 / 配对车机
- 车机上同样是左右分屏，可以触控、调中间分隔条、开应用列表
- **App 的声音从车机喇叭出来**，手机喇叭会静音；**打电话仍走手机**
- **蓝牙直接配 Wi‑Fi**：车机经蓝牙下发热点信息，手机自动连上再出画（不用自己手填 Wi‑Fi）
- **方向盘控制**：短按控当前窗口（切歌等）；长按下一曲 ≈ 换窗；长按上一曲 ≈ 打开应用列表
- **奥迪 MIB3**：仪表台可显示 **QQ 音乐车载 / 汽水** 的歌曲、歌词与封面
- **不支持多点触控**（受 CarPlay 协议限制，单指触控可用）
- 断线后同样大约 3 分钟内再连，尽量保留状态

和 Android Auto **可以一起用（有 AA 时二选一出口），也可以只开 CarPlay**——车机没有 AA、手机没装 AA 时，只开 CarPlay 即可。

---

## 常见问题

**Q：桌面上找不到 AADisplay？**  
A：正常。请到 **LSPosed** 里点模块名称打开状态页。

**Q：状态显示未激活 / 需要重启？**  
A：确认 LSPosed 里模块已开，作用域勾了「系统框架」，然后 **重启手机**。（走 Android Auto 只需装过 AA 供证书提取，**不要**勾 LSPosed 的 Android Auto。）

**Q：手机没装 Android Auto，能用吗？**  
A：可以，只要车机支持 **无线 CarPlay**。LSPosed 勾「系统框架」即可；状态页会默认 CarPlay，按上面「4a」连车。

**Q：第一次连 AA 一直卡在引导、进不了分屏？**  
A：先装齐 Google App、地图、语音服务，走完 AA 首次设置；进过分屏后再卸也可以。

**Q：改完模块文件 / 装了新 APK，钩子好像没变？**  
A：模块代码改动需重装 APK；凡涉及系统侧（VirtualDisplay / 钩子）的改动，仍要 **重启手机**生效（没有代码驻留 Android Auto 进程，force-stop AA 不再有用）。

**Q：有线 USB 假 iPhone 那种 CarPlay？**  
A：仓库里有实验代码（见 [`archive/usb-carplay/`](archive/usb-carplay/)），目前仍是 **半成品**，公开版 **未开放**，状态页也没有入口；日常请用无线 CarPlay。

---

## 免责声明

用爱发电。不同手机差异巨大，没有义务为你的机型单独适配；也无法保证能解释「为什么你不能用」。

使用本模块即代表自愿承担一切后果，包括但不限于设备损坏、驾车事故。  
任何由本项目衍生出的项目，本项目不承担任何责任。  
开发者可能在任何时间停止更新或删除项目。

- 请不要在行驶过程中使用视频应用。
- 请不要在行驶过程中操作应用。

## Thanks

- [Nitsuya](https://github.com/Nitsuya) — 原作者
- [Stashboy](https://github.com/Stashboy) — 分支作者
- [prototux/AARE](https://github.com/prototux/AARE) — Android Auto（AAP）协议栈

## License

继承上游许可证，GPL-3.0。

---

## 自己编译（给会命令行的人）

```bash
export GRADLE_USER_HOME="$HOME/.gradle"
./gradlew :aa-display:assembleDebug
./gradlew :aa-display:assembleRelease
```

生成的包在：`aa-display/build/outputs/apk/` 下，文件名类似 `aa-display-1.0.apk`。

---

## 给开发者

改代码、查链路、CarPlay 协议细节，请从这里进：

| 想了解什么 | 去哪看 |
|------------|--------|
| 项目约定、别乱改什么 | [AGENTS.md](AGENTS.md) |
| 运行时整条链路 | [docs/EXECUTION.md](docs/EXECUTION.md) |
| 无线 CarPlay 怎么连上 | [docs/CARPLAY_CONNECT.md](docs/CARPLAY_CONNECT.md) |
| 其它专题文档 | [docs/](docs/) |
| 旧版用户说明 | [docs/archive/](docs/archive/) |
