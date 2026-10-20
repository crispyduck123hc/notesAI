package com.example.notesai.ui

import com.example.notesai.db.NoteEntity

/**
 * Which note the editor has open.
 *
 * A sealed type rather than a row id, because more than one kind of note exists: local notes (which
 * sync to Drive) and, from Phase 4, GitHub issues (which deliberately never do). Their row ids come
 * from different tables and are free to collide, so a bare `Long` selection could open an issue when
 * a local note with that id was clicked. Sealed also means adding a third kind later is a compile
 * error everywhere it matters, rather than a silently missing branch.
 */
sealed interface NoteRef {
    data class Local(
        val id: Long,
    ) : NoteRef
}

/**
 * What the editor renders, independent of where the text lives.
 *
 * Deliberately minimal: only what the editor uses today. GitHub issues will add a title, a completion
 * state and a toggle to this type in Phase 4, but adding them now — unused — would be guessing at the
 * shape rather than letting the button that needs them define it.
 */
data class NoteDocument(
    val ref: NoteRef,
    val content: String,
)

fun NoteEntity.toDocument(): NoteDocument =
    NoteDocument(
        ref = NoteRef.Local(id = id),
        content = content,
    )
