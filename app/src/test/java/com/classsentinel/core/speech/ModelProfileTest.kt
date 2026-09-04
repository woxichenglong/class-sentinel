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
        assertEquals(null, profile.recognizer.streamChunkMs)
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
}
