package com.example.notesai.data

/**
 * The derived note title: the first line of the note body, trimmed.
 *
 * Shared by [NoteRepository] (which caches it in the `title` column) and the UI
 * (which labels tree rows), so both always agree with the editor's first line.
 */
fun String.noteTitle(): String =
    lineSequence().firstOrNull().orEmpty().trim()
