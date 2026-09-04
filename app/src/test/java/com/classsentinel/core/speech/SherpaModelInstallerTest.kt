package com.classsentinel.core.speech

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaModelInstallerTest {

    private val modelFiles = mapOf(
        "encoder-epoch-99-avg-1.int8.onnx" to byteArrayOf(1, 2, 3),
        "decoder-epoch-99-avg-1.onnx" to byteArrayOf(4, 5),
        "joiner-epoch-99-avg-1.int8.onnx" to byteArrayOf(6, 7, 8, 9),
        "tokens.txt" to "tokens".toByteArray(),
    )

    @Test
    fun `missing asset fails with safe model error`() {
        val root = Files.createTempDirectory("sherpa-installer-missing-").toFile()
        try {
            val installer = SherpaModelInstaller(root) { assetPath ->
                if (assetPath.endsWith("tokens.txt")) ByteArrayInputStream("tokens".toByteArray())
                else throw FileNotFoundException(assetPath)
            }

            val error = assertThrows(IllegalStateException::class.java) { installer.install() }

            assertEquals("ASR_MODEL_MISSING", error.message)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `successful installation writes only the four allowed files below model directory`() {
        val root = Files.createTempDirectory("sherpa-installer-success-").toFile()
        try {
            val installer = installerFor(root)

            val target = installer.install()

            assertEquals(
                File(root, "asr/zipformer-zh-14M-2023-02-23").canonicalFile,
                target.canonicalFile,
            )
            assertEquals(modelFiles.keys, target.list()?.toSet())
            modelFiles.forEach { (name, bytes) ->
                assertArrayEquals(bytes, File(target, name).readBytes())
            }
            assertFalse(File(root, "asr/unexpected.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `same sized existing files are not recopied`() {
        val root = Files.createTempDirectory("sherpa-installer-repeat-").toFile()
        try {
            val installer = installerFor(root)
            val target = installer.install()
            val encoder = File(target, "encoder-epoch-99-avg-1.int8.onnx")
            val sentinel = byteArrayOf(9, 9, 9)
            encoder.writeBytes(sentinel)

            installer.install()

            assertArrayEquals(sentinel, encoder.readBytes())
            assertTrue(target.listFiles()?.all { it.isFile } == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `relative model path cannot escape asr root`() {
        val root = Files.createTempDirectory("sherpa-installer-boundary-").toFile()
        try {
            val installer = SherpaModelInstaller(
                filesDir = root,
                assetOpener = { ByteArrayInputStream(byteArrayOf(1)) },
                relativeModelPath = "../outside-model",
            )

            val error = assertThrows(IllegalArgumentException::class.java) { installer.install() }

            assertEquals("ASR_MODEL_PATH_OUTSIDE_ROOT", error.message)
            assertFalse(File(root.parentFile, "outside-model").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun installerFor(root: File): SherpaModelInstaller =
        SherpaModelInstaller(root) { assetPath ->
            val name = assetPath.substringAfterLast('/')
            ByteArrayInputStream(modelFiles.getValue(name))
        }
}
