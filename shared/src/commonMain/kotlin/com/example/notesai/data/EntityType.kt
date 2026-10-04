package com.example.notesai.data

/**
 * Entity discriminators used by the outbox, the baseline table and the remote
 * projection. Kept here (rather than next to the JSON DTOs) because they describe
 * local entities first and only happen to be serialised.
 */
object EntityType {
    const val NOTE = "note"
    const val FOLDER = "folder"
}
