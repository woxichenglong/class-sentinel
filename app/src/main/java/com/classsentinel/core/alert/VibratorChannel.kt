package com.classsentinel.core.alert

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.classsentinel.core.config.AppConfig
import com.classsentinel.core.detect.ClassEvent
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * 震动通道：短促两段震动，即使手机静音也能感知。
 * 兼容 VibratorManager（API 31+）与 Vibrator（API 26-30）。
 */
class VibratorChannel(
    private val modeFlow: StateFlow<String> = AppConfig.vibrationMode,
) : AlertChannel {

    override val key = "vibrate"

    override fun fire(event: ClassEvent, context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        val pattern = vibrationPattern(modeFlow.value)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}

/** 纯波形映射，供提醒通道和 JVM 单测共享；未知偏好回退到标准模式。 */
internal fun vibrationPattern(mode: String): LongArray = when (mode.trim().lowercase(Locale.ROOT)) {
    "gentle" -> longArrayOf(0L, 120L, 100L, 120L)
    "strong" -> longArrayOf(0L, 500L, 100L, 500L, 100L, 900L)
    else -> longArrayOf(0L, 300L, 150L, 300L, 150L, 600L)
}
