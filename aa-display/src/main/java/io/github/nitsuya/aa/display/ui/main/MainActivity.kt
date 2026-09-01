package io.github.nitsuya.aa.display.ui.main

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import io.github.duzhaokun123.template.bases.BaseActivity
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding
import io.github.nitsuya.aa.display.util.DisplayDpiStore

class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::class.java) {

    @SuppressLint("SetTextI18n")
    override fun initData() {
        val buildTime = CoreApi.buildTime
        when (buildTime) {
            0L -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_error_outline_24)
                baseBinding.tvActive.text = "未激活"
                baseBinding.tvVersion.text = ""
                setStatusCardColor(getColor(R.color.color_error))
                val onError = getColor(R.color.color_on_error)
                baseBinding.tvActive.setTextColor(onError)
                baseBinding.tvVersion.setTextColor(onError)
            }

            BuildConfig.BUILD_TIME -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_round_check_circle_24)
                baseBinding.tvActive.text = "已激活"
                baseBinding.tvVersion.text = CoreApi.versionName
            }

            else -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_warning_amber_24)
                baseBinding.tvActive.text = "需要重启"
                baseBinding.tvVersion.text =
                    "system: ${CoreApi.versionName}\nmodule: ${BuildConfig.VERSION_NAME}"
                setStatusCardColor(getColor(R.color.color_warning))
            }
        }

        if (Build.VERSION.PREVIEW_SDK_INT != 0) {
            baseBinding.systemVersion.text =
                "${Build.VERSION.CODENAME} Preview (API ${Build.VERSION.SDK_INT})"
        } else {
            baseBinding.systemVersion.text = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }

        baseBinding.btnApplyVdDensityDpi.setOnClickListener {
            hideKeyboard()
            applyVdDensityDpi()
        }
        baseBinding.btnResetVdDensityDpi.setOnClickListener {
            hideKeyboard()
            resetVdDensityDpi()
        }
        baseBinding.etVdDensityDpi.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                applyVdDensityDpi()
                true
            } else {
                false
            }
        }
        refreshVdDensityUi()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val focus = currentFocus
            if (focus is EditText) {
                val bounds = Rect()
                focus.getGlobalVisibleRect(bounds)
                if (!bounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    focus.clearFocus()
                    hideKeyboard(focus)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        refreshVdDensityUi()
    }

    private fun refreshVdDensityUi() {
        val configured = CoreApi.vdDensityDpi
        updateVdDensityInput(configured)
        updateVdDensityStatus(configured)
    }

    private fun updateVdDensityInput(configured: Int) {
        if (baseBinding.etVdDensityDpi.hasFocus()) return
        val text = if (configured > 0) configured.toString() else ""
        if (baseBinding.etVdDensityDpi.text?.toString() != text) {
            baseBinding.etVdDensityDpi.setText(text)
        }
    }

    private fun updateVdDensityStatus(configured: Int) {
        baseBinding.tvVdDensitySaved.text = if (configured > 0) {
            "已保存：$configured dpi"
        } else {
            "已保存：自动"
        }
        val huReported = CoreApi.reportedHostDensityDpi
        baseBinding.tvVdDensityHu.text = if (huReported > 0) {
            "车机协商：$huReported dpi"
        } else {
            "车机协商：暂无（连接 Android Auto 后更新）"
        }
        val effective = CoreApi.effectiveVdDensityDpi
        baseBinding.tvVdDensitySession.text = when {
            effective <= 0 -> "当前生效：未连接"
            configured > 0 && effective != configured ->
                "当前生效：$effective dpi（已保存 $configured，重连 AA 后对齐）"
            else -> "当前生效：$effective dpi"
        }
    }

    private fun applyVdDensityDpi() {
        if (CoreApi.buildTime == 0L) {
            toast("模块未激活，请先启用 LSPosed 并重启")
            return
        }
        val raw = baseBinding.etVdDensityDpi.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            persistVdDensityDpi(0)
            return
        }
        val parsed = raw.toIntOrNull()
        if (parsed == null) {
            toast("请输入有效数字")
            return
        }
        if (parsed <= 0) {
            persistVdDensityDpi(0)
            return
        }
        if (!DisplayDpiStore.isValidOverride(parsed)) {
            toast("DPI 须在 ${DisplayDpiStore.MIN_DPI}–${DisplayDpiStore.MAX_DPI} 之间")
            return
        }
        persistVdDensityDpi(parsed)
    }

    private fun resetVdDensityDpi() {
        if (CoreApi.buildTime == 0L) {
            toast("模块未激活，请先启用 LSPosed 并重启")
            return
        }
        persistVdDensityDpi(0)
    }

    private fun persistVdDensityDpi(dpi: Int) {
        CoreApi.setVdDensityDpi(dpi)
        val saved = CoreApi.vdDensityDpi
        if (dpi > 0 && saved != dpi) {
            toast("保存失败，请确认模块已激活并重试")
            refreshVdDensityUi()
            return
        }
        if (dpi == 0 && saved != 0) {
            toast("恢复自动失败，请重试")
            refreshVdDensityUi()
            return
        }
        baseBinding.etVdDensityDpi.clearFocus()
        updateVdDensityInput(saved)
        updateVdDensityStatus(saved)
        if (dpi > 0) {
            toast("已设为 $dpi dpi")
        } else {
            toast("已恢复自动 DPI")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard(focus: View? = currentFocus) {
        val target = focus ?: return
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    private fun setStatusCardColor(color: Int) {
        (baseBinding.mcvStatus.background.mutate() as? GradientDrawable)?.setColor(color)
            ?: baseBinding.mcvStatus.setBackgroundColor(color)
    }
}
