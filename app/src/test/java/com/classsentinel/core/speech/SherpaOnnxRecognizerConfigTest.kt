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
}
