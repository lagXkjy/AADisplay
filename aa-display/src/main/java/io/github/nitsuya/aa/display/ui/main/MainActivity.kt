package io.github.nitsuya.aa.display.ui.main

import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import android.content.Intent
import io.github.nitsuya.aa.display.ui.base.BaseActivity
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.aap.AaCertSettingsUi
import io.github.nitsuya.aa.display.egress.EgressChannel
import io.github.nitsuya.aa.display.carplay.CarPlayConsts
import io.github.nitsuya.aa.display.carplay.CarPlayHostRuntime
import io.github.nitsuya.aa.display.carplay.CarPlayStatusUi
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding
import io.github.nitsuya.aa.display.util.DisplayDpiStore

class MainActivity : BaseActivity<ActivityMainBinding>() {

    override fun bind(inflater: LayoutInflater): ActivityMainBinding =
        ActivityMainBinding.inflate(inflater)

    override fun initData() {
        val buildTime = CoreApi.buildTime
        when (buildTime) {
            0L -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_error_outline_24)
                baseBinding.tvActive.text = "未运行"
                baseBinding.tvVersion.text = "本包 ${BuildConfig.VERSION_NAME}"
                baseBinding.tvStatusHint.visibility = View.VISIBLE
                baseBinding.tvStatusHint.text =
                    "手机里没检测到模块：请在 LSPosed 里打开 AADisplay 开关，并在「作用域」里勾选「系统框架」，然后重启手机"
                setStatusCardColor(getColor(R.color.color_error))
                val onError = getColor(R.color.color_on_error)
                baseBinding.tvActive.setTextColor(onError)
                baseBinding.tvVersion.setTextColor(onError)
                baseBinding.systemVersion.setTextColor(onError)
                baseBinding.tvStatusHint.setTextColor(onError)
            }

            BuildConfig.BUILD_TIME -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_round_check_circle_24)
                baseBinding.tvActive.text = "运行中"
                baseBinding.tvStatusHint.visibility = View.GONE
                baseBinding.tvVersion.text = BuildConfig.VERSION_NAME
            }

            else -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_warning_amber_24)
                baseBinding.tvActive.text = "需要重启手机"
                val framework = CoreApi.versionName ?: "—"
                baseBinding.tvVersion.text =
                    "框架 $framework\n本包 ${BuildConfig.VERSION_NAME}"
                baseBinding.tvStatusHint.visibility = View.VISIBLE
                baseBinding.tvStatusHint.text =
                    "系统里还在跑旧版模块，重启手机后生效"
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
        // CarPlay / AAP channel / Host / adb CONNECT|STOP — owned by carplay / aap packages.
        CarPlayStatusUi.bind(this, baseBinding)
        AaCertSettingsUi.bind(this, baseBinding)
        CarPlayStatusUi.handleLaunchIntent(this, baseBinding, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        CarPlayStatusUi.handleLaunchIntent(this, baseBinding, intent)
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
        CarPlayStatusUi.refresh(this, baseBinding)
        AaCertSettingsUi.refresh(this, baseBinding)
        refreshVdDensityUi()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        CarPlayStatusUi.onPermissionsResult(this, baseBinding, requestCode, grantResults)
    }

    private fun refreshVdDensityUi() {
        val configured = CoreApi.vdDensityDpi
        val carPlay = EgressChannel.useCarPlayEgress(this)
        updateVdDensityLabels(carPlay)
        updateVdDensityInput(configured, carPlay)
        updateVdDensityStatus(configured, carPlay)
    }

    private fun updateVdDensityLabels(carPlay: Boolean) {
        val aap = EgressChannel.useAapEgress(this)
        when {
            carPlay || aap -> {
                baseBinding.tvVdDensityTitle.text = "画面清晰度"
                baseBinding.tvVdDensityHint.text =
                    "一般不用改。车机画布固定像素：数字越大图标和字越大；留空用默认 ${CarPlayConsts.ENCODE_UI_DENSITY_DPI}。范围 120–640"
                baseBinding.etVdDensityDpi.hint = "留空 = 默认 ${CarPlayConsts.ENCODE_UI_DENSITY_DPI}"
                baseBinding.btnResetVdDensityDpi.text = "恢复默认"
            }
            else -> {
                baseBinding.tvVdDensityTitle.text = "画面清晰度"
                baseBinding.tvVdDensityHint.text = "一般不用改。留空用自动；范围 120–640"
                baseBinding.etVdDensityDpi.hint = "留空 = 自动"
                baseBinding.btnResetVdDensityDpi.text = "恢复自动"
            }
        }
    }

    private fun updateVdDensityInput(configured: Int, carPlay: Boolean = false) {
        if (baseBinding.etVdDensityDpi.hasFocus()) return
        val text = if (configured > 0) configured.toString() else ""
        if (baseBinding.etVdDensityDpi.text?.toString() != text) {
            baseBinding.etVdDensityDpi.setText(text)
        }
    }

    private fun updateVdDensityStatus(configured: Int, carPlay: Boolean) {
        baseBinding.tvVdDensitySaved.text = when {
            configured > 0 -> "已保存：$configured"
            carPlay || EgressChannel.useAapEgress(this) ->
                "已保存：默认 ${CarPlayConsts.ENCODE_UI_DENSITY_DPI}"
            else -> "已保存：自动"
        }
        baseBinding.tvVdDensityHu.visibility = View.GONE
        val effective = CoreApi.effectiveVdDensityDpi
        val defaultHint = if (carPlay || EgressChannel.useAapEgress(this)) {
            CarPlayConsts.ENCODE_UI_DENSITY_DPI
        } else {
            0
        }
        baseBinding.tvVdDensitySession.text = when {
            effective <= 0 && !CarPlayHostRuntime.isRunning() &&
                !EgressChannel.useAapEgress(this) -> "当前：未连接"
            configured > 0 && effective > 0 && effective != configured ->
                "当前：$effective（已保存 $configured，重连后生效）"
            effective > 0 -> "当前：$effective"
            carPlay || EgressChannel.useAapEgress(this) ->
                "当前：默认 $defaultHint（未连接时）"
            else -> "当前：未连接"
        }
    }

    private fun applyVdDensityDpi() {
        if (CoreApi.buildTime == 0L) {
            toast("模块未运行：请在 LSPosed 打开 AADisplay、作用域勾「系统框架」，再重启手机")
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
            toast("请填 ${DisplayDpiStore.MIN_DPI}–${DisplayDpiStore.MAX_DPI} 之间的数字")
            return
        }
        persistVdDensityDpi(parsed)
    }

    private fun resetVdDensityDpi() {
        if (CoreApi.buildTime == 0L) {
            toast("模块未运行：请在 LSPosed 打开 AADisplay、作用域勾「系统框架」，再重启手机")
            return
        }
        persistVdDensityDpi(0)
    }

    private fun persistVdDensityDpi(dpi: Int) {
        CoreApi.setVdDensityDpi(dpi)
        val saved = CoreApi.vdDensityDpi
        val carPlay = EgressChannel.useCarPlayEgress(this)
        if (dpi > 0 && saved != dpi) {
            toast("保存失败，请确认 LSPosed 里模块已勾选并重启手机后重试")
            refreshVdDensityUi()
            return
        }
        if (dpi == 0 && saved != 0) {
            toast("恢复失败，请重试")
            refreshVdDensityUi()
            return
        }
        // CarPlay Host keeps GL divider + VD dpi in lockstep; push live when Host is up.
        if (carPlay) {
            CarPlayHostRuntime.applyConfiguredDpi()
        }
        baseBinding.etVdDensityDpi.clearFocus()
        updateVdDensityInput(saved, carPlay)
        updateVdDensityStatus(saved, carPlay)
        if (dpi > 0) {
            toast("已设为 $dpi")
        } else {
            toast(
                if (carPlay || EgressChannel.useAapEgress(this)) {
                    "已恢复默认 ${CarPlayConsts.ENCODE_UI_DENSITY_DPI}"
                } else {
                    "已恢复自动"
                },
            )
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
