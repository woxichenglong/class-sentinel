package com.classsentinel.core.speech

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Copies the pinned model from APK assets into the app-private ASR directory. */
internal class SherpaModelInstaller(
    private val filesDir: File,
    private val profile: ModelProfile = ModelProfiles.ZIPFORMER_ZH_14M,
    private val assetOpener: (String) -> InputStream,
) {
    @Synchronized
    fun install(): File {
        val asrRoot = File(filesDir, "asr").canonicalFile
        val targetDir = File(asrRoot, profile.artifact.directory).canonicalFile
        val rootPrefix = asrRoot.path + File.separator
        require(targetDir.path.startsWith(rootPrefix)) { "ASR_MODEL_PATH_OUTSIDE_ROOT" }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }
        if (!targetDir.isDirectory) {
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }

        if (hasValidInstallation(targetDir)) return targetDir

        for (spec in profile.artifact.files) {
            val destination = File(targetDir, spec.name)
            if (isValidFile(destination, spec)) continue

            val temporary = File(targetDir, ".${spec.name}.tmp")
            temporary.delete()
            try {
                assetOpener("$ASSET_ROOT/${profile.artifact.directory}/${spec.name}").use { input ->
                    copyToTemporary(input, temporary)
                }
                if (!isValidFile(temporary, spec)) {
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
        return targetDir
    }

    private fun hasValidInstallation(targetDir: File): Boolean {
        val marker = File(targetDir, MODEL_MARKER_FILE)
        if (!marker.isFile || runCatching { marker.readText() }.getOrNull() != markerContent()) {
            return false
        }
        return profile.artifact.files.all { isValidFile(File(targetDir, it.name), it) }
    }

    private fun isValidFile(file: File, spec: ModelFileSpec): Boolean =
        file.isFile && file.length() == spec.expectedSize &&
            runCatching { file.sha256() == spec.sha256 }.getOrDefault(false)

    private fun writeMarker(targetDir: File) {
        val marker = File(targetDir, MODEL_MARKER_FILE)
        val temporary = File(targetDir, ".$MODEL_MARKER_FILE.tmp")
        try {
            temporary.writeText(markerContent())
            if (!temporary.renameTo(marker)) {
                temporary.copyTo(marker, overwrite = true)
                temporary.delete()
            }
        } catch (_: Exception) {
            temporary.delete()
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }
    }

    private fun markerContent(): String = "${profile.id}\n${profile.version}\n"

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
        internal fun isInstalled(filesDir: File, profile: ModelProfile): Boolean = runCatching {
            val asrRoot = File(filesDir, "asr").canonicalFile
            val targetDir = File(asrRoot, profile.artifact.directory).canonicalFile
            val rootPrefix = asrRoot.path + File.separator
            if (!targetDir.path.startsWith(rootPrefix)) return@runCatching false

            val marker = File(targetDir, MODEL_MARKER_FILE)
            marker.isFile && marker.readText() == "${profile.id}\n${profile.version}\n" &&
                profile.artifact.files.all { spec ->
                    val file = File(targetDir, spec.name)
                    file.isFile && file.length() == spec.expectedSize && sha256(file) == spec.sha256
                }
        }.getOrDefault(false)

        /** Compatibility alias for UI callers; the profile remains the single source of truth. */
        val DEFAULT_MODEL_PATH: String
            get() = ModelProfiles.ZIPFORMER_ZH_14M.artifact.directory
        private const val ASSET_ROOT = "asr"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val HASH_BUFFER_SIZE = 64 * 1024
        private const val MODEL_MARKER_FILE = ".model-profile"

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
    }
}
