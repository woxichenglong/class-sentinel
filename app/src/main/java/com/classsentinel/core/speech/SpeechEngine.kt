package com.classsentinel.core.speech

import kotlinx.coroutines.flow.Flow

/** Legacy network-stream interface used only by the segmented recovery adapter. */
internal interface SpeechEngine {
    val name: String
    fun transcribe(pcm: Flow<ShortArray>): Flow<String>
}
