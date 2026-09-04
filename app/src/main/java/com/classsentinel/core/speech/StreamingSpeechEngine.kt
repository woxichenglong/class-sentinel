package com.classsentinel.core.speech

import kotlinx.coroutines.flow.Flow

/** Stable event-based ASR boundary for the live listening path. */
internal interface StreamingSpeechEngine {
    val name: String
    fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent>
}
