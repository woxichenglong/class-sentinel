package com.classsentinel.ui.screens

import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.core.speech.ModelFileSpec
import com.classsentinel.core.speech.ModelProfile
import com.classsentinel.core.speech.ModelProfiles
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HomeScreenStateTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `home state describes listening status without course statistics`() {
        assertEquals("未在监听", homeStateText(PipelineState.Idle))
        assertEquals("正在监听 · 已转写 3 句", homeStateText(PipelineState.Listening(3)))
        assertEquals("正在启动监听…", homeStateText(PipelineState.Starting))
        assertEquals("监听出错：转写中断", homeStateText(PipelineState.Error("转写中断")))
    }

    @Test
    fun `home start gate never allows service start before model readiness`() {
        assertEquals(LocalListeningStartGate.MODEL_NOT_READY, localListeningStartGate(null))
        assertEquals(LocalListeningStartGate.MODEL_NOT_READY, localListeningStartGate(false))
        assertEquals(LocalListeningStartGate.READY, localListeningStartGate(true))
    }

    @Test
    fun `local model status requires the validated profile marker and all files`() {
        val root = temporaryFolder.newFolder("files")
        val profile = testProfile("home-model")
        val modelDir = File(root, "asr/${profile.artifact.directory}").apply { mkdirs() }

        assertFalse(localAsrModelReady(root, profile))
        profile.artifact.files.dropLast(1).forEach { spec ->
            File(modelDir, spec.name).writeBytes(MODEL_BYTES.getValue(spec.name))
        }
        assertFalse(localAsrModelReady(root, profile))
        profile.artifact.files.forEach { spec -> File(modelDir, spec.name).writeBytes(MODEL_BYTES.getValue(spec.name)) }
        assertFalse(localAsrModelReady(root, profile))
        File(modelDir, ".model-profile").writeText("${profile.id}\n${profile.version}\n")

        assertTrue(localAsrModelReady(root, profile))
    }

    @Test
    fun `local model status follows the selected profile artifact layout`() {
        val root = temporaryFolder.newFolder("x-asr-files")
        val profile = testProfile("selected-model")
        val modelDir = File(root, "asr/${profile.artifact.directory}").apply { mkdirs() }

        assertFalse(localAsrModelReady(root, profile))
        profile.artifact.files.forEach { spec -> File(modelDir, spec.name).writeBytes(MODEL_BYTES.getValue(spec.name)) }
        assertFalse(localAsrModelReady(root, profile))
        File(modelDir, ".model-profile").writeText("${profile.id}\n${profile.version}\n")

        assertTrue(localAsrModelReady(root, profile))
    }

    private fun testProfile(directory: String): ModelProfile {
        val base = ModelProfiles.SMALL_BILINGUAL_ZH_EN
        return base.copy(
            artifact = base.artifact.copy(
                directory = directory,
                encoder = spec("encoder.onnx"),
                decoder = spec("decoder.onnx"),
                joiner = spec("joiner.onnx"),
                tokens = spec("tokens.txt"),
            ),
        )
    }

    private fun spec(name: String): ModelFileSpec {
        val bytes = MODEL_BYTES.getValue(name)
        return ModelFileSpec(name, bytes.size.toLong(), sha256(bytes))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        val MODEL_BYTES = mapOf(
            "encoder.onnx" to byteArrayOf(1, 2, 3),
            "decoder.onnx" to byteArrayOf(4, 5),
            "joiner.onnx" to byteArrayOf(6, 7, 8, 9),
            "tokens.txt" to "tokens".toByteArray(),
        )
    }
}
