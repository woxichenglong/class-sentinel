package com.classsentinel.core.llm

import android.app.Activity
import android.os.Bundle
import com.classsentinel.core.log.SafeLog
import com.classsentinel.core.llm.AnswerStyle.ACADEMIC
import com.classsentinel.data.SettingsRepositoryHolder
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only, non-UI bridge for the privacy-safe transport probe.
 * It reads the persisted AI key through SettingsRepository/SecretStore and never exposes it.
 */
internal class LlmTransportProbeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val repository = SettingsRepositoryHolder.get(applicationContext)
                    repository.load()
                    val ai = repository.aiSettingsFlow.first()
                    val style = if (repository.answerStyleFlow.first() == "academic") {
                        ACADEMIC
                    } else {
                        AnswerStyle.TERSENESS
                    }
                    val maxTokens = answerLengthPolicy(
                        repository.answerLengthFlow.first(),
                        style,
                    ).maxTokens
                    ai to maxTokens
                }
                val (ai, maxTokens) = snapshot
                SafeLog.d(
                    "llm_probe_config",
                    mapOf(
                        "module" to "LlmTransportProbe",
                        "status" to when (ai.baseUrl.trimEnd('/')) {
                            AiProviderPreset.COMMAND_CODE.baseUrl -> "COMMAND_CODE"
                            AiProviderPreset.DEEPSEEK_OFFICIAL.baseUrl -> "DEEPSEEK_OFFICIAL"
                            else -> "OTHER"
                        },
                    ),
                )
                if (ai.apiKey.isBlank()) {
                    SafeLog.w(
                        "llm_probe_failure",
                        mapOf(
                            "module" to "LlmTransportProbe",
                            "errorCode" to "UNKNOWN",
                        ),
                    )
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    val protocolExtra = intent.getStringExtra(EXTRA_PROTOCOL)
                    val requestExtra = intent.getStringExtra(EXTRA_REQUEST)
                    val selectedProtocols = protocolExtra?.let { value ->
                        ProbeProtocol.values().filter { it.name == value }
                    } ?: ProbeProtocol.values().toList()
                    val selectedRequests = requestExtra?.let { value ->
                        ProbeRequest.values().filter { it.name == value }
                    } ?: ProbeRequest.values().toList()
                    require(selectedProtocols.isNotEmpty())
                    require(selectedRequests.isNotEmpty())
                    LlmTransportProbe().run(
                        config = LlmConfig(
                            baseUrl = ai.baseUrl,
                            apiKey = ai.apiKey,
                            model = ai.model,
                            thinkingDisabled = true,
                        ),
                        maxTokens = maxTokens,
                        protocols = selectedProtocols,
                        requests = selectedRequests,
                    )
                }
                SafeLog.d(
                    "llm_probe_complete",
                    mapOf("module" to "LlmTransportProbe"),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SafeLog.w(
                    "llm_probe_failure",
                    mapOf(
                        "module" to "LlmTransportProbe",
                        "errorCode" to classifyProbeFailure(e).name,
                    ),
                )
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_REQUEST = "request"
    }
}
