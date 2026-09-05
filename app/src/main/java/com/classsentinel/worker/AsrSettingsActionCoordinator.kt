package com.classsentinel.worker

import com.classsentinel.core.config.AppConfig
import com.classsentinel.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * 设置 action 层：先持久化 ASR 配置，成功且 provider 已就绪后才恢复 pending recovery。
 * SettingsRepository 本身只负责保存，不持有 WorkManager 依赖。
 */
class AsrSettingsActionCoordinator(
    private val persistSiliconKey: suspend (String) -> Unit,
    private val persistEngine: suspend (String) -> Unit,
    private val currentEngine: suspend () -> String,
    private val isRecoveryReady: (String) -> Boolean,
    private val resumeAfterConfigChange: suspend () -> Boolean,
) {
    suspend fun saveSiliconKey(key: String): Boolean {
        val normalized = key.trim()
        persistSiliconKey(normalized)
        if (normalized.isBlank()) return false
        return if (isRecoveryReady(currentEngine())) {
            resumeAfterConfigChange()
        } else {
            false
        }
    }

    suspend fun saveEngine(engine: String): Boolean {
        val normalized = engine.trim()
        persistEngine(normalized)
        return if (normalized.isNotBlank() && isRecoveryReady(normalized)) {
            resumeAfterConfigChange()
        } else {
            false
        }
    }

    companion object {
        fun create(
            context: android.content.Context,
            settings: SettingsRepository,
        ): AsrSettingsActionCoordinator {
            val resume = PendingRecoveryResumeCoordinator.create(context)
            return AsrSettingsActionCoordinator(
                persistSiliconKey = settings::saveAsrSiliconKey,
                persistEngine = settings::saveAsrEngine,
                currentEngine = { settings.asrEngineFlow.first() },
                isRecoveryReady = { engine ->
                    if (engine == "xunfei") {
                        AppConfig.xunfeiAppId.isNotBlank() &&
                            AppConfig.xunfeiApiKey.isNotBlank() &&
                            AppConfig.xunfeiApiSecret.isNotBlank()
                    } else {
                        AppConfig.siliconApiKey.isNotBlank()
                    }
                },
                resumeAfterConfigChange = resume::resumeAfterAsrConfigChange,
            )
        }
    }
}
