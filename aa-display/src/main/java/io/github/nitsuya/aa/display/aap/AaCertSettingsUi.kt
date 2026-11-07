package io.github.nitsuya.aa.display.aap

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.aap.cert.PhoneCertStore
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding
import java.util.concurrent.Executors

/**
 * Status-page card for AAP phone certificates.
 *
 * Auto-extracts from the **installed** Android Auto APK — no manual file picker.
 */
object AaCertSettingsUi {
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "aap-cert-extract").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    fun bind(activity: Activity, binding: ActivityMainBinding) {
        binding.btnAapCertExtract.setOnClickListener {
            runExtract(activity, binding, force = true)
        }
        refresh(activity, binding)
        // Warm extract once when the status page opens (idempotent if version matches).
        runExtract(activity, binding, force = false)
    }

    fun refresh(activity: Activity, binding: ActivityMainBinding) {
        applyStatus(activity, binding, PhoneCertStore.status(activity))
    }

    private fun runExtract(activity: Activity, binding: ActivityMainBinding, force: Boolean) {
        if (busy) {
            toast(activity, "正在准备…")
            return
        }
        busy = true
        binding.btnAapCertExtract.isEnabled = false
        binding.tvAapCertState.text = if (force) "正在重新准备…" else "正在检查 Android Auto…"
        io.execute {
            val st = try {
                PhoneCertStore.ensureExtracted(activity, force = force)
            } catch (e: Throwable) {
                PhoneCertStore.status(activity).also {
                    main.post {
                        toast(activity, "准备失败，请稍后重试")
                    }
                }
            }
            main.post {
                busy = false
                binding.btnAapCertExtract.isEnabled = true
                applyStatus(activity, binding, st)
                if (force) {
                    toast(
                        activity,
                        if (st.hasCarService && !st.expired) "已准备好，可以连车"
                        else "还没准备好，请先安装或更新 Android Auto",
                    )
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun applyStatus(
        activity: Activity,
        binding: ActivityMainBinding,
        st: PhoneCertStore.Status,
    ) {
        binding.panelAapCert.visibility = View.VISIBLE
        binding.tvAapCertState.text = st.summary
        val color = when {
            st.hasCarService && st.expired -> activity.getColor(R.color.color_warning)
            st.hasCarService && st.nearExpiry -> activity.getColor(R.color.color_warning)
            st.hasCarService -> activity.getColor(R.color.color_primary)
            st.gearheadInstalled -> activity.getColor(R.color.color_outline)
            else -> activity.getColor(R.color.color_error)
        }
        binding.tvAapCertState.setTextColor(color)

        val detail = buildString {
            if (st.gearheadInstalled) {
                append("已安装 Android Auto")
                st.gearheadVersionName?.let { append(" $it") }
            } else {
                append("还没装 Android Auto")
            }
            if (st.notAfter != null) {
                append("\n可用至 ")
                append(st.notAfter)
            }
            if (st.hasCarService) {
                append("\n更新 Android Auto 后会自动重新准备；卸掉应用也不影响已准备好的连车信息。")
            } else {
                append("\n请先安装 Android Auto，再点下方按钮。多数原厂车机需要这一步才能连上。")
            }
        }
        binding.tvAapCertDetail.text = detail
        binding.btnAapCertExtract.text = when {
            !st.gearheadInstalled && st.hasCarService && (st.expired || st.nearExpiry) ->
                "安装较新 Android Auto 后再准备"
            !st.gearheadInstalled -> "安装 Android Auto 后点此重试"
            st.expired || st.nearExpiry -> "更新 Android Auto 后重新准备"
            st.hasCarService -> "重新准备（更新 Android Auto 后）"
            else -> "从已安装的 Android Auto 准备"
        }
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
