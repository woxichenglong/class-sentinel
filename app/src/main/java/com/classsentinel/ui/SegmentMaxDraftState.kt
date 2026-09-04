package com.classsentinel.ui

/**
 * Keeps a settings slider aligned with its persisted value without overwriting
 * an in-progress user edit.
 */
internal fun syncSegmentMaxDraft(
    currentDraft: Float,
    persistedSeconds: Int,
    userEdited: Boolean,
): Float = if (userEdited) currentDraft else persistedSeconds.toFloat()
