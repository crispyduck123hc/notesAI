package com.example.notesai.data

/**
 * A deleted record that is old enough to be forgotten, plus the remote file that has to
 * disappear with it.
 */
data class Tombstone(
    val entityType: String,
    val uuid: String,
    val remoteFileId: String?,
)
