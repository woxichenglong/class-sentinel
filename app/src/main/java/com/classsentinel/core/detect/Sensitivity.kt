package com.classsentinel.core.detect

/**
 * 灵敏度配置：三档预设 + 抑制窗口。
 * 由 SettingsRepository 持久化，运行时通过 StateFlow 热更新。
 */
data class Sensitivity(
    val nameScoreMin: Double,     // 点名相似度阈值
    val contextRequired: Boolean, // 是否要求上下文确认词
    val questionWordLevel: Int,   // 触发词表等级 1少/2中/3多
    val vadDb: Int,               // VAD 静音阈值 dB
    val rollcallSuppressMs: Long = 60_000,
    val questionSuppressMs: Long = 120_000,
) {
    companion object {
        val STRICT = Sensitivity(0.92, true, 3, -30)
        val STANDARD = Sensitivity(0.80, true, 2, -35)
        val LOOSE = Sensitivity(0.68, false, 1, -40)

        fun preset(name: String): Sensitivity = when (name) {
            "strict" -> STRICT
            "loose" -> LOOSE
            else -> STANDARD
        }
    }
}
