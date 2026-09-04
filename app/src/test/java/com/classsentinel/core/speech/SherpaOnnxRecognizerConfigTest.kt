package com.classsentinel.core.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxRecognizerConfigTest {

    @Test
    fun `config pins local zipformer cpu greedy and explicit endpoint rules`() {
        val modelDir = File("/data/user/0/com.classsentinel/files/asr/zipformer-zh-14M-2023-02-23")

        val config = SherpaOnnxRecognizerFactory.buildConfig(modelDir)
        val model = config.modelConfig
        val transducer = model.transducer

        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals("zipformer", model.modelType)
        assertEquals("cjkchar", model.modelingUnit)
        assertEquals("cpu", model.provider)
        assertEquals(
            File(modelDir, "tokens.txt").path,
            model.tokens,
        )
        assertEquals(
            File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").path,
            transducer.encoder,
        )
        assertEquals(
            File(modelDir, "decoder-epoch-99-avg-1.onnx").path,
            transducer.decoder,
        )
        assertEquals(
            File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").path,
            transducer.joiner,
        )
        assertEquals("greedy_search", config.decodingMethod)
        assertTrue(config.enableEndpoint)
        assertEquals(2.4f, config.endpointConfig.rule1.minTrailingSilence, 0.0f)
        assertEquals(1.4f, config.endpointConfig.rule2.minTrailingSilence, 0.0f)
        assertEquals(20.0f, config.endpointConfig.rule3.minUtteranceLength, 0.0f)
        assertTrue(config.endpointConfig.rule2.mustContainNonSilence)
        assertFalse(config.endpointConfig.rule1.mustContainNonSilence)
        assertFalse(config.endpointConfig.rule3.mustContainNonSilence)
        assertEquals("", config.hotwordsFile)
        assertEquals(0.0f, config.hotwordsScore, 0.0f)
    }

    @Test
    fun `config maps artifact and recognizer values from a custom profile`() {
        val base = ModelProfiles.ZIPFORMER_ZH_14M
        val profile = base.copy(
            artifact = base.artifact.copy(
                directory = "x-asr-zh-en-480",
                encoder = base.artifact.encoder.copy(name = "encoder.onnx"),
                decoder = base.artifact.decoder.copy(name = "decoder.onnx"),
                joiner = base.artifact.joiner.copy(name = "joiner.onnx"),
                tokens = base.artifact.tokens.copy(name = "vocab.txt"),
            ),
            recognizer = base.recognizer.copy(
                modelType = "zipformer2",
                modelingUnit = "bpe",
                decodingMethod = "modified_beam_search",
                provider = "cpu",
                sampleRate = 16_000,
                featureDim = 80,
                endpoint = ModelEndpointProfile(
                    rule1 = ModelEndpointRule(false, 1.1f, 0.0f),
                    rule2 = ModelEndpointRule(true, 0.7f, 0.0f),
                    rule3 = ModelEndpointRule(false, 0.0f, 12.0f),
                ),
                artifactStreamingChunkMs = 480,
                enableEndpoint = false,
                maxActivePaths = 9,
                hotwordsFile = "hotwords.txt",
                hotwordsScore = 3.5f,
                ruleFsts = "rules.fst",
                ruleFars = "rules.far",
                blankPenalty = 0.25f,
            ),
        )
        val modelDir = File("/data/user/0/com.classsentinel/files/asr/x-asr-zh-en-480")

        val config = SherpaOnnxRecognizerFactory.buildConfig(modelDir, profile)
        val model = config.modelConfig
        val transducer = model.transducer

        assertEquals("zipformer2", model.modelType)
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
    fun `config maps official small bilingual profile without inventing model type`() {
        val modelDir = File("/data/user/0/com.classsentinel/files/asr/small-bilingual-zh-en-2023-02-16")

        val config = SherpaOnnxRecognizerFactory.buildConfig(
            modelDirectory = modelDir,
            profile = ModelProfiles.SMALL_BILINGUAL_ZH_EN,
        )
        val model = config.modelConfig
        val transducer = model.transducer

        assertEquals("", model.modelType)
        assertEquals("", model.modelingUnit)
        assertEquals("cpu", model.provider)
        assertEquals("greedy_search", config.decodingMethod)
        assertEquals(16_000, config.featConfig.sampleRate)
        assertEquals(80, config.featConfig.featureDim)
        assertEquals(
            File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").path,
            transducer.encoder,
        )
        assertEquals(
            File(modelDir, "decoder-epoch-99-avg-1.onnx").path,
            transducer.decoder,
        )
        assertEquals(
            File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").path,
            transducer.joiner,
        )
        assertEquals(File(modelDir, "tokens.txt").path, model.tokens)
        assertEquals(2.4f, config.endpointConfig.rule1.minTrailingSilence, 0.0f)
        assertEquals(1.2f, config.endpointConfig.rule2.minTrailingSilence, 0.0f)
        assertEquals(20.0f, config.endpointConfig.rule3.minUtteranceLength, 0.0f)
        assertEquals(1.5f, config.hotwordsScore, 0.0f)
    }

    @Test
    fun `config maps both x asr chunk profiles to their matching model files`() {
        val cases = listOf(
            ModelProfiles.X_ASR_480 to "x-asr-zh-en-480ms",
            ModelProfiles.X_ASR_960 to "x-asr-zh-en-960ms",
        )

        cases.forEach { (profile, directory) ->
            val modelDir = File("/data/user/0/com.classsentinel/files/asr/$directory")
            val config = SherpaOnnxRecognizerFactory.buildConfig(modelDir, profile)
            val model = config.modelConfig
            val transducer = model.transducer

            assertEquals("zipformer2", model.modelType)
            assertEquals("", model.modelingUnit)
            assertEquals("cpu", model.provider)
            assertEquals("greedy_search", config.decodingMethod)
            assertEquals(false, config.enableEndpoint)
            assertEquals(16_000, config.featConfig.sampleRate)
            assertEquals(80, config.featConfig.featureDim)
            assertEquals(File(modelDir, profile.artifact.encoder.name).path, transducer.encoder)
            assertEquals(File(modelDir, profile.artifact.decoder.name).path, transducer.decoder)
            assertEquals(File(modelDir, profile.artifact.joiner.name).path, transducer.joiner)
            assertEquals(File(modelDir, profile.artifact.tokens.name).path, model.tokens)
        }
    }
}
