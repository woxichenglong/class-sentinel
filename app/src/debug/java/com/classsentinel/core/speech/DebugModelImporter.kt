package com.classsentinel.core.speech

import java.io.File
import java.io.FileInputStream

/**
 * Debug-only bridge for importing an already extracted model directory.
 *
 * This file intentionally lives in the debug source set. Release code cannot import external
 * model files through this API. The shared installer still owns the profile allowlist, integrity
 * checks, marker, atomic per-file replacement, and app-private destination boundary.
 */
internal class DebugModelImporter(
    private val filesDir: File,
) {

    fun importFromDirectory(profile: ModelProfile, sourceDirectory: File): File {
        val sourceRoot = sourceDirectory.canonicalFile
        require(sourceRoot.isDirectory) { "ASR_MODEL_SOURCE_NOT_DIRECTORY" }
        val sourcePrefix = sourceRoot.path + File.separator
        val allowedNames = profile.artifact.files.map { it.name }.toSet()

        return SherpaModelInstaller(
            filesDir = filesDir,
            profile = profile,
            assetOpener = { assetPath ->
                val fileName = assetPath.substringAfterLast('/')
                require(fileName in allowedNames) { "ASR_MODEL_FILE_NOT_ALLOWED" }
                val sourceFile = File(sourceRoot, fileName).canonicalFile
                require(sourceFile.path.startsWith(sourcePrefix)) { "ASR_MODEL_SOURCE_OUTSIDE_ROOT" }
                FileInputStream(sourceFile)
            },
        ).install()
    }
}
