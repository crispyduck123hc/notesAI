package com.example.notesai.ui

import com.example.notesai.db.GitHubIssueNoteEntity
import com.example.notesai.db.NoteEntity

/**
 * Which note the editor has open.
 *
 * A sealed type rather than a row id, because there is more than one kind of note: local ones (which
 * sync to Drive) and GitHub issues (which deliberately never do). Their row ids come from different
 * tables and are free to collide, so a bare `Long` selection could open an issue when a local note
 * with that id was clicked.
 */
sealed interface NoteRef {
    data class Local(
        val id: Long,
    ) : NoteRef

    data class GitHubIssue(
        val repoId: Long,
        val number: Long,
    ) : NoteRef
}

/** Whether an issue is done. Null for local notes, which have no such notion. */
enum class Completion { Open, Closed }

/**
 * What the editor renders, independent of where the note lives.
 *
 * [completion] being non-null is also what tells the editor it is looking at something with external
 * state: it shows [title] as a header and offers the complete/reopen action.
 */
data class NoteDocument(
    val ref: NoteRef,
    val title: String,
    val content: String,
    val completion: Completion? = null,
)

fun NoteEntity.toDocument(): NoteDocument =
    NoteDocument(
        ref = NoteRef.Local(id = id),
        title = title,
        content = content,
    )

/** An issue's body is editable here; its title and state belong to GitHub. */
fun GitHubIssueNoteEntity.toDocument(): NoteDocument =
    NoteDocument(
        ref = NoteRef.GitHubIssue(repoId = repoId, number = number),
        title = title,
        content = body,
        completion = if (state == "closed") Completion.Closed else Completion.Open,
    )
