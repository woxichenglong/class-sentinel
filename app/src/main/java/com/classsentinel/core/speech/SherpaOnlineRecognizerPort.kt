package com.classsentinel.core.speech

/**
 * Small seam around sherpa-onnx's Android OnlineRecognizer/OnlineStream API.
 * Production adapters own the native objects; tests can model decoder timing
 * without loading the AAR's JNI library.
 */
internal interface SherpaOnlineRecognizerPort {
    fun createStream(): SherpaOnlineStreamPort
    fun release()
}

internal interface SherpaOnlineStreamPort {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun isReady(): Boolean
    fun decode()
    fun resultText(): String
    fun isEndpoint(): Boolean
    fun reset()
    fun inputFinished()
    fun release()
}
