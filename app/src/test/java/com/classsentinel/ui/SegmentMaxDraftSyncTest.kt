package com.classsentinel.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression contract for the "分段最长时长" draft sync in SettingsScreen.
 *
 * Guards the latent defect at SettingsScreen.kt:270, where `seg` is remembered
 * without a key and initialized from `segmentMax` — whose first frame is the
 * default 4s while the persisted value (e.g. 8s) arrives asynchronously later.
 * The UI draft must converge to the latest persisted value when the user has
 * not edited, yet must never clobber the user's own in-progress edit.
 *
 * The production helper [syncSegmentMaxDraft] does NOT exist yet: this test is
 * the RED side of the contract. When implemented it should be a pure top-level
 * function in package com.classsentinel.ui (mirroring SummaryUiState.kt),
 * taking only plain values — no DataStore, no state holder.
 */
class SegmentMaxDraftSyncTest {

    @Test
    fun `syncs to latest persisted value while user has not edited`() {
        // First frame default 4 → persisted value arrives late as 8.
        assertEquals(
            8f,
            syncSegmentMaxDraft(currentDraft = 4f, persistedSeconds = 8, userEdited = false),
        )
    }

    @Test
    fun `preserves user draft when user has edited`() {
        // User has already moved the slider to 8; stale persisted 4 must not win.
        assertEquals(
            8f,
            syncSegmentMaxDraft(currentDraft = 8f, persistedSeconds = 4, userEdited = true),
        )
    }

    @Test
    fun `keeps current draft when persisted value is unchanged`() {
        assertEquals(
            4f,
            syncSegmentMaxDraft(currentDraft = 4f, persistedSeconds = 4, userEdited = false),
        )
    }
}
