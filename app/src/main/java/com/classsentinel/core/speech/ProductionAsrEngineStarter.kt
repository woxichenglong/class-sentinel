package com.classsentinel.core.speech

import java.io.File
import kotlinx.coroutines.CancellationException

/** Starts the one production ASR engine without model selection or fallback. */
internal class ProductionAsrEngineStarter(
    private val prepareDirectory: suspend () -> File,
    private val initializeModel: suspend (File) -> Unit,
    private val createEngine: (File) -> ProfileBoundStreamingSpeechEngine,
) {
    suspend fun create(): ProfileBoundStreamingSpeechEngine {
        val directory = prepareDirectory()
        try {
            initializeModel(directory)
        } catch (error: CancellationException) {
            throw error
        }
        return createEngine(directory)
    }
}
