package com.classsentinel.core.speech

import java.io.File

/** Prepares verified artifacts; native initialization belongs to the single collection lifecycle. */
internal class ProductionAsrEngineStarter(
    private val prepareDirectory: suspend () -> File,
    private val createEngine: (File) -> ProfileBoundStreamingSpeechEngine,
) {
    suspend fun create(): ProfileBoundStreamingSpeechEngine {
        val directory = prepareDirectory()
        return createEngine(directory)
    }
}
