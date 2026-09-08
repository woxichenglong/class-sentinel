package com.classsentinel.core.speech

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelInstallerTest {

    private val modelFiles = linkedMapOf(
        "encoder.onnx" to byteArrayOf(1, 2, 3),
        "decoder.onnx" to byteArrayOf(4, 5),
        "joiner.onnx" to byteArrayOf(6, 7, 8, 9),
        "tokens.txt" to "tokens".toByteArray(),
    )

    @Test
    fun `all remote files install atomically and write a verified marker`() {
        val root = Files.createTempDirectory("remote-installer-success-").toFile()
        try {
            val profile = remoteProfile()
            val target = RemoteModelInstaller(root, profile, sourceFor()).install()

            assertEquals(
                modelFiles.keys + ".model-profile",
                target.list()?.toSet(),
            )
            modelFiles.forEach { (name, bytes) ->
                assertArrayEquals(bytes, File(target, name).readBytes())
            }
            assertTrue(File(target, ".model-profile").isFile)
            assertTrue(ModelIntegrityVerifier.verify(profile, target))
            assertTrue(ModelReadinessChecker(root, Dispatchers.Unconfined).isReadyBlocking(profile))
            assertTrue(target.listFiles()?.none { it.name.endsWith(".part") } == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sha mismatch fails without a ready marker or part file`() {
        val root = Files.createTempDirectory("remote-installer-sha-").toFile()
        try {
            val profile = remoteProfile()
            val badName = modelFiles.keys.first()
            val error = assertThrows(IllegalStateException::class.java) {
                RemoteModelInstaller(root, profile, RemoteModelSource { location ->
                    val name = location.substringAfterLast('/')
                    val bytes = if (name == badName) byteArrayOf(9, 9, 9) else modelFiles.getValue(name)
                    ByteArrayInputStream(bytes)
                }).install()
            }

            assertEquals("ASR_MODEL_INTEGRITY", error.message)
            val target = File(root, "asr/${profile.artifact.directory}")
            assertFalse(File(target, ".model-profile").exists())
            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))
            assertTrue(target.listFiles()?.none { it.name.endsWith(".part") } == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failure in a later file leaves the whole artifact not ready`() {
        val root = Files.createTempDirectory("remote-installer-mid-file-").toFile()
        try {
            val profile = remoteProfile()
            val failingName = modelFiles.keys.elementAt(1)
            val error = assertThrows(IllegalStateException::class.java) {
                RemoteModelInstaller(root, profile, RemoteModelSource { location ->
                    val name = location.substringAfterLast('/')
                    if (name == failingName) error("source failed")
                    ByteArrayInputStream(modelFiles.getValue(name))
                }).install()
            }

            assertEquals("ASR_MODEL_REMOTE_SOURCE_FAILED", error.message)
            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))
            val target = File(root, "asr/${profile.artifact.directory}")
            assertFalse(File(target, ".model-profile").exists())
            assertTrue(target.listFiles()?.none { it.name.endsWith(".part") } == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `existing correct formal files are reused without another source read`() {
        val root = Files.createTempDirectory("remote-installer-reuse-").toFile()
        try {
            val profile = remoteProfile()
            val opens = AtomicInteger()
            val first = RemoteModelInstaller(root, profile, sourceFor { opens.incrementAndGet() })
            val target = first.install()
            assertEquals(modelFiles.size, opens.get())

            val reused = RemoteModelInstaller(root, profile, RemoteModelSource {
                error("correct installed files must be reused")
            }).install()

            assertEquals(target.canonicalFile, reused.canonicalFile)
            assertTrue(ModelIntegrityVerifier.isInstalled(root, profile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `existing corrupt formal file is not treated as ready and is reacquired`() {
        val root = Files.createTempDirectory("remote-installer-corrupt-").toFile()
        try {
            val profile = remoteProfile()
            val target = RemoteModelInstaller(root, profile, sourceFor()).install()
            val encoder = File(target, "encoder.onnx")
            encoder.writeBytes(byteArrayOf(9, 9, 9))
            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))

            val opens = AtomicInteger()
            RemoteModelInstaller(root, profile, sourceFor { opens.incrementAndGet() }).install()

            assertEquals(1, opens.get())
            assertArrayEquals(modelFiles.getValue(encoder.name), encoder.readBytes())
            assertTrue(ModelIntegrityVerifier.isInstalled(root, profile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `part files are ignored by readiness`() {
        val root = Files.createTempDirectory("remote-installer-part-").toFile()
        try {
            val profile = remoteProfile()
            val target = File(root, "asr/${profile.artifact.directory}").apply { mkdirs() }
            modelFiles.forEach { (name, bytes) -> File(target, "$name.part").writeBytes(bytes) }

            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))
            assertFalse(ModelReadinessChecker(root, Dispatchers.Unconfined).isReadyBlocking(profile))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun remoteProfile(): ModelProfile {
        val base = ModelProfiles.X_ASR_480
        return base.copy(
            artifact = base.artifact.copy(
                directory = "remote-model",
                encoder = spec("encoder.onnx"),
                decoder = spec("decoder.onnx"),
                joiner = spec("joiner.onnx"),
                tokens = spec("tokens.txt"),
            ),
            distribution = ModelDistribution.Remote(
                files = modelFiles.keys.associateWith { "test://remote/$it" },
            ),
        )
    }

    private fun sourceFor(onOpen: () -> Unit = {}): RemoteModelSource =
        RemoteModelSource { location ->
            onOpen()
            ByteArrayInputStream(modelFiles.getValue(location.substringAfterLast('/')))
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

    private fun ModelReadinessChecker.isReadyBlocking(profile: ModelProfile): Boolean =
        runBlocking { isReady(profile) }
}
