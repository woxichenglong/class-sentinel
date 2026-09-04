package com.classsentinel.core.speech

import java.io.File

/** Debug-only request parsed by the ADB import bridge. */
internal data class DebugModelImportRequest(
    val profile: ModelProfile,
    val sourceDirectory: File,
)

/**
 * Accept only a daily allowlisted profile and an absolute source directory.
 * The importer performs the actual file allowlist, canonical-root, size, and hash checks.
 */
internal fun parseDebugModelImportRequest(
    profileId: String?,
    sourcePath: String?,
): DebugModelImportRequest {
    val profile = ModelProfiles.DAILY_SELECTABLE.firstOrNull { it.id == profileId }
        ?: throw IllegalArgumentException("UNKNOWN_LOCAL_ASR_MODEL")
    val path = sourcePath?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("ASR_MODEL_SOURCE_PATH_INVALID")
    val sourceDirectory = File(path)
    require(sourceDirectory.isAbsolute) { "ASR_MODEL_SOURCE_PATH_INVALID" }
    return DebugModelImportRequest(profile, sourceDirectory)
}
