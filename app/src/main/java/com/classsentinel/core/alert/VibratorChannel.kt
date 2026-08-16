package com.classsentinel.core.alert

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.classsentinel.core.detect.ClassEvent

/**
 * 震动通道：短促两段震动，即使手机静音也能感知。
 * 兼容 VibratorManager（API 31+）与 Vibrator（API 26-30）。
 */
class VibratorChannel : AlertChannel {

    override val key = "vibrate"

    override fun fire(event: ClassEvent, context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        // 0ms 启动 → 震 300 → 停 150 → 震 300 → 停 150 → 长震 600（不重复）
        val pattern = longArrayOf(0, 300, 150, 300, 150, 600)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
