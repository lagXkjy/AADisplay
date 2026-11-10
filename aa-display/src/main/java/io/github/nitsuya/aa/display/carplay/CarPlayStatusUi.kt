package io.github.nitsuya.aa.display.carplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Toast
import io.github.nitsuya.aa.display.CoreApi
import io.github.nitsuya.aa.display.R
import io.github.nitsuya.aa.display.aap.AapProjectionService
import io.github.nitsuya.aa.display.databinding.ActivityMainBinding
import io.github.nitsuya.aa.display.egress.ChannelOrchestrator
import io.github.nitsuya.aa.display.egress.EgressChannel
import io.github.nitsuya.aa.display.egress.ProjectionSessionGate

/**
 * Status-page egress controls (AAP / CarPlay only — Google gearhead projection retired).
 * Keeps [io.github.nitsuya.aa.display.ui.main.MainActivity] free of session logic.
 */
object CarPlayStatusUi {
    private var channelChangeFromUser = true
    private var pendingChannelAfterPerms: Int? = null

    fun bind(activity: Activity, binding: ActivityMainBinding) {
        binding.rbChannelAap.visibility = View.VISIBLE
        binding.rgCarplayChannel.visibility = View.VISIBLE
        // Apply persisted selection BEFORE the listener — otherwise a layout default
        // radio can fire applyChannel and stop AAP mid USB attach.
        channelChangeFromUser = false
        refresh(activity, binding)
        binding.rgCarplayChannel.setOnCheckedChangeListener { _, checkedId ->
            if (!channelChangeFromUser) {
                channelChangeFromUser = true
                return@setOnCheckedChangeListener
            }
            val channel = when (checkedId) {
                R.id.rb_channel_cp -> EgressChannel.CHANNEL_CARPLAY
                R.id.rb_channel_aap -> EgressChannel.CHANNEL_AAP
                else -> EgressChannel.defaultChannel(activity)
            }
            applyChannel(activity, binding, channel)
        }
        binding.btnCarplayHost.setOnClickListener {
            applyChannel(activity, binding, EgressChannel.CHANNEL_CARPLAY)
            CarPlayProjectionService.requestDiscoverable(activity)
        }
        binding.btnBtDiscoverable.setOnClickListener {
            CarPlayProjectionService.requestDiscoverable(activity)
            toast(activity, "请允许手机被车机发现")
        }
        binding.btnAapReconnect.setOnClickListener {
            applyChannel(activity, binding, EgressChannel.CHANNEL_AAP)
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
        val channel = pendingChannelAfterPerms ?: EgressChannel.CHANNEL_CARPLAY
        pendingChannelAfterPerms = null
        finishApplyChannel(activity, binding, channel)
    }

    private fun applyChannel(
        activity: Activity,
        binding: ActivityMainBinding,
        channel: Int,
    ) {
        // Persist + swap cards immediately so the radio and panels stay in sync,
        // even when a permission dialog delays arming the selected egress.
        EgressChannel.set(activity, channel)
        refresh(activity, binding)
        if ((channel == EgressChannel.CHANNEL_CARPLAY || channel == EgressChannel.CHANNEL_AAP) &&
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
        val target = when {
            channel == EgressChannel.CHANNEL_AAP -> EgressChannel.CHANNEL_AAP
            channel == EgressChannel.CHANNEL_CARPLAY -> channel
            else -> EgressChannel.defaultChannel(activity)
        }
        ChannelOrchestrator.switchTo(activity, target) {
            when {
                EgressChannel.useAapEgress(activity) -> toast(activity, "已切到 Android Auto")
                EgressChannel.useCarPlayEgress(activity) -> {
                    CarPlayProjectionService.requestDiscoverable(activity)
                    toast(activity, "已切到无线 CarPlay，请到车机上连接")
                }
            }
            refresh(activity, binding)
        }
    }

    fun handleLaunchIntent(activity: Activity, binding: ActivityMainBinding, intent: Intent?) {
        when (intent?.action) {
            CarPlayConsts.ACTION_CONNECT -> {
                applyChannel(activity, binding, EgressChannel.CHANNEL_CARPLAY)
            }
            CarPlayConsts.ACTION_STOP -> {
                CarPlayProjectionService.stop(activity)
                refresh(activity, binding)
                toast(activity, "CarPlay 已停止")
            }
        }
    }

    fun ensureArmedIfCarPlay(activity: Activity) {
        if (!EgressChannel.useCarPlayEgress(activity)) return
        if (EgressChannel.useWirelessCarPlay(activity) &&
            !CarPlayRuntimePerms.ensureWireless(activity)
        ) {
            pendingChannelAfterPerms = EgressChannel.CHANNEL_CARPLAY
            return
        }
        ChannelOrchestrator.ensureArmedCurrent(activity)
    }

    fun ensureArmedIfAap(activity: Activity) {
        if (!EgressChannel.useAapEgress(activity)) return
        if (!CarPlayRuntimePerms.ensureWireless(activity)) {
            pendingChannelAfterPerms = EgressChannel.CHANNEL_AAP
            return
        }
        ChannelOrchestrator.ensureArmedCurrent(activity)
    }

    @SuppressLint("SetTextI18n")
    fun refresh(activity: Activity, binding: ActivityMainBinding) {
        val live = try {
            CoreApi.isCarPlaySessionLive
        } catch (_: Throwable) {
            ProjectionSessionGate.isCarPlayLive()
        }
        val channel = EgressChannel.get(activity)
        val carPlay = EgressChannel.useCarPlayEgress(activity)
        val aap = EgressChannel.useAapEgress(activity)
        val armed = CarPlayProjectionService.isSessionArmed()
        val aapArmed = AapProjectionService.isSessionArmed()
        val hostUp = CarPlayHostRuntime.isRunning()
        val aaInstalled = EgressChannel.isAndroidAutoInstalled(activity)

        val (cpState, cpDetail, cpColor) = when {
            !carPlay -> Triple("", "", activity.getColor(R.color.color_text_primary))
            live && hostUp -> Triple(
                "已投到车机",
                "车机断开后手机仍会继续等待下次连接",
                activity.getColor(R.color.color_primary),
            )
            live -> Triple(
                "正在准备画面…",
                "稍等几秒；若一直停在这里，点下方「重新连接」",
                activity.getColor(R.color.color_outline),
            )
            armed -> Triple(
                "等待车机连接",
                "手机已就绪，请到车机上连接 CarPlay",
                activity.getColor(R.color.color_primary),
            )
            else -> Triple(
                "尚未启动",
                if (aaInstalled) {
                    "点下方「重新连接」，或重新勾选上方「无线 CarPlay」"
                } else {
                    "点下方「重新连接」，再到车机上连 CarPlay"
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
                live && aapArmed -> "已投到车机"
                aapArmed -> "等待车机连接"
                else -> "尚未启动"
            }
            binding.tvAapState.setTextColor(
                if (live || aapArmed) activity.getColor(R.color.color_primary)
                else activity.getColor(R.color.color_error),
            )
            binding.tvAapDetail.text = st
        }

        val wantId = when (channel) {
            EgressChannel.CHANNEL_CARPLAY -> R.id.rb_channel_cp
            else -> R.id.rb_channel_aap
        }
        if (binding.rgCarplayChannel.checkedRadioButtonId != wantId) {
            channelChangeFromUser = false
            binding.rgCarplayChannel.check(wantId)
        }

        binding.panelAa.visibility = View.VISIBLE
        // 连车准备 / Android Auto 卡只跟 AAP；无线 CarPlay 卡只跟 CarPlay。
        binding.panelAapCert.visibility = if (aap) View.VISIBLE else View.GONE
        binding.panelAap.visibility = if (aap) View.VISIBLE else View.GONE
        binding.panelCarplay.visibility = if (carPlay) View.VISIBLE else View.GONE
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
