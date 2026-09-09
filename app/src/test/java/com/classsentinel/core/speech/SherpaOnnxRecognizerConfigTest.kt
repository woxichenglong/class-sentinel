package com.classsentinel.core.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxRecognizerConfigTest {

    @Test
    fun `default config maps the production X ASR 480 artifact and endpoint contract`() {
        val modelDir = File("/data/user/0/com.classsentinel/files/asr/${ModelProfiles.PRODUCTION.artifact.directory}")

        val config = SherpaOnnxRecognizerFactory.buildConfig(modelDir)
        val model = config.modelConfig
        val transducer = model.transducer

        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals("zipformer2", model.modelType)
        assertEquals("", model.modelingUnit)
        assertEquals("cpu", model.provider)
        assertEquals(File(modelDir, "tokens.txt").path, model.tokens)
        assertEquals(File(modelDir, "encoder-480ms.onnx").path, transducer.encoder)
        assertEquals(File(modelDir, "decoder-480ms.onnx").path, transducer.decoder)
        assertEquals(File(modelDir, "joiner-480ms.onnx").path, transducer.joiner)
        assertEquals("greedy_search", config.decodingMethod)
        assertTrue(config.enableEndpoint)
        assertEquals(2.4f, config.endpointConfig.rule1.minTrailingSilence, 0.0f)
        assertEquals(1.2f, config.endpointConfig.rule2.minTrailingSilence, 0.0f)
        assertEquals(20.0f, config.endpointConfig.rule3.minUtteranceLength, 0.0f)
        assertTrue(config.endpointConfig.rule2.mustContainNonSilence)
        assertFalse(config.endpointConfig.rule1.mustContainNonSilence)
        assertFalse(config.endpointConfig.rule3.mustContainNonSilence)
        assertEquals("", config.hotwordsFile)
        assertEquals(1.5f, config.hotwordsScore, 0.0f)
    }

    @Test
    fun `config maps artifact and recognizer values from an explicit test profile`() {
        val base = ModelProfiles.PRODUCTION
        val profile = base.copy(
            id = "test-profile",
            artifact = base.artifact.copy(
                directory = "test-model",
                encoder = base.artifact.encoder.copy(name = "encoder.onnx"),
                decoder = base.artifact.decoder.copy(name = "decoder.onnx"),
                joiner = base.artifact.joiner.copy(name = "joiner.onnx"),
                tokens = base.artifact.tokens.copy(name = "vocab.txt"),
            ),
            recognizer = base.recognizer.copy(
                modelType = "custom-type",
                modelingUnit = "bpe",
                decodingMethod = "modified_beam_search",
                endpoint = ModelEndpointProfile(
                    rule1 = ModelEndpointRule(false, 1.1f, 0.0f),
                    rule2 = ModelEndpointRule(true, 0.7f, 0.0f),
                    rule3 = ModelEndpointRule(false, 0.0f, 12.0f),
                ),
                enableEndpoint = false,
                maxActivePaths = 9,
                hotwordsFile = "hotwords.txt",
                hotwordsScore = 3.5f,
                ruleFsts = "rules.fst",
                ruleFars = "rules.far",
                blankPenalty = 0.25f,
            ),
        )
        val modelDir = File("/data/user/0/com.classsentinel/files/asr/test-model")

        val config = SherpaOnnxRecognizerFactory.buildConfig(modelDir, profile)
        val model = config.modelConfig
        val transducer = model.transducer

        assertEquals("custom-type", model.modelType)
        assertEquals("bpe", model.modelingUnit)
        assertEquals("cpu", model.provider)
        assertEquals("modified_beam_search", config.decodingMethod)
        assertEquals(false, config.enableEndpoint)
        assertEquals(9, config.maxActivePaths)
        assertEquals("hotwords.txt", config.hotwordsFile)
        assertEquals(3.5f, config.hotwordsScore, 0.0f)
        assertEquals("rules.fst", config.ruleFsts)
        assertEquals("rules.far", config.ruleFars)
        assertEquals(0.25f, config.blankPenalty, 0.0f)
        assertEquals(File(modelDir, "vocab.txt").path, model.tokens)
        assertEquals(File(modelDir, "encoder.onnx").path, transducer.encoder)
        assertEquals(File(modelDir, "decoder.onnx").path, transducer.decoder)
        assertEquals(File(modelDir, "joiner.onnx").path, transducer.joiner)
        assertEquals(1.1f, config.endpointConfig.rule1.minTrailingSilence, 0.0f)
        assertEquals(0.7f, config.endpointConfig.rule2.minTrailingSilence, 0.0f)
        assertEquals(12.0f, config.endpointConfig.rule3.minUtteranceLength, 0.0f)
    }

    @Test
    fun `live and official endpoint modes preserve the production profile settings`() {
        val modelDir = File("/data/user/0/com.classsentinel/files/asr/${ModelProfiles.PRODUCTION.artifact.directory}")

        val live = SherpaOnnxRecognizerFactory.buildConfig(modelDir)
        val official = SherpaOnnxRecognizerFactory.buildConfig(
            modelDirectory = modelDir,
            endpointMode = SherpaEndpointMode.OFFICIAL_DEPLOYMENT,
        )

        assertTrue(live.enableEndpoint)
        assertFalse(official.enableEndpoint)
    }
}
