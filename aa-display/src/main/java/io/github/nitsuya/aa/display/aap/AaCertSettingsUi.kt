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
            toast(activity, "正在提取…")
            return
        }
        busy = true
        binding.btnAapCertExtract.isEnabled = false
        binding.tvAapCertState.text = if (force) "正在重新提取…" else "正在检测已安装 Android Auto…"
        io.execute {
            val st = try {
                PhoneCertStore.ensureExtracted(activity, force = force)
            } catch (e: Throwable) {
                PhoneCertStore.status(activity).also {
                    main.post {
                        toast(activity, "提取失败：${e.message ?: e.javaClass.simpleName}")
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
                        if (st.hasCarService && !st.expired) "已更新证书" else (st.lastError ?: "提取未成功"),
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
                append("已安装 AA ")
                append(st.gearheadVersionName ?: "?")
                append(" (")
                append(st.gearheadVersionCode ?: "?")
                append(")")
            } else {
                append("未检测到 com.google.android.projection.gearhead")
            }
            if (st.modulusFingerprint != null) {
                append("\n指纹 ")
                append(st.modulusFingerprint)
            }
            if (st.notAfter != null) {
                append("\n有效期至 ")
                append(st.notAfter)
            }
            if (!st.lastError.isNullOrBlank()) {
                append("\n上次错误：")
                append(st.lastError)
            }
            append("\n优先用已落盘 CarService；失败回落自签。AA 安装/更新会自动重提；卸载不删证书。")
        }
        binding.tvAapCertDetail.text = detail
        binding.btnAapCertExtract.text = when {
            !st.gearheadInstalled && st.hasCarService && (st.expired || st.nearExpiry) ->
                "安装较新 Android Auto 后提取"
            !st.gearheadInstalled -> "安装 Android Auto 后点此重试"
            st.expired || st.nearExpiry -> "更新 AA 后重新提取"
            st.hasCarService -> "重新提取（AA 更新后）"
            else -> "从已安装 Android Auto 提取"
        }
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
