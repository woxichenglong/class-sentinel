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

        return when (profile.distribution) {
            ModelDistribution.Bundled -> SherpaModelInstaller(
                filesDir = filesDir,
                profile = profile,
                assetOpener = { assetPath ->
                    val fileName = assetPath.substringAfterLast('/')
                    openSourceFile(fileName, allowedNames, sourceRoot, sourcePrefix)
                },
            ).install()

            is ModelDistribution.Remote -> {
                val localSourceProfile = profile.copy(
                    distribution = ModelDistribution.Remote(
                        files = allowedNames.associateWith { it },
                    ),
                )
                RemoteModelInstaller(
                    filesDir = filesDir,
                    profile = localSourceProfile,
                    source = RemoteModelSource { location ->
                        openSourceFile(location, allowedNames, sourceRoot, sourcePrefix)
                    },
                ).install()
            }
        }
    }

    private fun openSourceFile(
        fileName: String,
        allowedNames: Set<String>,
        sourceRoot: File,
        sourcePrefix: String,
    ): FileInputStream {
        require(fileName in allowedNames) { "ASR_MODEL_FILE_NOT_ALLOWED" }
        val sourceFile = File(sourceRoot, fileName).canonicalFile
        require(sourceFile.path.startsWith(sourcePrefix)) { "ASR_MODEL_SOURCE_OUTSIDE_ROOT" }
        return FileInputStream(sourceFile)
    }
}
