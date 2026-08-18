package io.github.nitsuya.aa.display.ui.main

import android.annotation.SuppressLint
import android.graphics.drawable.GradientDrawable
import android.os.Build
import io.github.duzhaokun123.template.bases.BaseActivity
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding

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
    }

    private fun setStatusCardColor(color: Int) {
        (baseBinding.mcvStatus.background.mutate() as? GradientDrawable)?.setColor(color)
            ?: baseBinding.mcvStatus.setBackgroundColor(color)
    }
}
