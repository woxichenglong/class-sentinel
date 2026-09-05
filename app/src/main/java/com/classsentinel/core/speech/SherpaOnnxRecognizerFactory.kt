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

/** Selects the endpoint policy for a recognizer configuration. */
internal enum class SherpaEndpointMode {
    LIVE,
    OFFICIAL_DEPLOYMENT,
}

/** Builds the pinned v1.13.7 CPU/MLAS configuration and adapts its native API. */
internal object SherpaOnnxRecognizerFactory {
    fun buildConfig(
        modelDirectory: File,
        profile: ModelProfile = ModelProfiles.ZIPFORMER_ZH_14M,
        endpointMode: SherpaEndpointMode = SherpaEndpointMode.LIVE,
    ): OnlineRecognizerConfig {
        val artifact = profile.artifact
        val recognizer = profile.recognizer
        val transducer = OnlineTransducerModelConfig(
            encoder = File(modelDirectory, artifact.encoder.name).path,
            decoder = File(modelDirectory, artifact.decoder.name).path,
            joiner = File(modelDirectory, artifact.joiner.name).path,
        )
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = recognizer.sampleRate,
                featureDim = recognizer.featureDim,
            ),
            modelConfig = OnlineModelConfig(
                transducer = transducer,
                tokens = File(modelDirectory, artifact.tokens.name).path,
                provider = recognizer.provider,
                modelType = recognizer.modelType,
                modelingUnit = recognizer.modelingUnit,
            ),
            endpointConfig = recognizer.endpoint.toSherpaConfig(),
            enableEndpoint = when (endpointMode) {
                SherpaEndpointMode.LIVE -> recognizer.enableEndpoint
                SherpaEndpointMode.OFFICIAL_DEPLOYMENT -> recognizer.officialDeploymentEnableEndpoint
            },
            decodingMethod = recognizer.decodingMethod,
            maxActivePaths = recognizer.maxActivePaths,
            hotwordsFile = recognizer.hotwordsFile,
            hotwordsScore = recognizer.hotwordsScore,
            ruleFsts = recognizer.ruleFsts,
            ruleFars = recognizer.ruleFars,
            blankPenalty = recognizer.blankPenalty,
        )
    }

    fun create(
        modelDirectory: File,
        profile: ModelProfile = ModelProfiles.ZIPFORMER_ZH_14M,
        endpointMode: SherpaEndpointMode = SherpaEndpointMode.LIVE,
    ): SherpaOnlineRecognizerPort {
        require(modelDirectory.isDirectory) { "ASR_MODEL_NOT_READY" }
        return NativeRecognizerPort(
            OnlineRecognizer(
                assetManager = null,
                config = buildConfig(modelDirectory, profile, endpointMode),
            ),
        )
    }

    private fun ModelEndpointProfile.toSherpaConfig(): EndpointConfig = EndpointConfig(
        rule1 = rule1.toSherpaRule(),
        rule2 = rule2.toSherpaRule(),
        rule3 = rule3.toSherpaRule(),
    )

    private fun ModelEndpointRule.toSherpaRule(): EndpointRule = EndpointRule(
        mustContainNonSilence = mustContainNonSilence,
        minTrailingSilence = minTrailingSilence,
        minUtteranceLength = minUtteranceLength,
    )

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
