package com.classsentinel.core.speech

import java.io.InputStream

/** Shared local-listening preflight used by every START entry point. */
internal class LocalListenStartPreflight(
    private val readinessChecker: ModelReadinessChecker,
    private val assetOpener: (String) -> InputStream,
) {

    suspend fun isReady(profile: ModelProfile): Boolean =
        readinessChecker.isReady(profile)

    suspend fun ensureReady(profile: ModelProfile): Boolean =
        readinessChecker.ensureReady(profile, assetOpener)
}
