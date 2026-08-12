package io.github.nitsuya.aa.display.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.duzhaokun123.template.bases.BaseActivity
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding
import io.github.nitsuya.template.bases.getAttr

class MainActivity :
    BaseActivity<ActivityMainBinding>(
        ActivityMainBinding::class.java,
        Config.NO_BACK,
        Config.LAYOUT_MATCH_HORI
    ),
    MenuProvider {
    companion object {
        const val TAG = "AADisplay_MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ActivityMainBinding.inflate(LayoutInflater.from(this))
        super.onCreate(savedInstanceState)
        addMenuProvider(this, this)
    }

    @SuppressLint("SetTextI18n")
    override fun initData() {
        val buildTime = CoreApi.buildTime
        when (buildTime) {
            0L -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_error_outline_24)
                baseBinding.tvActive.text = "未激活"
                baseBinding.tvVersion.text = ""
                val colorError = android.R.attr.colorError
                val colorOnError = theme.getAttr(com.google.android.material.R.attr.colorOnError).data
                baseBinding.mcvStatus.setCardBackgroundColor(colorError)
                baseBinding.mcvStatus.outlineAmbientShadowColor = colorError
                baseBinding.mcvStatus.outlineSpotShadowColor = colorError
                baseBinding.tvActive.setTextColor(colorOnError)
                baseBinding.tvVersion.setTextColor(colorOnError)
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
                baseBinding.mcvStatus.setCardBackgroundColor(
                    MaterialColors.harmonizeWithPrimary(this, getColor(R.color.color_warning))
                )
                baseBinding.mcvStatus.setOnClickListener {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("需要重启")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }

        if (Build.VERSION.PREVIEW_SDK_INT != 0) {
            baseBinding.systemVersion.text =
                "${Build.VERSION.CODENAME} Preview (API ${Build.VERSION.SDK_INT})"
        } else {
            baseBinding.systemVersion.text = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_main, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.github -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = "https://github.com/Stashboy/AADisplay".toUri()
                })
                true
            }

            else -> false
        }
    }
}
