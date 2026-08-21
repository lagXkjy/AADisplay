package io.github.nitsuya.aa.display.util

/**
 * Extra reconnect sizing trace（定位 HU 全宽 vs 内容区分裂）。
 * 默认关闭，避免断线重连时日志过多。
 * 主路径结算见 [DisplayProfileSettle] / CoreManagerService displayProfile logs。
 */
internal object ReconnectSizingTrace {
    const val ENABLED = false
}
