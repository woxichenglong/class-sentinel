package com.classsentinel.core.speech

import android.annotation.SuppressLint
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException

/** Extra free space required while copying the large bundled production model. */
internal const val MODEL_STORAGE_SAFETY_MARGIN_BYTES = 256L * 1024L * 1024L
internal const val ASR_MODEL_STORAGE_INSUFFICIENT = "ASR_MODEL_STORAGE_INSUFFICIENT"

/** Stable file-system seam used by the installer and readiness preflight. */
@SuppressLint("UsableSpace")
internal fun modelUsableSpace(file: File): Long = file.usableSpace

/** Copies the pinned production model from APK assets into the app-private ASR directory. */
internal class SherpaModelInstaller(
    private val filesDir: File,
    private val profile: ModelProfile = ModelProfiles.PRODUCTION,
    private val availableSpace: (File) -> Long = ::modelUsableSpace,
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

        if (ModelIntegrityVerifier.verify(profile, targetDir)) return targetDir
        // A stale marker must never survive a failed replacement or a storage preflight failure.
        ModelIntegrityVerifier.clearMarker(targetDir)

        try {
            ensureStorageAvailable(targetDir)
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
                    ModelIntegrityVerifier.atomicReplace(temporary, destination)
                } catch (error: CancellationException) {
                    temporary.delete()
                    throw error
                } catch (error: IllegalStateException) {
                    temporary.delete()
                    throw error
                } catch (_: Exception) {
                    temporary.delete()
                    throw IllegalStateException("ASR_MODEL_MISSING")
                }
            }

            ModelIntegrityVerifier.writeMarker(profile, targetDir)
            check(ModelIntegrityVerifier.verify(profile, targetDir)) { "ASR_MODEL_INTEGRITY" }
            ModelReadinessChecker.invalidate(filesDir, profile)
            return targetDir
        } catch (error: CancellationException) {
            clearMarkerAfterFailure(targetDir)
            throw error
        } catch (error: IllegalStateException) {
            clearMarkerAfterFailure(targetDir)
            throw error
        } catch (_: Exception) {
            clearMarkerAfterFailure(targetDir)
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }
    }

    private fun ensureStorageAvailable(targetDir: File) {
        val remainingBytes = profile.artifact.files.sumOf { spec ->
            if (ModelIntegrityVerifier.verifyFile(File(targetDir, spec.name), spec)) 0L else spec.expectedSize
        }
        val requiredBytes = if (Long.MAX_VALUE - remainingBytes < MODEL_STORAGE_SAFETY_MARGIN_BYTES) {
            Long.MAX_VALUE
        } else {
            remainingBytes + MODEL_STORAGE_SAFETY_MARGIN_BYTES
        }
        if (availableSpace(filesDir) < requiredBytes) {
            throw IllegalStateException(ASR_MODEL_STORAGE_INSUFFICIENT)
        }
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

    private fun clearMarkerAfterFailure(targetDir: File) {
        runCatching { ModelIntegrityVerifier.clearMarker(targetDir) }
    }

    companion object {
        /** UI/readiness seam: only a marker-backed, exact hash/size installation is ready. */
        internal fun isInstalled(filesDir: File, profile: ModelProfile): Boolean =
            ModelIntegrityVerifier.isInstalled(filesDir, profile)

        private const val ASSET_ROOT = "asr"
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
