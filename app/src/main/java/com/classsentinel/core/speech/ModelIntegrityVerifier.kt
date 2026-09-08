package com.classsentinel.core.speech

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Shared model marker and file-integrity boundary for every model installer/readiness check. */
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

/** Legacy full-response seam retained for local/debug sources and existing callers. */
internal fun interface RemoteModelSource {
    fun open(location: String): InputStream
}

/** Downloads a remote profile through an injected source and atomically installs its files. */
internal class RemoteModelInstaller private constructor(
    private val filesDir: File,
    private val profile: ModelProfile,
    private val legacySource: RemoteModelSource?,
    private val resumableSource: ResumableRemoteModelSource?,
) {
    constructor(
        filesDir: File,
        profile: ModelProfile,
        source: RemoteModelSource,
    ) : this(filesDir, profile, source, null)

    constructor(
        filesDir: File,
        profile: ModelProfile,
        source: ResumableRemoteModelSource,
    ) : this(filesDir, profile, null, source)

    private val distribution: ModelDistribution.Remote =
        profile.distribution as? ModelDistribution.Remote
            ?: throw IllegalArgumentException("ASR_MODEL_DISTRIBUTION_NOT_REMOTE")

    @Synchronized
    fun install(
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onVerifying: () -> Unit = {},
    ): File {
        val targetDir = ModelIntegrityVerifier.resolveTargetDirectory(filesDir, profile)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }
        if (!targetDir.isDirectory) {
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }
        if (ModelIntegrityVerifier.verify(profile, targetDir)) return targetDir

        ModelIntegrityVerifier.clearMarker(targetDir)
        val totalBytes = profile.artifact.files.sumOf { it.expectedSize }
        reportProgress(targetDir, totalBytes, onProgress)
        try {
            for (spec in profile.artifact.files) {
                val destination = File(targetDir, spec.name)
                if (ModelIntegrityVerifier.verifyFile(destination, spec)) continue

                val location = distribution.files[spec.name]
                    ?: throw ModelDownloadException(
                        reason = ModelDownloadFailureReason.REMOTE_SOURCE_MISSING,
                        retryable = false,
                        message = "ASR_MODEL_REMOTE_SOURCE_MISSING",
                    )
                val temporary = File(targetDir, "${spec.name}.part")
                if (temporary.exists() && temporary.length() > spec.expectedSize) {
                    if (!temporary.delete()) {
                        throw ModelDownloadException(
                            reason = ModelDownloadFailureReason.INSTALL,
                            retryable = false,
                            message = "ASR_MODEL_INSTALL_FAILED",
                        )
                    }
                }
                if (temporary.isFile && temporary.length() == spec.expectedSize) {
                    if (ModelIntegrityVerifier.verifyFile(temporary, spec)) {
                        promote(temporary, destination)
                        reportProgress(targetDir, totalBytes, onProgress)
                        continue
                    }
                    if (!temporary.delete()) {
                        throw ModelDownloadException(
                            reason = ModelDownloadFailureReason.INSTALL,
                            retryable = false,
                            message = "ASR_MODEL_INSTALL_FAILED",
                        )
                    }
                }
                val offset = if (temporary.isFile) temporary.length() else 0L
                val chunk = openChunk(location, offset, spec)
                validateChunk(chunk, offset, spec.expectedSize)

                try {
                    chunk.use {
                        copyChunk(
                            input = it.body,
                            destination = temporary,
                            append = it.disposition == RemoteDownloadDisposition.APPEND,
                            expectedSize = spec.expectedSize,
                            targetDir = targetDir,
                            totalBytes = totalBytes,
                            onProgress = onProgress,
                        )
                    }
                } catch (error: ModelDownloadException) {
                    if (error.reason == ModelDownloadFailureReason.RESPONSE_TOO_LARGE) {
                        temporary.delete()
                    }
                    throw error
                } catch (error: IOException) {
                    throw ModelDownloadException(
                        reason = ModelDownloadFailureReason.NETWORK,
                        retryable = true,
                        message = "ASR_MODEL_REMOTE_SOURCE_FAILED",
                        cause = error,
                    )
                }

                if (!ModelIntegrityVerifier.verifyFile(temporary, spec)) {
                    temporary.delete()
                    throw ModelDownloadException(
                        reason = ModelDownloadFailureReason.INTEGRITY,
                        retryable = false,
                        message = "ASR_MODEL_INTEGRITY",
                    )
                }

                promote(temporary, destination)
                reportProgress(targetDir, totalBytes, onProgress)
            }

            runCatching { onVerifying() }
            if (!ModelIntegrityVerifier.verifyFiles(profile, targetDir)) {
                throw ModelDownloadException(
                    reason = ModelDownloadFailureReason.INTEGRITY,
                    retryable = false,
                    message = "ASR_MODEL_INTEGRITY",
                )
            }
            ModelIntegrityVerifier.writeMarker(profile, targetDir)
            if (!ModelIntegrityVerifier.verify(profile, targetDir)) {
                ModelIntegrityVerifier.clearMarker(targetDir)
                throw ModelDownloadException(
                    reason = ModelDownloadFailureReason.INTEGRITY,
                    retryable = false,
                    message = "ASR_MODEL_INTEGRITY",
                )
            }
            ModelReadinessChecker.invalidate(filesDir, profile)
            return targetDir
        } catch (error: ModelDownloadException) {
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

    private fun openChunk(
        location: String,
        offset: Long,
        spec: ModelFileSpec,
    ): RemoteDownloadChunk {
        try {
            resumableSource?.let { return it.open(location, offset, spec.expectedSize) }
            val source = legacySource ?: throw ModelDownloadException(
                reason = ModelDownloadFailureReason.REMOTE_SOURCE_MISSING,
                retryable = false,
                message = "ASR_MODEL_REMOTE_SOURCE_MISSING",
            )
            return RemoteDownloadChunk(
                disposition = RemoteDownloadDisposition.REPLACE,
                startOffset = 0L,
                totalBytes = null,
                body = source.open(location),
            )
        } catch (error: ModelDownloadException) {
            throw error
        } catch (error: IOException) {
            throw ModelDownloadException(
                reason = ModelDownloadFailureReason.NETWORK,
                retryable = true,
                message = "ASR_MODEL_REMOTE_SOURCE_FAILED",
                cause = error,
            )
        } catch (error: Exception) {
            throw ModelDownloadException(
                reason = ModelDownloadFailureReason.NETWORK,
                retryable = true,
                message = "ASR_MODEL_REMOTE_SOURCE_FAILED",
                cause = error,
            )
        }
    }

    private fun validateChunk(chunk: RemoteDownloadChunk, offset: Long, expectedSize: Long) {
        if (chunk.startOffset < 0L || chunk.startOffset > expectedSize) {
            chunk.close()
            throw ModelDownloadException(
                reason = ModelDownloadFailureReason.RANGE_INVALID,
                retryable = false,
                message = "ASR_MODEL_RANGE_INVALID",
            )
        }
        if (chunk.totalBytes != null && chunk.totalBytes != expectedSize) {
            chunk.close()
            throw ModelDownloadException(
                reason = ModelDownloadFailureReason.RANGE_INVALID,
                retryable = false,
                message = "ASR_MODEL_RANGE_INVALID",
            )
        }
        val validDisposition = when (chunk.disposition) {
            RemoteDownloadDisposition.APPEND -> chunk.startOffset == offset
            RemoteDownloadDisposition.REPLACE -> chunk.startOffset == 0L
        }
        if (!validDisposition) {
            chunk.close()
            throw ModelDownloadException(
                reason = ModelDownloadFailureReason.RANGE_INVALID,
                retryable = false,
                message = "ASR_MODEL_RANGE_INVALID",
            )
        }
    }

    private fun copyChunk(
        input: InputStream,
        destination: File,
        append: Boolean,
        expectedSize: Long,
        targetDir: File,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        FileOutputStream(destination, append).use { output ->
            var size = if (append) destination.length() else 0L
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (size + count > expectedSize) {
                    throw ModelDownloadException(
                        reason = ModelDownloadFailureReason.RESPONSE_TOO_LARGE,
                        retryable = false,
                        message = "ASR_MODEL_PART_TOO_LARGE",
                    )
                }
                output.write(buffer, 0, count)
                size += count
                reportProgress(targetDir, totalBytes, onProgress)
            }
            output.flush()
        }
    }

    private fun promote(temporary: File, destination: File) {
        try {
            ModelIntegrityVerifier.atomicReplace(temporary, destination)
        } catch (_: Exception) {
            temporary.delete()
            throw ModelDownloadException(
                reason = ModelDownloadFailureReason.INSTALL,
                retryable = false,
                message = "ASR_MODEL_INSTALL_FAILED",
            )
        }
    }

    private fun reportProgress(
        targetDir: File,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val downloaded = profile.artifact.files.sumOf { spec ->
            val formal = File(targetDir, spec.name)
            val part = File(targetDir, "${spec.name}.part")
            when {
                formal.isFile -> formal.length().coerceAtMost(spec.expectedSize)
                part.isFile -> part.length().coerceAtMost(spec.expectedSize)
                else -> 0L
            }
        }
        runCatching { onProgress(downloaded, totalBytes) }
    }

    private fun clearMarkerAfterFailure(targetDir: File) {
        runCatching { ModelIntegrityVerifier.clearMarker(targetDir) }
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
