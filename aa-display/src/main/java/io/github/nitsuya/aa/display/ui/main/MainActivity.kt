package io.github.nitsuya.aa.display.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.Shell
import io.github.duzhaokun123.template.bases.BaseActivity
import io.github.nitsuya.aa.display.BuildConfig
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding
import io.github.nitsuya.aa.display.util.AADisplayConfig
import io.github.nitsuya.aa.display.util.GoogleMapsOnAaManager
import io.github.nitsuya.aa.display.util.SharedPreferencesAccess
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

    private data class DelayOption(
        val seconds: Int,
        val label: String
    )

    private val appConfig by lazy {
        SharedPreferencesAccess.openForHooks(this, AADisplayConfig.ConfigName)
    }

    private var delayOptions: List<DelayOption> = emptyList()
    private var delayDisplayToSeconds: Map<String, Int> = emptyMap()
    private var savedDelayDestroyTime: Int = 180
    private var savedAutoOpen: Boolean = false
    private var savedRestoreLastSplit: Boolean = false
    private var savedDisableGoogleMapsOnAa: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        ActivityMainBinding.inflate(LayoutInflater.from(this))
        super.onCreate(savedInstanceState)
        SharedPreferencesAccess.makeReadableForHooks(this, AADisplayConfig.ConfigName)
        addMenuProvider(this, this)
    }

    override fun initViews() {
        super.initViews()
        setupSettingControls()
    }

    @SuppressLint("SetTextI18n")
    override fun initData() {
        val buildTime = CoreApi.buildTime
        Log.d(TAG, "buildtime: $buildTime ${BuildConfig.BUILD_TIME}")
        when (buildTime) {
            0L -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_error_outline_24)
                baseBinding.tvActive.setText(R.string.not_activated)
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
                baseBinding.tvActive.setText(R.string.activated)
                baseBinding.tvVersion.text = CoreApi.versionName
            }

            else -> {
                baseBinding.ivIcon.setImageResource(R.drawable.ic_warning_amber_24)
                baseBinding.tvActive.setText(R.string.need_reboot)
                baseBinding.tvVersion.text =
                    "system: ${CoreApi.versionName}\nmodule: ${BuildConfig.VERSION_NAME}"
                baseBinding.mcvStatus.setCardBackgroundColor(
                    MaterialColors.harmonizeWithPrimary(this, getColor(R.color.color_warning))
                )
                baseBinding.mcvStatus.setOnClickListener {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.need_reboot)
                        .setPositiveButton(R.string.reboot) { _, _ ->
                            Shell.getShell().newJob().add("reboot").exec()
                        }
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
        baseBinding.rootPrivilege.text = if (Shell.getShell().isRoot) "YES" else "NO"
        refreshSettingControls()
    }

    override fun onResume() {
        super.onResume()
        refreshSettingControls()
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

    private fun setupSettingControls() {
        baseBinding.switchAutoOpen.setOnCheckedChangeListener { _, _ ->
            updateSaveButtonState()
        }

        baseBinding.switchRestoreLastSplit.setOnCheckedChangeListener { _, _ ->
            updateSaveButtonState()
        }

        baseBinding.switchDisableGoogleMapsOnAa.setOnCheckedChangeListener { _, _ ->
            updateSaveButtonState()
        }

        baseBinding.actvDelayDestroyTime.apply {
            inputType = InputType.TYPE_NULL
            keyListener = null
            threshold = 0
            isCursorVisible = false
            isLongClickable = false
            showSoftInputOnFocus = false
            setTextIsSelectable(false)
            setOnClickListener {
                showAllDelayOptions()
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showAllDelayOptions()
                }
                if (!hasFocus) {
                    validateDelaySelection(showError = true)
                }
            }
            setOnItemClickListener { _, _, _, _ ->
                validateDelaySelection(showError = false)
                updateSaveButtonState()
            }
        }

        baseBinding.btnSave.setOnClickListener {
            persistSettings()
        }
    }

    private fun refreshSettingControls() {
        savedAutoOpen = AADisplayConfig.AutoOpen.get(appConfig)
        savedRestoreLastSplit = AADisplayConfig.RestoreLastSplit.get(appConfig)
        savedDisableGoogleMapsOnAa = AADisplayConfig.DisableGoogleMapsOnAa.get(appConfig)
        savedDelayDestroyTime = AADisplayConfig.DelayDestroyTime.get(appConfig)
        baseBinding.switchAutoOpen.isChecked = savedAutoOpen
        baseBinding.switchRestoreLastSplit.isChecked = savedRestoreLastSplit
        baseBinding.switchDisableGoogleMapsOnAa.isChecked = savedDisableGoogleMapsOnAa

        bindDelayDropdown()

        val selectedDelay = delayOptions.firstOrNull { it.seconds == savedDelayDestroyTime }
            ?: delayOptions.firstOrNull()
        if (selectedDelay != null) {
            baseBinding.actvDelayDestroyTime.setText(selectedDelay.label, false)
        } else {
            baseBinding.actvDelayDestroyTime.setText("", false)
        }

        validateDelaySelection(showError = false)
        updateSaveButtonState()
    }

    private fun bindDelayDropdown() {
        delayOptions = listOf(
            DelayOption(60, getString(R.string.delay_60_seconds)),
            DelayOption(120, getString(R.string.delay_120_seconds)),
            DelayOption(180, getString(R.string.delay_180_seconds))
        )
        delayDisplayToSeconds = delayOptions.associate { it.label to it.seconds }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            delayOptions.map { it.label }
        )
        baseBinding.actvDelayDestroyTime.setAdapter(adapter)
    }

    private fun showAllDelayOptions() {
        @Suppress("UNCHECKED_CAST")
        val adapter = baseBinding.actvDelayDestroyTime.adapter as? ArrayAdapter<String> ?: return
        adapter.filter.filter(null)
        baseBinding.actvDelayDestroyTime.showDropDown()
    }

    private fun resolveSelectedDelaySeconds(): Int? {
        val raw = baseBinding.actvDelayDestroyTime.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return null
        delayDisplayToSeconds[raw]?.let { return it }
        return delayOptions.firstOrNull { it.label == raw }?.seconds
    }

    private fun validateDelaySelection(showError: Boolean): Boolean {
        val value = resolveSelectedDelaySeconds()
        val valid = value != null && delayOptions.any { it.seconds == value }
        if (!valid && showError) {
            baseBinding.tilDelayDestroyTime.error = getString(R.string.delay_destroy_time_invalid)
        } else if (valid) {
            baseBinding.tilDelayDestroyTime.error = null
        }
        return valid
    }

    private fun persistSettings() {
        val delayValid = validateDelaySelection(showError = true)
        if (!delayValid) {
            updateSaveButtonState()
            return
        }

        val delay = resolveSelectedDelaySeconds() ?: return
        val disableGoogleMapsOnAa = baseBinding.switchDisableGoogleMapsOnAa.isChecked
        if (disableGoogleMapsOnAa != savedDisableGoogleMapsOnAa) {
            val applied = GoogleMapsOnAaManager.apply(disableGoogleMapsOnAa)
            if (!applied) {
                Toast.makeText(this, getString(R.string.disable_google_maps_on_aa_apply_failed), Toast.LENGTH_SHORT).show()
                baseBinding.switchDisableGoogleMapsOnAa.isChecked = savedDisableGoogleMapsOnAa
                updateSaveButtonState()
                return
            }
        }

        val settingsSaved = appConfig.edit()
            .putBoolean(AADisplayConfig.AutoOpen.key, baseBinding.switchAutoOpen.isChecked)
            .putBoolean(AADisplayConfig.RestoreLastSplit.key, baseBinding.switchRestoreLastSplit.isChecked)
            .putBoolean(AADisplayConfig.DisableGoogleMapsOnAa.key, disableGoogleMapsOnAa)
            .putString(AADisplayConfig.DelayDestroyTime.key, delay.toString())
            .commit()

        if (!settingsSaved) {
            Toast.makeText(this, getString(R.string.settings_saved_pref_access_failed), Toast.LENGTH_LONG).show()
            updateSaveButtonState()
            return
        }

        // Always keep local "saved*" in sync when disk write succeeds. Hook readability is separate.
        savedAutoOpen = baseBinding.switchAutoOpen.isChecked
        savedRestoreLastSplit = baseBinding.switchRestoreLastSplit.isChecked
        savedDisableGoogleMapsOnAa = disableGoogleMapsOnAa
        savedDelayDestroyTime = delay

        val readableForHooks = SharedPreferencesAccess.makeReadableForHooks(this, AADisplayConfig.ConfigName)
        if (!readableForHooks) {
            Toast.makeText(this, getString(R.string.settings_saved_pref_access_failed), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, getString(R.string.settings_saved_successfully), Toast.LENGTH_SHORT).show()
        }
        updateSaveButtonState()
    }

    private fun updateSaveButtonState() {
        val hasChanges = hasPendingChanges()
        val enabled = hasChanges && validateDelaySelection(showError = false)
        baseBinding.btnSave.isEnabled = enabled
        baseBinding.btnSave.alpha = if (enabled) 1f else 0.6f
    }

    private fun hasPendingChanges(): Boolean {
        val currentAutoOpen = baseBinding.switchAutoOpen.isChecked
        val currentRestoreLastSplit = baseBinding.switchRestoreLastSplit.isChecked
        val currentDisableGoogleMapsOnAa = baseBinding.switchDisableGoogleMapsOnAa.isChecked
        val currentDelay = resolveSelectedDelaySeconds()
        return currentAutoOpen != savedAutoOpen ||
            currentRestoreLastSplit != savedRestoreLastSplit ||
            currentDisableGoogleMapsOnAa != savedDisableGoogleMapsOnAa ||
            currentDelay != savedDelayDestroyTime
    }
}
