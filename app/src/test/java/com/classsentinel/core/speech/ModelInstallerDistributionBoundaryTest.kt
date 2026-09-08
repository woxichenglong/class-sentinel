package com.classsentinel.core.speech

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInstallerDistributionBoundaryTest {

    private val modelFiles = linkedMapOf(
        "encoder.onnx" to byteArrayOf(1, 2, 3),
        "decoder.onnx" to byteArrayOf(4, 5),
        "joiner.onnx" to byteArrayOf(6, 7, 8, 9),
        "tokens.txt" to "tokens".toByteArray(),
    )

    @Test
    fun `remote profile is rejected by bundled Sherpa installer`() {
        val root = Files.createTempDirectory("model-boundary-sherpa-remote-").toFile()
        try {
            val profile = testProfile(ModelDistribution.Remote(remoteLocations()))

            assertThrows(IllegalArgumentException::class.java) {
                SherpaModelInstaller(root, profile) { assetPath ->
                    ByteArrayInputStream(modelFiles.getValue(assetPath.substringAfterLast('/')))
                }.install()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `bundled profile is rejected by remote installer`() {
        val root = Files.createTempDirectory("model-boundary-remote-bundled-").toFile()
        try {
            val profile = testProfile(ModelDistribution.Bundled)

            assertThrows(IllegalArgumentException::class.java) {
                RemoteModelInstaller(root, profile, RemoteModelSource {
                    error("bundled profile must not open a remote source")
                }).install()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `debug importer routes remote profile through remote installer`() {
        val root = Files.createTempDirectory("model-boundary-debug-root-").toFile()
        val source = Files.createTempDirectory("model-boundary-debug-source-").toFile()
        try {
            val profile = testProfile(ModelDistribution.Remote(remoteLocations()))
            modelFiles.forEach { (name, bytes) -> File(source, name).writeBytes(bytes) }

            val target = DebugModelImporter(root).importFromDirectory(profile, source)

            assertTrue(ModelIntegrityVerifier.verify(profile, target))
            assertTrue(ModelIntegrityVerifier.isInstalled(root, profile))
        } finally {
            root.deleteRecursively()
            source.deleteRecursively()
        }
    }

    @Test
    fun `marker with a corrupt formal file is not ready`() {
        val root = Files.createTempDirectory("model-boundary-corrupt-").toFile()
        try {
            val profile = testProfile(ModelDistribution.Bundled)
            val target = targetDirectory(root, profile)
            writeCompleteArtifact(profile, target)
            File(target, ".model-profile").writeText(ModelIntegrityVerifier.markerContent(profile))
            File(target, "decoder.onnx").writeBytes(byteArrayOf(9, 9))

            assertFalse(ModelIntegrityVerifier.verify(profile, target))
            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `partial formal files plus part file without marker are not ready`() {
        val root = Files.createTempDirectory("model-boundary-partial-").toFile()
        try {
            val profile = testProfile(ModelDistribution.Remote(remoteLocations()))
            val target = targetDirectory(root, profile)
            File(target, "encoder.onnx").writeBytes(modelFiles.getValue("encoder.onnx"))
            File(target, "decoder.onnx.part").writeBytes(modelFiles.getValue("decoder.onnx"))

            assertFalse(ModelIntegrityVerifier.verify(profile, target))
            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))
            assertTrue(target.listFiles()?.none { it.name == ".model-profile" } == true)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testProfile(distribution: ModelDistribution): ModelProfile {
        val base = ModelProfiles.ZIPFORMER_ZH_14M
        return base.copy(
            artifact = base.artifact.copy(
                directory = "distribution-boundary",
                encoder = spec("encoder.onnx"),
                decoder = spec("decoder.onnx"),
                joiner = spec("joiner.onnx"),
                tokens = spec("tokens.txt"),
            ),
            distribution = distribution,
        )
    }

    private fun remoteLocations(): Map<String, String> =
        modelFiles.keys.associateWith { "test://boundary/$it" }

    private fun targetDirectory(root: File, profile: ModelProfile): File =
        File(root, "asr/${profile.artifact.directory}").apply { mkdirs() }

    private fun writeCompleteArtifact(profile: ModelProfile, target: File) {
        profile.artifact.files.forEach { spec ->
            File(target, spec.name).writeBytes(modelFiles.getValue(spec.name))
        }
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
