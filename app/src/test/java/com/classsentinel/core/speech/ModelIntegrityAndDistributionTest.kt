package com.classsentinel.core.speech

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIntegrityAndDistributionTest {

    private val modelFiles = linkedMapOf(
        "encoder.onnx" to byteArrayOf(1, 2, 3),
        "decoder.onnx" to byteArrayOf(4, 5),
        "joiner.onnx" to byteArrayOf(6, 7, 8, 9),
        "tokens.txt" to "tokens".toByteArray(),
    )

    @Test
    fun `profiles declare bundled and remote distributions explicitly`() {
        assertEquals(ModelDistribution.Bundled, ModelProfiles.ZIPFORMER_ZH_14M.distribution)
        assertEquals(ModelDistribution.Bundled, ModelProfiles.SMALL_BILINGUAL_ZH_EN.distribution)
        assertTrue(ModelProfiles.X_ASR_480.distribution is ModelDistribution.Remote)
        assertTrue(ModelProfiles.X_ASR_960.distribution is ModelDistribution.Remote)
        assertEquals(
            "689ff18c584d29910da37b6fe904db0c1489c9d1",
            ModelProfiles.X_ASR_480.version,
        )
        assertEquals(ModelProfiles.X_ASR_480.version, ModelProfiles.X_ASR_960.version)
    }

    @Test
    fun `correct size hash and matching marker make an artifact valid`() {
        withArtifact { root, profile, target ->
            writeFiles(target, profile)
            File(target, ".model-profile").writeText("${profile.id}\n${profile.version}\n")

            assertTrue(ModelIntegrityVerifier.verify(profile, target))
            assertTrue(ModelIntegrityVerifier.isInstalled(root, profile))
        }
    }

    @Test
    fun `wrong size makes an artifact invalid`() {
        withArtifact { root, profile, target ->
            writeFiles(target, profile)
            val encoder = File(target, "encoder.onnx")
            encoder.writeBytes(byteArrayOf(1, 2))
            File(target, ".model-profile").writeText("${profile.id}\n${profile.version}\n")

            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))
        }
    }

    @Test
    fun `wrong sha makes an artifact invalid`() {
        withArtifact { root, profile, target ->
            writeFiles(target, profile)
            File(target, "encoder.onnx").writeBytes(byteArrayOf(9, 9, 9))
            File(target, ".model-profile").writeText("${profile.id}\n${profile.version}\n")

            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))
        }
    }

    @Test
    fun `missing or mismatched marker makes an otherwise complete artifact invalid`() {
        withArtifact { root, profile, target ->
            writeFiles(target, profile)

            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))

            File(target, ".model-profile").writeText("other-profile\n${profile.version}\n")
            assertFalse(ModelIntegrityVerifier.isInstalled(root, profile))
        }
    }

    private fun withArtifact(block: (File, ModelProfile, File) -> Unit) {
        val root = Files.createTempDirectory("model-integrity-").toFile()
        val profile = testProfile()
        val target = File(root, "asr/${profile.artifact.directory}").apply { mkdirs() }
        try {
            block(root, profile, target)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testProfile(): ModelProfile {
        val base = ModelProfiles.ZIPFORMER_ZH_14M
        return base.copy(
            distribution = ModelDistribution.Bundled,
            artifact = base.artifact.copy(
                directory = "integrity-model",
                encoder = spec("encoder.onnx"),
                decoder = spec("decoder.onnx"),
                joiner = spec("joiner.onnx"),
                tokens = spec("tokens.txt"),
            ),
        )
    }

    private fun writeFiles(target: File, profile: ModelProfile) {
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
