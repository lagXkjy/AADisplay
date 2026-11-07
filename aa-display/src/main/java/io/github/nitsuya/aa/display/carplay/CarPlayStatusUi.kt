package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Toast
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.aap.AapProjectionService
import io.github.nitsuya.aa.display.aap.cert.PhoneCertStore
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding
import io.github.nitsuya.aa.display.egress.ProjectionSessionGate

/**
 * Status-page egress controls (AA gearhead / native AAP / CarPlay).
 * Keeps [io.github.nitsuya.aa.display.ui.main.MainActivity] free of session logic.
 */
object CarPlayStatusUi {
    private var channelChangeFromUser = true
    private var pendingChannelAfterPerms: Int? = null

    fun bind(activity: Activity, binding: ActivityMainBinding) {
        applyAaAvailability(activity, binding)
        // Apply persisted selection BEFORE the listener — otherwise the layout's
        // default radio can fire applyChannel(AA) and stop AAP mid USB attach.
        channelChangeFromUser = false
        refresh(activity, binding)
        binding.rgCarplayChannel.setOnCheckedChangeListener { _, checkedId ->
            if (!channelChangeFromUser) {
                channelChangeFromUser = true
                return@setOnCheckedChangeListener
            }
            val channel = when (checkedId) {
                R.id.rb_channel_cp -> CarPlayChannel.CHANNEL_CARPLAY
                R.id.rb_channel_aap -> CarPlayChannel.CHANNEL_AAP
                else -> CarPlayChannel.CHANNEL_AA
            }
            applyChannel(activity, binding, channel)
        }
        binding.btnCarplayHost.setOnClickListener {
            applyChannel(activity, binding, CarPlayChannel.CHANNEL_CARPLAY)
            CarPlayProjectionService.requestDiscoverable(activity)
        }
        binding.btnBtDiscoverable.setOnClickListener {
            CarPlayProjectionService.requestDiscoverable(activity)
            toast(activity, "请允许蓝牙可被发现（300 秒）")
        }
        binding.btnAapReconnect.setOnClickListener {
            applyChannel(activity, binding, CarPlayChannel.CHANNEL_AAP)
        }
        channelChangeFromUser = true
        ensureArmedIfCarPlay(activity)
        ensureArmedIfAap(activity)
    }

    fun onPermissionsResult(
        activity: Activity,
        binding: ActivityMainBinding,
        requestCode: Int,
        grantResults: IntArray,
    ) {
        if (!CarPlayRuntimePerms.onRequestResult(requestCode, grantResults)) {
            if (requestCode == CarPlayRuntimePerms.REQUEST_CODE) {
                pendingChannelAfterPerms = null
                toast(activity, "需要蓝牙权限才能连接")
                refresh(activity, binding)
            }
            return
        }
        val channel = pendingChannelAfterPerms ?: CarPlayChannel.CHANNEL_CARPLAY
        pendingChannelAfterPerms = null
        finishApplyChannel(activity, binding, channel)
    }

    private fun applyChannel(
        activity: Activity,
        binding: ActivityMainBinding,
        channel: Int,
    ) {
        if ((channel == CarPlayChannel.CHANNEL_CARPLAY || channel == CarPlayChannel.CHANNEL_AAP) &&
            !CarPlayRuntimePerms.ensureWireless(activity)
        ) {
            pendingChannelAfterPerms = channel
            toast(activity, "请允许蓝牙 / 附近设备权限")
            return
        }
        finishApplyChannel(activity, binding, channel)
    }

    private fun finishApplyChannel(
        activity: Activity,
        binding: ActivityMainBinding,
        channel: Int,
    ) {
        CarPlayChannel.set(activity, channel)
        when {
            channel == CarPlayChannel.CHANNEL_AAP -> {
                CarPlayProjectionService.stop(activity)
                CarPlayHostActivity.current?.finish()
                // Auto-extract cert from installed AA before arming.
                PhoneCertStore.ensureExtracted(activity, force = false)
                AapProjectionService.start(activity)
                io.github.nitsuya.aa.display.aap.usb.AapUsbProbe.tryAttachExisting(activity)
                toast(activity, "已切到原生 AAP（不依赖 gearhead 钩子）")
            }
            CarPlayChannel.useCarPlayEgress(activity) -> {
                AapProjectionService.stop(activity)
                CarPlayProjectionService.start(activity, launchHostTrampoline = false)
                CarPlayProjectionService.requestDiscoverable(activity)
                toast(activity, "已切到 CarPlay，请允许蓝牙可发现后到车机上连接")
            }
            else -> {
                AapProjectionService.stop(activity)
                CarPlayProjectionService.stop(activity)
                CarPlayHostActivity.current?.finish()
                toast(activity, "已切回 Android Auto（gearhead）")
            }
        }
        refresh(activity, binding)
    }

    fun handleLaunchIntent(activity: Activity, binding: ActivityMainBinding, intent: Intent?) {
        when (intent?.action) {
            CarPlayConsts.ACTION_CONNECT -> {
                applyChannel(activity, binding, CarPlayChannel.CHANNEL_CARPLAY)
            }
            CarPlayConsts.ACTION_STOP -> {
                CarPlayProjectionService.stop(activity)
                refresh(activity, binding)
                toast(activity, "CarPlay 已停止")
            }
        }
    }

    fun ensureArmedIfCarPlay(activity: Activity) {
        if (!CarPlayChannel.useCarPlayEgress(activity)) return
        if (CarPlayChannel.useWirelessCarPlay(activity) &&
            !CarPlayRuntimePerms.ensureWireless(activity)
        ) {
            pendingChannelAfterPerms = CarPlayChannel.CHANNEL_CARPLAY
            return
        }
        try {
            CarPlayProjectionService.start(activity, launchHostTrampoline = false)
        } catch (e: Throwable) {
            android.util.Log.e(CarPlayConsts.TAG, "ensureArmedIfCarPlay failed", e)
        }
    }

    fun ensureArmedIfAap(activity: Activity) {
        if (!CarPlayChannel.useAapEgress(activity)) return
        if (!CarPlayRuntimePerms.ensureWireless(activity)) {
            pendingChannelAfterPerms = CarPlayChannel.CHANNEL_AAP
            return
        }
        try {
            PhoneCertStore.ensureExtracted(activity, force = false)
            AapProjectionService.start(activity)
        } catch (e: Throwable) {
            android.util.Log.e(CarPlayConsts.TAG, "ensureArmedIfAap failed", e)
        }
    }

    /**
     * Gearhead radio stays available when installed (legacy path).
     * Native AAP is always offered (cert auto-extract needs AA APK when talking to
     * Google-library HUs, but the channel itself does not require gearhead hooks).
     */
    private fun applyAaAvailability(activity: Activity, binding: ActivityMainBinding) {
        val aaInstalled = CarPlayChannel.isAndroidAutoInstalled(activity)
        binding.rbChannelAa.visibility = if (aaInstalled) View.VISIBLE else View.GONE
        binding.rbChannelAap.visibility = View.VISIBLE
        binding.rgCarplayChannel.visibility = View.VISIBLE
    }

    @SuppressLint("SetTextI18n")
    fun refresh(activity: Activity, binding: ActivityMainBinding) {
        applyAaAvailability(activity, binding)
        val live = try {
            CoreApi.isCarPlaySessionLive
        } catch (_: Throwable) {
            ProjectionSessionGate.isCarPlayLive()
        }
        val channel = CarPlayChannel.get(activity)
        val carPlay = CarPlayChannel.useCarPlayEgress(activity)
        val aap = CarPlayChannel.useAapEgress(activity)
        val armed = CarPlayProjectionService.isSessionArmed()
        val aapArmed = AapProjectionService.isSessionArmed()
        val hostUp = CarPlayHostRuntime.isRunning()
        val aaInstalled = CarPlayChannel.isAndroidAutoInstalled(activity)

        val (cpState, cpDetail, cpColor) = when {
            !carPlay -> Triple("", "", activity.getColor(R.color.color_text_primary))
            live && hostUp -> Triple(
                "投屏中 / 已出画",
                "Host 与编码器已启动；车机断开后仍保持监听",
                activity.getColor(R.color.color_primary),
            )
            live -> Triple(
                "正在准备画面…",
                "稍等几秒；若一直停在这里，点下方「重新连接」",
                activity.getColor(R.color.color_outline),
            )
            armed -> Triple(
                "等待车机连接",
                "蓝牙已监听，车机连上后再启动画面编码（省电）",
                activity.getColor(R.color.color_primary),
            )
            else -> Triple(
                "尚未启动",
                if (aaInstalled) {
                    "点下方「重新连接」，或重新勾选上方「CarPlay 无线」"
                } else {
                    "点下方「重新连接」开始监听；车机用无线 CarPlay 连接"
                },
                activity.getColor(R.color.color_error),
            )
        }
        binding.tvCarplayState.text = cpState
        binding.tvCarplayState.setTextColor(cpColor)
        binding.tvCarplayDetail.text = cpDetail
        binding.btnCarplayHost.text = when {
            !carPlay -> "重新连接"
            armed || live -> "重新连接"
            else -> "开始连接"
        }

        if (aap) {
            val st = AapProjectionService.lastStatus()
            binding.tvAapState.text = when {
                live && aapArmed -> "投屏中 / 已出画"
                aapArmed -> "已监听"
                else -> "尚未启动"
            }
            binding.tvAapState.setTextColor(
                if (live || aapArmed) activity.getColor(R.color.color_primary)
                else activity.getColor(R.color.color_error),
            )
            binding.tvAapDetail.text = st
        }

        val wantId = when (channel) {
            CarPlayChannel.CHANNEL_CARPLAY -> R.id.rb_channel_cp
            CarPlayChannel.CHANNEL_AAP -> R.id.rb_channel_aap
            else -> if (aaInstalled) R.id.rb_channel_aa else R.id.rb_channel_aap
        }
        if (binding.rgCarplayChannel.checkedRadioButtonId != wantId) {
            channelChangeFromUser = false
            binding.rgCarplayChannel.check(wantId)
        }

        binding.panelAa.visibility = View.VISIBLE
        binding.panelCarplay.visibility = if (carPlay) View.VISIBLE else View.GONE
        binding.panelAap.visibility = if (aap) View.VISIBLE else View.GONE
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
