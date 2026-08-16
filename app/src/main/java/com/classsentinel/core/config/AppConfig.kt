package com.classsentinel.core.config

import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.detect.Sensitivity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 极简运行期配置（Phase 6 将替换为 DataStore 持久化）。
 * 可变字段先占位，由 UI / 服务在启动时写入；StateFlow 字段支持运行时热更新。
 */
object AppConfig {

    // ---- ASR 密钥 ----
    var siliconApiKey: String = ""
    var xunfeiAppId: String = ""
    var xunfeiApiKey: String = ""
    var xunfeiApiSecret: String = ""

    // ---- 点名名单与灵敏度（热更新）----
    val names = MutableStateFlow<List<NameEntry>>(emptyList())
    val sensitivity = MutableStateFlow(Sensitivity.STANDARD)

    // ---- 启用的提醒通道 ----
    val enabledChannels = MutableStateFlow<Set<String>>(setOf("vibrate", "notify"))
}
