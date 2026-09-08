package com.classsentinel.core.speech

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Copies the pinned model from APK assets into the app-private ASR directory. */
internal class SherpaModelInstaller(
    private val filesDir: File,
    private val profile: ModelProfile = ModelProfiles.ZIPFORMER_ZH_14M,
    private val assetOpener: (String) -> InputStream,
) {
    init {
        require(profile.distribution is ModelDistribution.Bundled) {
            "ASR_MODEL_DISTRIBUTION_NOT_BUNDLED"
        }
    }

    @Synchronized
    fun install(): File {
        val targetDir = ModelIntegrityVerifier.resolveTargetDirectory(filesDir, profile)

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }
        if (!targetDir.isDirectory) {
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }

        if (hasValidInstallation(targetDir)) return targetDir

        for (spec in profile.artifact.files) {
            val destination = File(targetDir, spec.name)
            if (ModelIntegrityVerifier.verifyFile(destination, spec)) continue

            val temporary = File(targetDir, ".${spec.name}.tmp")
            temporary.delete()
            try {
                assetOpener("$ASSET_ROOT/${profile.artifact.directory}/${spec.name}").use { input ->
                    copyToTemporary(input, temporary)
                }
                if (!ModelIntegrityVerifier.verifyFile(temporary, spec)) {
                    throw IllegalStateException("ASR_MODEL_INTEGRITY")
                }
                if (destination.exists() && !destination.isFile) {
                    throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
                }
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
            } catch (e: IllegalStateException) {
                temporary.delete()
                throw e
            } catch (_: Exception) {
                temporary.delete()
                throw IllegalStateException("ASR_MODEL_MISSING")
            }
        }
        writeMarker(targetDir)
        ModelReadinessChecker.invalidate(filesDir, profile)
        return targetDir
    }

    private fun hasValidInstallation(targetDir: File): Boolean {
        return ModelIntegrityVerifier.verify(profile, targetDir)
    }

    private fun writeMarker(targetDir: File) {
        ModelIntegrityVerifier.writeMarker(profile, targetDir)
    }

    private fun copyToTemporary(input: InputStream, destination: File) {
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
            }
            output.flush()
        }
    }

    companion object {
        /** UI/readiness seam: only a marker-backed, exact hash/size installation is ready. */
        internal fun isInstalled(filesDir: File, profile: ModelProfile): Boolean =
            ModelIntegrityVerifier.isInstalled(filesDir, profile)

        /** Compatibility alias for UI callers; the profile remains the single source of truth. */
        val DEFAULT_MODEL_PATH: String
            get() = ModelProfiles.ZIPFORMER_ZH_14M.artifact.directory
        private const val ASSET_ROOT = "asr"
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
