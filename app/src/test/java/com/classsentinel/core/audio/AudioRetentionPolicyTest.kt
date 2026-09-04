package com.classsentinel.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRetentionPolicyTest {

    @Test
    fun `missing or unknown stored value uses failed-only default`() {
        assertEquals(AudioRetentionPolicy.FAILED_ONLY, AudioRetentionPolicy.fromStored(null))
        assertEquals(AudioRetentionPolicy.FAILED_ONLY, AudioRetentionPolicy.fromStored(""))
        assertEquals(AudioRetentionPolicy.FAILED_ONLY, AudioRetentionPolicy.fromStored("not-a-policy"))
        assertEquals(AudioRetentionPolicy.FULL_SESSION, AudioRetentionPolicy.fromStored("full_session"))
    }

    @Test
    fun `all policies except none retain an untranscribed segment for recovery`() {
        assertTrue(AudioRetentionPolicy.FAILED_ONLY.shouldRetainFailedSegment())
        assertTrue(AudioRetentionPolicy.MARKED_ONLY.shouldRetainFailedSegment())
        assertTrue(AudioRetentionPolicy.FULL_SESSION.shouldRetainFailedSegment())
        assertFalse(AudioRetentionPolicy.NONE.shouldRetainFailedSegment())
    }

    @Test
    fun `successful segment retention follows the selected policy`() {
        assertFalse(AudioRetentionPolicy.FAILED_ONLY.shouldRetainAfterSuccessfulTranscription(marked = false))
        assertFalse(AudioRetentionPolicy.FAILED_ONLY.shouldRetainAfterSuccessfulTranscription(marked = true))
        assertFalse(AudioRetentionPolicy.MARKED_ONLY.shouldRetainAfterSuccessfulTranscription(marked = false))
        assertTrue(AudioRetentionPolicy.MARKED_ONLY.shouldRetainAfterSuccessfulTranscription(marked = true))
        assertTrue(AudioRetentionPolicy.FULL_SESSION.shouldRetainAfterSuccessfulTranscription(marked = false))
        assertTrue(AudioRetentionPolicy.FULL_SESSION.shouldRetainAfterSuccessfulTranscription(marked = true))
        assertFalse(AudioRetentionPolicy.NONE.shouldRetainAfterSuccessfulTranscription(marked = true))
    }

    @Test
    fun `marked and full policies capture successful segments as retention candidates`() {
        assertFalse(AudioRetentionPolicy.FAILED_ONLY.shouldCaptureSuccessfulSegments())
        assertTrue(AudioRetentionPolicy.MARKED_ONLY.shouldCaptureSuccessfulSegments())
        assertTrue(AudioRetentionPolicy.FULL_SESSION.shouldCaptureSuccessfulSegments())
        assertFalse(AudioRetentionPolicy.NONE.shouldCaptureSuccessfulSegments())
    }

    @Test
    fun `full session exposes an explicit warning`() {
        assertTrue(AudioRetentionPolicy.FULL_SESSION.warningText()?.isNotBlank() == true)
        assertEquals(null, AudioRetentionPolicy.FAILED_ONLY.warningText())
        assertEquals(null, AudioRetentionPolicy.MARKED_ONLY.warningText())
        assertEquals(null, AudioRetentionPolicy.NONE.warningText())
    }

    @Test
    fun `estimate uses 16khz mono pcm16 storage rate and saturates`() {
        assertEquals(0L, AudioRetentionPolicy.estimateBytes(-1L))
        assertEquals(1_920_000L, AudioRetentionPolicy.estimateBytes(60_000L))
        assertEquals(Long.MAX_VALUE, AudioRetentionPolicy.estimateBytes(Long.MAX_VALUE))
    }
}
