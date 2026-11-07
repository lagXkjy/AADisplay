package io.github.nitsuya.aa.display.carplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.ui.aa.split.SplitPane
import io.github.nitsuya.aa.display.util.AABroadcastConst

/**
 * IME pane for the Host encode canvas (CarPlay + AAP). [SplitImeController]
 * broadcasts [AABroadcastConst.ACTION_IME_VISIBILITY]; [CarPlayGlCompositor]
 * paints the chip and [CarPlayInputBridge] hit-tests it. Bound from
 * [CarPlayHostRuntime] / [io.github.nitsuya.aa.display.aap.AapDisplayBridge].
 */
internal object CarPlayImeChip {
    @Volatile
    var pane: Int = SplitPane.FULLSCREEN_NONE
        private set

    @Volatile
    var label: String = "收起键盘"
        private set

    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    fun bind(context: Context) {
        val app = context.applicationContext
        if (receiver != null && appContext === app) {
            syncFromService()
            return
        }
        unbind()
        appContext = app
        label = runCatching { app.getString(R.string.hide_ime) }.getOrDefault(label)
        val filter = IntentFilter(AABroadcastConst.ACTION_IME_VISIBILITY)
        val registered = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != AABroadcastConst.ACTION_IME_VISIBILITY) return
                val reported = intent.getIntExtra(
                    AABroadcastConst.EXTRA_PANE,
                    SplitPane.FULLSCREEN_NONE,
                )
                pane = if (SplitPane.isValid(reported)) reported else SplitPane.FULLSCREEN_NONE
            }
        }
        app.registerReceiver(registered, filter, Context.RECEIVER_EXPORTED)
        receiver = registered
        syncFromService()
    }

    fun unbind() {
        val ctx = appContext
        val registered = receiver
        receiver = null
        appContext = null
        pane = SplitPane.FULLSCREEN_NONE
        if (ctx != null && registered != null) {
            runCatching { ctx.unregisterReceiver(registered) }
        }
    }

    /** Optimistic hide so the chip leaves the canvas before the broadcast returns. */
    fun dismissLocal() {
        pane = SplitPane.FULLSCREEN_NONE
    }

    private fun syncFromService() {
        val reported = runCatching { CoreApi.imePane }.getOrDefault(SplitPane.FULLSCREEN_NONE)
        pane = if (SplitPane.isValid(reported)) reported else SplitPane.FULLSCREEN_NONE
    }
}
