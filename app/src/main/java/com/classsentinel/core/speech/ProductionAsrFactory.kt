package com.classsentinel.core.speech

import android.content.Context
import com.classsentinel.core.audio.WavSegment
import com.classsentinel.core.config.AppConfig
import com.classsentinel.data.SettingsRepositoryHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 旧分段 ASR 装配：在当前进程或 WorkManager 新进程中按 DataStore 配置构造单段 Router。
 *
 * 这里不保存 process-local engine 实例，也不把凭证放入 WorkManager Data；每次装配都会先
 * 从 SettingsRepository 加载最新设置，供 WAV 导入和离线 pending 段恢复使用。
 */
internal object ProductionAsrFactory {

    /** WorkManager pending 段恢复使用：只返回单段 Router，不负责音频切段。 */
    suspend fun createRouter(
        context: Context,
        onSegmentFailed: (suspend (WavSegment, AsrException) -> Unit)? = null,
    ): SegmentSpeechRouter = assemble(context, onSegmentFailed)

    private suspend fun assemble(
        context: Context,
        onSegmentFailed: (suspend (WavSegment, AsrException) -> Unit)?,
    ): SegmentSpeechRouter {
        val appContext = context.applicationContext
        val settings = SettingsRepositoryHolder.get(appContext)
        val (asrChoice, siliconApiKey, xunfeiAppId, xunfeiApiKey) =
            withContext(Dispatchers.IO) {
                // Worker 可能在没有启动 Activity 的新进程中执行，不能依赖 AppConfig 已被预热。
                settings.load()
                val choice = settings.asrEngineFlow.first()
                AsrSettings(
                    asrChoice = choice,
                    siliconApiKey = AppConfig.siliconApiKey,
                    xunfeiAppId = AppConfig.xunfeiAppId,
                    xunfeiApiKey = AppConfig.xunfeiApiKey,
                )
            }

        val engines = buildEngines(
            asrChoice = asrChoice,
            siliconApiKey = siliconApiKey,
            xunfeiAppId = xunfeiAppId,
            xunfeiApiKey = xunfeiApiKey,
        )
        return SegmentSpeechRouter(
            primary = engines.first(),
            fallbacks = engines.drop(1),
            maxPrimaryRetries = 1,
            onSegmentFailed = onSegmentFailed,
        )
    }

    private fun buildEngines(
        asrChoice: String,
        siliconApiKey: String,
        xunfeiAppId: String,
        xunfeiApiKey: String,
    ): List<SegmentSpeechEngine> {
        val httpEngines = if (siliconApiKey.isBlank()) {
            listOf(
                ConfigFailureSegmentEngine("XingChenASR-V3.2-Ultra"),
                ConfigFailureSegmentEngine("SenseVoiceSmall"),
            )
        } else {
            listOf(
                TeleSpeechEngine(siliconApiKey),
                SenseVoiceEngine(siliconApiKey),
            )
        }

        return when {
            asrChoice == "sensevoice" -> httpEngines.asReversed()
            asrChoice == "xunfei" && xunfeiAppId.isNotBlank() && xunfeiApiKey.isNotBlank() ->
                listOf(
                    XunfeiSegmentSpeechEngine(XunfeiRtasrEngine(xunfeiAppId, xunfeiApiKey)),
                ) + httpEngines
            else -> httpEngines
        }
    }

    private data class AsrSettings(
        val asrChoice: String,
        val siliconApiKey: String,
        val xunfeiAppId: String,
        val xunfeiApiKey: String,
    )

}

/** ASR key 缺失时不触网，返回安全 CONFIG 失败，由上层按既有策略呈现/持久化。 */
private class ConfigFailureSegmentEngine(
    override val name: String,
) : SegmentSpeechEngine {
    override suspend fun transcribeSegment(segment: WavSegment): Result<String> =
        Result.failure(
            AsrException(
                AsrError(
                    kind = AsrError.Kind.CONFIG,
                    retriable = false,
                    message = "ASR is not configured",
                    scope = AsrError.Scope.WORKER_GLOBAL,
                ),
            ),
        )
}

/**
 * 讯飞是旧的 PCM 流式接口；离线 pending 段恢复需要单段接口，因此在边界处把 WAV PCM
 * 转成一次性 Flow。实时配置缺失时由上层选择 HTTP 引擎，不会用空凭证发请求。
 */
private class XunfeiSegmentSpeechEngine(
    private val delegate: XunfeiRtasrEngine,
) : SegmentSpeechEngine {
    override val name: String = delegate.name

    override suspend fun transcribeSegment(segment: WavSegment): Result<String> {
        return try {
            val pcm = pcmFromWav(segment.bytes)
            if (pcm.isEmpty()) {
                Result.failure(
                    AsrException(
                        AsrError(
                            AsrError.Kind.CONFIG,
                            retriable = false,
                            message = "invalid wav",
                            scope = AsrError.Scope.ITEM,
                        ),
                    ),
                )
            } else {
                val text = delegate.transcribe(flowOf(pcm)).toList().joinToString("").trim()
                if (text.isBlank()) {
                    Result.failure(AsrException(AsrError.emptyText()))
                } else {
                    Result.success(text)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AsrException) {
            Result.failure(e)
        } catch (_: IOException) {
            Result.failure(AsrException(AsrError.network("Xunfei network error")))
        } catch (_: Throwable) {
            Result.failure(AsrException(AsrError(AsrError.Kind.UNKNOWN, retriable = false)))
        }
    }

    private fun pcmFromWav(bytes: ByteArray): ShortArray {
        if (bytes.size <= WAV_HEADER_BYTES) return ShortArray(0)
        val sampleCount = (bytes.size - WAV_HEADER_BYTES) / 2
        return ShortArray(sampleCount) { index ->
            val offset = WAV_HEADER_BYTES + index * 2
            ((bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)).toShort()
        }
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
    }
}
