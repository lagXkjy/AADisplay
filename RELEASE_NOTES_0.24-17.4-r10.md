# AADisplay 0.24#17.4-r10 版本更新

相对上一版 r9，这次重点是「少装 Google 也能顺畅上车」和锁屏下全屏把手可用。

## 新功能 / 体验优化

### 不必装齐 Google 也能过 FRX

- 连接时 Google App / Maps / TTS 的强制安装与版本检查会被判为已就绪，减少因缺包卡在首次设置的情况。
- 缺 Maps 时不再掉进导航占位页（该页容易把车机进程弄崩）。

### 连接更干净

- 抑制 Coolwalk 空媒体卡：连接与重连时少闪、少占位空白媒体界面。

## 问题修复

### 锁屏下全屏 peel

- 手机锁屏时，全屏把手仍可拖出退出、点按切换、长按打开 Recent（不再依赖车机 presentation 被 Keyguard 挡住的那条触控路径）。

## 使用提示

1. 安装后请在 LSPosed 确认已启用 **System Framework** + **Android Auto**，并重启一次。
2. 无桌面图标：从 LSPosed → AADisplay 打开激活状态页。
3. 详细技术变更与真机核对项见 [CHANGELOG.md](CHANGELOG.md)。

通过网盘分享的文件：aa-display-0.24-17.4-r10.apk
链接: https://pan.baidu.com/s/1NT0TYWEH0u7-r1TdudBoow?pwd=n6st 提取码: n6st