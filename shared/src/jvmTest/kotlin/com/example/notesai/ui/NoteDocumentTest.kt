package com.example.notesai.ui

import com.example.notesai.db.NoteEntity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The seam itself: a stored note becomes an editor document, and its identity travels as a
 * [NoteRef] rather than a bare row id.
 *
 * The Compose side of the refactor cannot be covered here — there is no UI test harness in this
 * project yet — but the mapping that everything else depends on can be.
 */
class NoteDocumentTest {
    private fun note(
        id: Long = 7,
        content: String = "first line\nbody",
    ) = NoteEntity(
        id = id,
        title = "first line",
        content = content,
        createdAt = 1_000,
        folderId = 1,
        uuid = "11111111-1111-1111-1111-111111111111",
        updatedAt = 2_000,
        deletedAt = null,
        conflictOf = null,
    )

    @Test
    fun aLocalNoteBecomesADocumentKeyedByItsRowId() {
        val document = note().toDocument()

        assertEquals(NoteRef.Local(7), document.ref)
        assertEquals("first line\nbody", document.content)
    }

    @Test
    fun twoNotesDoNotShareADocumentIdentity() {
        // This is the property the editor's `remember(ref)` and flush-on-dispose depend on: a
        // different note must be a different key, or the previous note's text is written into it.
        assertEquals(false, note(id = 1).toDocument().ref == note(id = 2).toDocument().ref)
        assertEquals(true, note(id = 1).toDocument().ref == note(id = 1).toDocument().ref)
    }
}
