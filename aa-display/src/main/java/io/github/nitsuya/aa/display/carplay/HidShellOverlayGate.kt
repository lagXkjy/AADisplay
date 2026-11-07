package io.github.nitsuya.aa.display.carplay

import io.github.nitsuya.aa.display.CoreApi

/**
 * Keeps system_server [aaUiShellCapture] aligned with Host Recents / app-picker
 * visibility so phone BT mouse routes to [CarPlayInputBridge] instead of pane VDs.
 */
internal object HidShellOverlayGate {
    fun sync() {
        val open = CarPlayRecentsOverlay.isVisible() || CarPlayAppPickerOverlay.isVisible()
        runCatching { CoreApi.reportHidShellOverlay(open) }
    }
}
