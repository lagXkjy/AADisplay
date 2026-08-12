package io.github.nitsuya.aa.display.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast

@SuppressLint("StaticFieldLeak")
object TipUtil {
    private lateinit var context: Context
    private lateinit var prefix: String

    fun init(context: Context, prefix: String = "") {
        TipUtil.context = context
        TipUtil.prefix = prefix
    }

    fun showToast(msg: String) {
        // Never throw into system_server (e.g. AMS Context hook missed on OEM ROMs).
        if (!::context.isInitialized) {
            log("TipUtil", "showToast skipped (context not initialized): $msg")
            return
        }
        runCatching {
            Toast.makeText(context, "${if (::prefix.isInitialized) prefix else ""}$msg", Toast.LENGTH_LONG).show()
        }.onFailure {
            log("TipUtil", "showToast failed: $msg", it)
        }
    }
}