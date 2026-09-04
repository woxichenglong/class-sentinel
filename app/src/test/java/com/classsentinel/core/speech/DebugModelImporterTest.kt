package com.classsentinel.core.speech

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DebugModelImporterTest {

    private val modelFiles = mapOf(
        "encoder.onnx" to byteArrayOf(1, 2, 3),
        "decoder.onnx" to byteArrayOf(4, 5),
        "joiner.onnx" to byteArrayOf(6, 7, 8, 9),
        "tokens.txt" to "tokens".toByteArray(),
    )

    @Test
    fun `debug importer copies only profiled files into app private model root`() {
        val root = Files.createTempDirectory("debug-model-import-root-").toFile()
        val source = Files.createTempDirectory("debug-model-import-source-").toFile()
        try {
            modelFiles.forEach { (name, bytes) -> File(source, name).writeBytes(bytes) }
            File(source, "ignored-script.sh").writeText("must not be imported")
            val profile = testProfile()

            val target = DebugModelImporter(root).importFromDirectory(profile, source)

            assertEquals(File(root, "asr/debug-model").canonicalFile, target.canonicalFile)
            assertEquals(
                modelFiles.keys + ".model-profile",
                target.list()?.toSet(),
            )
            modelFiles.forEach { (name, bytes) ->
                assertArrayEquals(bytes, File(target, name).readBytes())
            }
            assertEquals("${profile.id}\n${profile.version}\n", File(target, ".model-profile").readText())
            assertFalse(File(target, "ignored-script.sh").exists())
        } finally {
            root.deleteRecursively()
            source.deleteRecursively()
        }
    }

    @Test
    fun `debug importer rejects corrupt source before accepting model`() {
        val root = Files.createTempDirectory("debug-model-import-corrupt-root-").toFile()
        val source = Files.createTempDirectory("debug-model-import-corrupt-source-").toFile()
        try {
            modelFiles.forEach { (name, bytes) -> File(source, name).writeBytes(bytes) }
            File(source, "encoder.onnx").writeBytes(byteArrayOf(9, 9, 9))

            val error = assertThrows(IllegalStateException::class.java) {
                DebugModelImporter(root).importFromDirectory(testProfile(), source)
            }

            assertEquals("ASR_MODEL_INTEGRITY", error.message)
            assertFalse(File(root, "asr/debug-model/.model-profile").exists())
        } finally {
            root.deleteRecursively()
            source.deleteRecursively()
        }
    }

    private fun testProfile(): ModelProfile {
        val base = ModelProfiles.SMALL_BILINGUAL_ZH_EN
        return base.copy(
            artifact = base.artifact.copy(
                directory = "debug-model",
                encoder = spec("encoder.onnx"),
                decoder = spec("decoder.onnx"),
                joiner = spec("joiner.onnx"),
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
