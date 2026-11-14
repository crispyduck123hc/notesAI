package com.example.notesai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingEditTest {
    @Test
    fun committingRunsTheWriteTheEditorOffered() {
        val pendingEdit = PendingEdit()
        val writes = mutableListOf<String>()

        pendingEdit.offer { writes += "first" }
        pendingEdit.commit()

        assertEquals(listOf("first"), writes)
    }

    @Test
    fun thereIsNothingToCommitBeforeAnEditorExisted() {
        // A window that is closed before anything was ever opened must not crash the quit path.
        val pendingEdit = PendingEdit()

        pendingEdit.commit()

        assertTrue(true, "committing with nothing offered is a no-op")
    }

    @Test
    fun aWithdrawnEditorIsNeverWrittenAgain() {
        // The editor withdraws when it is disposed, so a quit that happens afterwards cannot write into a closed
        // repository.
        val pendingEdit = PendingEdit()
        val writes = mutableListOf<String>()

        pendingEdit.offer { writes += "one" }
        pendingEdit.withdraw()
        pendingEdit.commit()

        assertEquals(emptyList(), writes)
    }

    @Test
    fun theLatestEditorIsTheOneCommitting() {
        // Switching notes offers a new flush for the new document; the old one must not linger.
        val pendingEdit = PendingEdit()
        val writes = mutableListOf<String>()

        pendingEdit.offer { writes += "old note" }
        pendingEdit.offer { writes += "new note" }
        pendingEdit.commit()

        assertEquals(listOf("new note"), writes)
    }
}
