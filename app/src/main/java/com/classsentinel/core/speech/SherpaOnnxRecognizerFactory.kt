package com.classsentinel.core.speech

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/** Builds the pinned v1.13.7 CPU/MLAS configuration and adapts its native API. */
internal object SherpaOnnxRecognizerFactory {
    const val MODEL_DIRECTORY_NAME = "zipformer-zh-14M-2023-02-23"

    fun buildConfig(modelDirectory: File): OnlineRecognizerConfig {
        val transducer = OnlineTransducerModelConfig(
            encoder = File(modelDirectory, "encoder-epoch-99-avg-1.int8.onnx").path,
            decoder = File(modelDirectory, "decoder-epoch-99-avg-1.onnx").path,
            joiner = File(modelDirectory, "joiner-epoch-99-avg-1.int8.onnx").path,
        )
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = transducer,
                tokens = File(modelDirectory, "tokens.txt").path,
                provider = "cpu",
                modelType = "zipformer",
                modelingUnit = "cjkchar",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 2.4f,
                    minUtteranceLength = 0.0f,
                ),
                rule2 = EndpointRule(
                    mustContainNonSilence = true,
                    minTrailingSilence = 1.4f,
                    minUtteranceLength = 0.0f,
                ),
                rule3 = EndpointRule(
                    mustContainNonSilence = false,
                    minTrailingSilence = 0.0f,
                    minUtteranceLength = 20.0f,
                ),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
            hotwordsFile = "",
            hotwordsScore = 0.0f,
            ruleFsts = "",
            ruleFars = "",
            blankPenalty = 0.0f,
        )
    }

    fun create(modelDirectory: File): SherpaOnlineRecognizerPort {
        require(modelDirectory.isDirectory) { "ASR_MODEL_NOT_READY" }
        return NativeRecognizerPort(
            OnlineRecognizer(
                assetManager = null,
                config = buildConfig(modelDirectory),
            ),
        )
    }

    private class NativeRecognizerPort(
        private val recognizer: OnlineRecognizer,
    ) : SherpaOnlineRecognizerPort {
        private var released = false

        override fun createStream(): SherpaOnlineStreamPort {
            check(!released) { "ASR_RECOGNIZER_RELEASED" }
            return NativeStreamPort(recognizer, recognizer.createStream(hotwords = ""))
        }

        @Synchronized
        override fun release() {
            if (!released) {
                released = true
                recognizer.release()
            }
        }
    }

    private class NativeStreamPort(
        private val recognizer: OnlineRecognizer,
        private val stream: OnlineStream,
    ) : SherpaOnlineStreamPort {
        private var inputFinished = false
        private var released = false

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            check(!released) { "ASR_STREAM_RELEASED" }
            stream.acceptWaveform(samples, sampleRate)
        }

        override fun isReady(): Boolean = !released && recognizer.isReady(stream)

        override fun decode() {
            check(!released) { "ASR_STREAM_RELEASED" }
            recognizer.decode(stream)
        }

        override fun resultText(): String {
            check(!released) { "ASR_STREAM_RELEASED" }
            return recognizer.getResult(stream).text
        }

        override fun isEndpoint(): Boolean = !released && recognizer.isEndpoint(stream)

        override fun reset() {
            check(!released) { "ASR_STREAM_RELEASED" }
            recognizer.reset(stream)
        }

        @Synchronized
        override fun inputFinished() {
            if (!released && !inputFinished) {
                inputFinished = true
                stream.inputFinished()
            }
        }

        @Synchronized
        override fun release() {
            if (!released) {
                released = true
                stream.release()
            }
        }
    }
}
