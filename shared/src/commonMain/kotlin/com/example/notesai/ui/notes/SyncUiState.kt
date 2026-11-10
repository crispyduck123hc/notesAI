package com.example.notesai.ui.notes

import androidx.compose.runtime.Immutable

/**
 * What the sidebar should say about syncing.
 *
 * One value instead of three parameters (`syncStatus`, `syncError`, `syncing`) that could contradict each other —
 * "syncing" while also carrying an error, or a summary with no state behind it. The producer already computes exactly
 * these cases, so this is a translation rather than a redesign.
 */
@Immutable
internal sealed interface SyncUiState {
    /** Nothing has been attempted yet in this session. */
    data object Idle : SyncUiState

    /** A sync pass is in flight. */
    data object Running : SyncUiState

    /** A pass finished; [summary] is the sentence for the sidebar. */
    data class Done(
        val summary: String,
    ) : SyncUiState

    /**
     * A pass failed.
     *
     * [summary] is the one line the sidebar shows; [detail] is what "what went wrong?" opens, and it is null when the
     * message is already the whole story (a reauthorisation warning, for instance).
     */
    data class Failed(
        val summary: String,
        val detail: String? = null,
    ) : SyncUiState
}
