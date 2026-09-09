package com.classsentinel.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfileTest {

    @Test
    fun `production exposes exactly the bundled X ASR 480 profile`() {
        val profile = ModelProfiles.PRODUCTION

        assertSame(ModelProfiles.X_ASR_480, profile)
        assertEquals("x-asr-480", profile.id)
        assertEquals("689ff18c584d29910da37b6fe904db0c1489c9d1", profile.version)
        assertEquals("x-asr-zh-en-480ms", profile.artifact.directory)
        assertEquals(
            listOf("encoder-480ms.onnx", "decoder-480ms.onnx", "joiner-480ms.onnx", "tokens.txt"),
            profile.artifact.files.map { it.name },
        )
        assertEquals(
            listOf(592_968_361L, 11_309_084L, 10_260_467L, 58_806L),
            profile.artifact.files.map { it.expectedSize },
        )
        assertEquals(
            listOf(
                "0c3454033d249081df124ddcd7adaf3deca07d0b999b26f2ee5d2475d37abc74",
                "3658368d274a5d5fd39a7ac20c46bed0ad9cfea1f0feddef30d5d89797c1f499",
                "03781c98165a2385024c9cecdd2b6b13310d81db23a62c7da420782c2915cf81",
                "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c",
            ),
            profile.artifact.files.map { it.sha256 },
        )
        assertEquals("X-ASR 中英增强模型", profile.displayName)
        assertEquals(ModelDistribution.Bundled, profile.distribution)
    }

    @Test
    fun `production keeps the pinned X ASR recognizer contract`() {
        val recognizer = ModelProfiles.PRODUCTION.recognizer

        assertEquals("zipformer2", recognizer.modelType)
        assertEquals("", recognizer.modelingUnit)
        assertEquals("greedy_search", recognizer.decodingMethod)
        assertEquals("cpu", recognizer.provider)
        assertEquals(16_000, recognizer.sampleRate)
        assertEquals(80, recognizer.featureDim)
        assertEquals(480, recognizer.artifactStreamingChunkMs)
        assertTrue(recognizer.enableEndpoint)
        assertFalse(recognizer.officialDeploymentEnableEndpoint)
        assertEquals(4, recognizer.maxActivePaths)
        assertEquals(1.5f, recognizer.hotwordsScore, 0.0f)
    }
}
