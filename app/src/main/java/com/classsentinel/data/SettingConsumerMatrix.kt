package com.classsentinel.data

/**
 * SettingsScreen 的可见设置与生产消费者登记表。
 *
 * 新增可见设置时必须同时登记 key 和消费者；[SettingConsumerMatrixTest] 会阻止
 * “能保存但没有任何运行时效果”的设置进入版本。
 */
object SettingConsumerMatrix {
    val visibleKeys: Set<String> = linkedSetOf(
        "names",
        "sensitivityPreset",
        "rollcallSuppressMs",
        "questionSuppressMs",
        "questionWordLevel",
        "channel.vibrate",
        "channel.notify",
        "vibrationMode",
        "ai.baseUrl",
        "ai.apiKey",
        "ai.model",
        "answerLength",
        "answerStyle",
        "streamOutput",
        "answerTriggerMode",
        "darkMode",
        "localAsrModel",
    )

    val consumers: Map<String, String> = linkedMapOf(
        "names" to "AppConfig.names → EventEngine/NameMatcher",
        "sensitivityPreset" to "AppConfig.sensitivity → EventEngine",
        "rollcallSuppressMs" to "AppConfig.sensitivity → EventEngine",
        "questionSuppressMs" to "AppConfig.sensitivity → EventEngine",
        "questionWordLevel" to "AppConfig.sensitivity → EventEngine",
        "channel.vibrate" to "AppConfig.enabledChannels → AlertCoordinator",
        "channel.notify" to "AppConfig.enabledChannels → AlertCoordinator",
        "vibrationMode" to "AppConfig.vibrationMode → VibratorChannel",
        "ai.baseUrl" to "SettingsRepository → ListenService/SummaryWorker",
        "ai.apiKey" to "KeystoreSecretStore → ListenService/SummaryWorker",
        "ai.model" to "SettingsRepository → ListenService/SummaryWorker",
        "answerLength" to "AnswerService → prompt/max_tokens",
        "answerStyle" to "AnswerService → ListenService",
        "streamOutput" to "AnswerService → ListenService",
        "answerTriggerMode" to "AnswerTriggerPolicy → ListenService/AnswerGenerationCoordinator",
        "darkMode" to "MainActivity → ClassSentinelTheme",
        "localAsrModel" to "SettingsRepository → ListenServiceHandleFactory/SherpaOnnxStreamingEngine",
    )
}
