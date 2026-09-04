package com.classsentinel.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfileTest {

    @Test
    fun `current zh 14m profile pins artifact and recognizer contract`() {
        val profile = ModelProfiles.ZIPFORMER_ZH_14M

        assertEquals("sherpa-zh-14m", profile.id)
        assertEquals("2023-02-23", profile.version)
        assertEquals("zipformer-zh-14M-2023-02-23", profile.artifact.directory)
        assertEquals(
            listOf(
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt",
            ),
            profile.artifact.files.map { it.name },
        )
        assertEquals("zipformer", profile.recognizer.modelType)
        assertEquals("cjkchar", profile.recognizer.modelingUnit)
        assertEquals("greedy_search", profile.recognizer.decodingMethod)
        assertEquals(16_000, profile.recognizer.sampleRate)
        assertEquals(80, profile.recognizer.featureDim)
        assertEquals(2.4f, profile.recognizer.endpoint.rule1.minTrailingSilence, 0.0f)
        assertEquals(1.4f, profile.recognizer.endpoint.rule2.minTrailingSilence, 0.0f)
        assertEquals(20.0f, profile.recognizer.endpoint.rule3.minUtteranceLength, 0.0f)
        assertEquals(null, profile.recognizer.artifactStreamingChunkMs)
    }

    @Test
    fun `current profile exposes honest capabilities`() {
        val capabilities = ModelProfiles.ZIPFORMER_ZH_14M.capabilities

        assertTrue(capabilities.zh)
        assertFalse(capabilities.en)
        assertTrue(capabilities.streaming)
        assertFalse(capabilities.hotwords)
        assertFalse(capabilities.codeSwitch)
    }

    @Test
    fun `current profile artifact hashes and sizes match checked in assets`() {
        val files = ModelProfiles.ZIPFORMER_ZH_14M.artifact.files

        assertEquals(21_621_684L, files.first { it.name.startsWith("encoder") }.expectedSize)
        assertEquals(
            "1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1",
            files.first { it.name.startsWith("encoder") }.sha256,
        )
        assertEquals(7_509_745L, files.first { it.name.startsWith("decoder") }.expectedSize)
        assertEquals(
            "5ee0f03a2768ff1d5c83ef3a493243c7935d316cd41280037b14783a3467cc78",
            files.first { it.name.startsWith("decoder") }.sha256,
        )
        assertEquals(1_795_562L, files.first { it.name.startsWith("joiner") }.expectedSize)
        assertEquals(
            "a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169",
            files.first { it.name.startsWith("joiner") }.sha256,
        )
        assertEquals(48_697L, files.first { it.name == "tokens.txt" }.expectedSize)
        assertEquals(
            "8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac",
            files.first { it.name == "tokens.txt" }.sha256,
        )
    }

    @Test
    fun `profile preserves upstream empty model type when runtime infers legacy transducer`() {
        val base = ModelProfiles.ZIPFORMER_ZH_14M

        val profile = base.copy(
            recognizer = base.recognizer.copy(
                modelType = "",
                modelingUnit = "",
            ),
        )

        assertEquals("", profile.recognizer.modelType)
        assertEquals("", profile.recognizer.modelingUnit)
    }

    @Test
    fun `small bilingual profile pins official int8 artifact and deployment contract`() {
        val profile = ModelProfiles.SMALL_BILINGUAL_ZH_EN

        assertEquals("sherpa-small-bilingual-zh-en", profile.id)
        assertEquals("2023-02-16", profile.version)
        assertEquals("small-bilingual-zh-en-2023-02-16", profile.artifact.directory)
        assertEquals(
            listOf(
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt",
            ),
            profile.artifact.files.map { it.name },
        )
        assertEquals(
            listOf(42_980_793L, 13_877_276L, 3_228_485L, 56_317L),
            profile.artifact.files.map { it.expectedSize },
        )
        assertEquals(
            listOf(
                "db6f51551762e40e549166fe041ea3e45464370b595e9ad23f06478ec3794fbb",
                "89be509a83175261695bdef5fd1c7b9ab1129a663d1284e7ba9f8507b21e0906",
                "bdda356d6f9b8c2d7cee9ee0e26075fa537490f7fd06520be408d287073667b9",
                "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3",
            ),
            profile.artifact.files.map { it.sha256 },
        )
        assertEquals("", profile.recognizer.modelType)
        assertEquals("", profile.recognizer.modelingUnit)
        assertEquals("greedy_search", profile.recognizer.decodingMethod)
        assertEquals("cpu", profile.recognizer.provider)
        assertEquals(16_000, profile.recognizer.sampleRate)
        assertEquals(80, profile.recognizer.featureDim)
        assertEquals(2.4f, profile.recognizer.endpoint.rule1.minTrailingSilence, 0.0f)
        assertEquals(1.2f, profile.recognizer.endpoint.rule2.minTrailingSilence, 0.0f)
        assertEquals(20.0f, profile.recognizer.endpoint.rule3.minUtteranceLength, 0.0f)
        assertEquals(null, profile.recognizer.artifactStreamingChunkMs)
        assertEquals(1.5f, profile.recognizer.hotwordsScore, 0.0f)
        assertTrue(profile.capabilities.zh)
        assertTrue(profile.capabilities.en)
        assertTrue(profile.capabilities.streaming)
        assertTrue(profile.capabilities.hotwords)
        assertTrue(profile.capabilities.codeSwitch)
    }

    @Test
    fun `x asr 480 profile pins immutable hub artifact and deployment contract`() {
        val profile = ModelProfiles.X_ASR_480

        assertEquals("x-asr-480", profile.id)
        assertEquals("689ff18c584d29910da37b6fe904db0c1489c9d1", profile.version)
        assertEquals("x-asr-zh-en-480ms", profile.artifact.directory)
        assertEquals(
            listOf(
                "encoder-480ms.onnx",
                "decoder-480ms.onnx",
                "joiner-480ms.onnx",
                "tokens.txt",
            ),
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
        assertEquals("zipformer2", profile.recognizer.modelType)
        assertEquals("", profile.recognizer.modelingUnit)
        assertEquals("greedy_search", profile.recognizer.decodingMethod)
        assertEquals("cpu", profile.recognizer.provider)
        assertEquals(16_000, profile.recognizer.sampleRate)
        assertEquals(80, profile.recognizer.featureDim)
        assertEquals(480, profile.recognizer.artifactStreamingChunkMs)
        assertEquals(false, profile.recognizer.enableEndpoint)
        assertEquals(4, profile.recognizer.maxActivePaths)
        assertEquals(1.5f, profile.recognizer.hotwordsScore, 0.0f)
        assertTrue(profile.capabilities.zh)
        assertTrue(profile.capabilities.en)
        assertTrue(profile.capabilities.streaming)
        assertTrue(profile.capabilities.codeSwitch)
    }

    @Test
    fun `x asr 960 profile pins immutable hub artifact and deployment contract`() {
        val profile = ModelProfiles.X_ASR_960

        assertEquals("x-asr-960", profile.id)
        assertEquals("689ff18c584d29910da37b6fe904db0c1489c9d1", profile.version)
        assertEquals("x-asr-zh-en-960ms", profile.artifact.directory)
        assertEquals(
            listOf(
                "encoder-960ms.onnx",
                "decoder-960ms.onnx",
                "joiner-960ms.onnx",
                "tokens.txt",
            ),
            profile.artifact.files.map { it.name },
        )
        assertEquals(
            listOf(592_966_960L, 11_309_084L, 10_260_467L, 58_806L),
            profile.artifact.files.map { it.expectedSize },
        )
        assertEquals(
            listOf(
                "dd9484b7c34c951495f3420f26f9f2ab706e748bc087cd14dfe0b90d3156264f",
                "3658368d274a5d5fd39a7ac20c46bed0ad9cfea1f0feddef30d5d89797c1f499",
                "03781c98165a2385024c9cecdd2b6b13310d81db23a62c7da420782c2915cf81",
                "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c",
            ),
            profile.artifact.files.map { it.sha256 },
        )
        assertEquals("zipformer2", profile.recognizer.modelType)
        assertEquals("", profile.recognizer.modelingUnit)
        assertEquals("greedy_search", profile.recognizer.decodingMethod)
        assertEquals("cpu", profile.recognizer.provider)
        assertEquals(16_000, profile.recognizer.sampleRate)
        assertEquals(80, profile.recognizer.featureDim)
        assertEquals(960, profile.recognizer.artifactStreamingChunkMs)
        assertEquals(false, profile.recognizer.enableEndpoint)
        assertEquals(4, profile.recognizer.maxActivePaths)
        assertEquals(1.5f, profile.recognizer.hotwordsScore, 0.0f)
        assertTrue(profile.capabilities.zh)
        assertTrue(profile.capabilities.en)
        assertTrue(profile.capabilities.streaming)
        assertTrue(profile.capabilities.codeSwitch)
    }
}
