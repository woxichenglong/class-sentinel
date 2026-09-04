package com.classsentinel.core.speech

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.security.MessageDigest
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
            val installer = SherpaModelInstaller(filesDir = root, profile = testProfile()) { assetPath ->
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
                File(root, "asr/test-model").canonicalFile,
                target.canonicalFile,
            )
            assertEquals(modelFiles.keys + ".model-profile", target.list()?.toSet())
            modelFiles.forEach { (name, bytes) ->
                assertArrayEquals(bytes, File(target, name).readBytes())
            }
            assertFalse(File(root, "asr/unexpected.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `same sized but corrupt existing files are replaced`() {
        val root = Files.createTempDirectory("sherpa-installer-repeat-").toFile()
        try {
            val installer = installerFor(root)
            val target = installer.install()
            val encoder = File(target, "encoder-epoch-99-avg-1.int8.onnx")
            val sentinel = byteArrayOf(9, 9, 9)
            encoder.writeBytes(sentinel)

            installer.install()

            assertArrayEquals(modelFiles.getValue(encoder.name), encoder.readBytes())
            assertTrue(target.listFiles()?.all { it.isFile || it.name == ".model-profile" } == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `valid marker and hashes avoid reopening assets`() {
        val root = Files.createTempDirectory("sherpa-installer-cache-").toFile()
        try {
            var assetOpens = 0
            val installer = SherpaModelInstaller(filesDir = root, profile = testProfile()) { assetPath ->
                assetOpens++
                ByteArrayInputStream(modelFiles.getValue(assetPath.substringAfterLast('/')))
            }

            val target = installer.install()

            assertEquals(4, assetOpens)
            assertTrue(File(target, ".model-profile").isFile)

            installer.install()

            assertEquals(4, assetOpens)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `asset integrity mismatch fails before installing corrupt model`() {
        val root = Files.createTempDirectory("sherpa-installer-integrity-").toFile()
        try {
            val installer = SherpaModelInstaller(filesDir = root, profile = testProfile()) {
                ByteArrayInputStream(byteArrayOf(0))
            }

            val error = assertThrows(IllegalStateException::class.java) { installer.install() }

            assertEquals("ASR_MODEL_INTEGRITY", error.message)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun installerFor(root: File): SherpaModelInstaller =
        SherpaModelInstaller(filesDir = root, profile = testProfile()) { assetPath ->
            val name = assetPath.substringAfterLast('/')
            ByteArrayInputStream(modelFiles.getValue(name))
        }

    private fun testProfile(): ModelProfile {
        val baseline = ModelProfiles.ZIPFORMER_ZH_14M
        return baseline.copy(
            artifact = baseline.artifact.copy(
                directory = "test-model",
                encoder = spec("encoder-epoch-99-avg-1.int8.onnx"),
                decoder = spec("decoder-epoch-99-avg-1.onnx"),
                joiner = spec("joiner-epoch-99-avg-1.int8.onnx"),
                tokens = spec("tokens.txt"),
            ),
        )
    }

    private fun spec(name: String): ModelFileSpec {
        val bytes = modelFiles.getValue(name)
        return ModelFileSpec(
            name = name,
            expectedSize = bytes.size.toLong(),
            sha256 = sha256(bytes),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
