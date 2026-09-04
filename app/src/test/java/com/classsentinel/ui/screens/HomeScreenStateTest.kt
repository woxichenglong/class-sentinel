package com.classsentinel.ui.screens

import com.classsentinel.core.pipeline.PipelineState
import java.io.File
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
    fun `local model status is false until all four files are nonempty`() {
        val root = temporaryFolder.newFolder("files")

        assertFalse(localAsrModelReady(root))
        val modelDir = File(root, "asr/zipformer-zh-14M-2023-02-23").apply { mkdirs() }
        MODEL_FILES.dropLast(1).forEach { File(modelDir, it).writeText("model") }
        assertFalse(localAsrModelReady(root))
        File(modelDir, MODEL_FILES.last()).writeText("tokens")

        assertTrue(localAsrModelReady(root))
    }

    private companion object {
        val MODEL_FILES = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        )
    }
}
