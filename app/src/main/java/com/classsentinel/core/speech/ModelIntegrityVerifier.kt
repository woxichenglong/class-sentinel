package com.classsentinel.core.speech

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Shared model marker and file-integrity boundary for the bundled production model. */
internal object ModelIntegrityVerifier {
    internal const val MARKER_FILE_NAME = ".model-profile"

    fun verify(profile: ModelProfile, directory: File): Boolean = runCatching {
        val targetDir = directory.canonicalFile
        if (!targetDir.isDirectory) return@runCatching false

        val marker = File(targetDir, MARKER_FILE_NAME)
        marker.isFile &&
            marker.readText(Charsets.UTF_8) == markerContent(profile) &&
            verifyFiles(profile, targetDir)
    }.getOrDefault(false)

    internal fun isInstalled(filesDir: File, profile: ModelProfile): Boolean = runCatching {
        verify(profile, resolveTargetDirectory(filesDir, profile))
    }.getOrDefault(false)

    internal fun resolveTargetDirectory(filesDir: File, profile: ModelProfile): File {
        val asrRoot = File(filesDir, "asr").canonicalFile
        val targetDir = File(asrRoot, profile.artifact.directory).canonicalFile
        val rootPrefix = asrRoot.path + File.separator
        require(targetDir.path.startsWith(rootPrefix)) { "ASR_MODEL_PATH_OUTSIDE_ROOT" }
        return targetDir
    }

    internal fun verifyFile(file: File, spec: ModelFileSpec): Boolean =
        file.isFile && file.length() == spec.expectedSize &&
            runCatching { sha256(file) == spec.sha256 }.getOrDefault(false)

    internal fun verifyFiles(profile: ModelProfile, directory: File): Boolean = runCatching {
        val targetDir = directory.canonicalFile
        targetDir.isDirectory && profile.artifact.files.all { spec ->
            verifyFile(File(targetDir, spec.name), spec)
        }
    }.getOrDefault(false)

    internal fun writeMarker(profile: ModelProfile, directory: File) {
        val marker = File(directory, MARKER_FILE_NAME)
        val temporary = File(directory, ".$MARKER_FILE_NAME.part")
        try {
            temporary.writeText(markerContent(profile), Charsets.UTF_8)
            atomicReplace(temporary, marker)
        } catch (_: Exception) {
            temporary.delete()
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }
    }

    internal fun clearMarker(directory: File) {
        val marker = File(directory, MARKER_FILE_NAME)
        if (marker.exists() && !marker.delete()) {
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }
    }

    internal fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    internal fun markerContent(profile: ModelProfile): String =
        "${profile.id}\n${profile.version}\n"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val HASH_BUFFER_SIZE = 64 * 1024
}
