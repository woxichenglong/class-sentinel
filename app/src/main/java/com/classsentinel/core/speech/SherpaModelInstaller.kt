package com.classsentinel.core.speech

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Copies the pinned model from APK assets into the app-private ASR directory. */
internal class SherpaModelInstaller(
    private val filesDir: File,
    private val relativeModelPath: String = DEFAULT_MODEL_PATH,
    private val assetOpener: (String) -> InputStream,
) {
    fun install(): File {
        val asrRoot = File(filesDir, "asr").canonicalFile
        val targetDir = File(asrRoot, relativeModelPath).canonicalFile
        val rootPrefix = asrRoot.path + File.separator
        require(targetDir.path.startsWith(rootPrefix)) { "ASR_MODEL_PATH_OUTSIDE_ROOT" }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }
        if (!targetDir.isDirectory) {
            throw IllegalStateException("ASR_MODEL_INSTALL_FAILED")
        }

        for (fileName in ALLOWED_FILES) {
            val destination = File(targetDir, fileName)
            val temporary = File(targetDir, ".$fileName.tmp")
            temporary.delete()
            try {
                assetOpener("$ASSET_ROOT/$relativeModelPath/$fileName").use { input ->
                    copyToTemporary(input, temporary)
                }
                val size = temporary.length()
                if (size <= 0L) {
                    throw IllegalStateException("ASR_MODEL_MISSING")
                }
                if (destination.isFile && destination.length() > 0L && destination.length() == size) {
                    temporary.delete()
                    continue
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
        return targetDir
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
        const val DEFAULT_MODEL_PATH = "zipformer-zh-14M-2023-02-23"
        private const val ASSET_ROOT = "asr"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private val ALLOWED_FILES = setOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt",
        )
    }
}
