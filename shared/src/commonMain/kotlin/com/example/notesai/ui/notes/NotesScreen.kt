package com.example.notesai.ui.notes

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.notesai.auth.AccountInfo
import com.example.notesai.data.EntityType
import com.example.notesai.data.NoteRepository
import com.example.notesai.github.GitHubIssueRepository
import com.example.notesai.github.filteredBy
import com.example.notesai.model.Completion
import com.example.notesai.model.NoteDocument
import com.example.notesai.model.NoteRef
import com.example.notesai.model.buildNoteTree
import com.example.notesai.model.folderAndDescendants
import com.example.notesai.model.gitHubRepoNodeId
import com.example.notesai.model.toDocument
import com.example.notesai.ui.dialogs.ConflictUiItem
import com.example.notesai.ui.editor.NoteEditorPane
import com.example.notesai.ui.openInBrowser
import com.example.notesai.ui.tree.NoteTreePane
import com.example.notesai.update.AvailableUpdate

/**
 * Top-level screen: owns the selection/expansion state and wires the tree pane to the
 * editor pane. All presentation detail lives in the child composables.
 *
 * The GitHub folder is explicit: nothing happens to it until a button is pressed.
 */
@Composable
internal fun NotesScreen(
    repository: NoteRepository,
    account: AccountInfo,
    sync: SyncUiState,
    gitHubRepository: GitHubIssueRepository,
    /** Null when GitHub is not connected. */
    gitHubLogin: String?,
    /** Non-null when a newer release than the running one has been published. */
    availableUpdate: AvailableUpdate? = null,
    onOpenUpdate: (AvailableUpdate) -> Unit = {},
    onConnectGitHub: suspend () -> Unit,
    onRetrySync: () -> Unit,
    onResolveConflict: (id: Long, keepLocal: Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    val folders by repository.allFolders.collectAsState(initial = emptyList())
    val notes by repository.allNotes.collectAsState(initial = emptyList())
    val conflicts by repository.conflicts.collectAsState(initial = emptyList())
    val linkedRepos by gitHubRepository.linkedRepos.collectAsState(initial = emptyList())
    val gitHubIssues by gitHubRepository.allIssues.collectAsState(initial = emptyList())

    // Links survive a sign-out on purpose — they live in the database, so reconnecting does not mean
    // re-linking every repository. But while nobody is signed in there is nothing that can be done
    // with them, and an unclickable GitHub folder in the tree reads as a broken feature rather than a
    // signed-out one. Hidden, not deleted: signing in again brings them straight back.
    val gitHubRepos = if (gitHubLogin == null) emptyList() else linkedRepos

    val state = rememberNotesScreenState()
    // The tree is built from the filtered list; the editor and the GitHub pane keep using the whole mirror, so
    // hiding a row never closes the note that is open or changes what a push sends.
    val visibleGitHubIssues = remember(gitHubIssues, state.issueFilter) { gitHubIssues.filteredBy(state.issueFilter) }

    val tree =
        remember(folders, notes, state.expanded, gitHubRepos, visibleGitHubIssues) {
            buildNoteTree(folders, notes, state.expanded, gitHubRepos, visibleGitHubIssues)
        }
    // The row id the tree highlights, and the entity behind the open note. Both are derived from the
    // selection rather than stored, so a note edited or pulled elsewhere still shows its current text.
    val selectedNoteId = (state.selectedRef as? NoteRef.Local)?.id
    val selectedNote = notes.firstOrNull { it.id == selectedNoteId }
    val selectedRepo = gitHubRepos.firstOrNull { gitHubRepoNodeId(it.id) == state.selectedFolderId }

    /**
     * Local folder ids are positive and GitHub nodes are negative, so a negative selection must never
     * be used as a folder id — `New Note` would otherwise write a note into a folder that does not
     * exist.
     */
    val selectedLocalFolderId = state.selectedFolderId.takeIf { it > 0 } ?: NoteRepository.ROOT_FOLDER_ID

    val selectedDocument: NoteDocument? =
        when (val ref = state.selectedRef) {
            is NoteRef.Local -> notes.firstOrNull { it.id == ref.id }?.toDocument()
            is NoteRef.GitHubIssue ->
                gitHubIssues
                    .firstOrNull { it.repoId == ref.repoId && it.number == ref.number }
                    ?.toDocument()

            null -> null
        }

    /** Runs a GitHub action with uniform busy/error reporting. Returns a message worth showing. */
    val conflictItems =
        remember(conflicts) {
            conflicts.map { conflict ->
                val note =
                    if (conflict.entityType == EntityType.NOTE) {
                        repository.noteByUuid(conflict.entityUuid)
                    } else {
                        null
                    }
                val folder =
                    if (conflict.entityType == EntityType.FOLDER) {
                        repository.folderByUuid(conflict.entityUuid)
                    } else {
                        null
                    }
                ConflictUiItem(
                    id = conflict.id,
                    label = note?.title?.ifBlank { "Untitled note" } ?: folder?.name ?: "Record",
                    kind = conflict.kind,
                    localIsDeletion = note?.deletedAt != null,
                )
            }
        }

    Row(modifier = Modifier.fillMaxSize()) {
        NoteTreePane(
            tree = tree,
            selectedFolderId = state.selectedFolderId,
            selectedRef = state.selectedRef,
            accountEmail = account.email,
            sync = sync,
            conflictCount = conflictItems.size,
            availableUpdate = availableUpdate,
            onOpenUpdate = onOpenUpdate,
            gitHub =
                rememberGitHubPaneState(
                    state = state,
                    login = gitHubLogin,
                    repos = gitHubRepos,
                    selectedRepo = selectedRepo,
                    repository = gitHubRepository,
                    issueCounts =
                        IssueCounts(
                            open = gitHubIssues.count { it.state != "closed" },
                            closed = gitHubIssues.count { it.state == "closed" },
                        ),
                    filter = state.issueFilter,
                    busy = state.gitHubBusy,
                    onConnectGitHub = onConnectGitHub,
                ),
            onToggle = state::toggle,
            onSelectFolder = { state.selectedFolderId = it },
            // Each kind says exactly which folder context comes with it, so there is no row whose
            // selection has to be interpreted.
            onSelectNote = { note ->
                state.selectedRef = note.ref
                state.selectedFolderId = note.folderId
            },
            onSelectIssue = { issue ->
                state.selectedRef = issue.ref
                state.selectedFolderId = issue.parentNodeId
            },
            onDeleteFolder = { id ->
                // Clear selection if the open local note or active folder is being removed.
                val removed = folderAndDescendants(id, folders)
                if (state.selectedFolderId in removed) state.selectedFolderId = NoteRepository.ROOT_FOLDER_ID
                val openNote = notes.firstOrNull { it.id == selectedNoteId }
                if (openNote != null && openNote.folderId in removed) state.selectedRef = null
                repository.deleteFolder(id)
            },
            onDeleteNote = { id ->
                repository.deleteNote(id)
                if (selectedNoteId == id) state.selectedRef = null
            },
            onRenameFolder = { state.renamingFolderId = it },
            onMoveNote = { state.movingNoteId = it },
            onCreateNote = { state.selectedRef = NoteRef.Local(repository.addNote(folderId = selectedLocalFolderId)) },
            onCreateFolder = { state.showNewFolderDialog = true },
            onReviewConflicts = { state.showConflicts = true },
            onShowSyncError = { state.showSyncError = true },
            onSignOut = onSignOut,
        )

        VerticalDivider()

        NoteEditorPane(
            document = selectedDocument,
            onChange = { text ->
                when (val ref = state.selectedRef) {
                    is NoteRef.Local -> selectedNote?.let { repository.updateNote(it.id, text) }
                    is NoteRef.GitHubIssue ->
                        gitHubIssues
                            .firstOrNull { it.repoId == ref.repoId && it.number == ref.number }
                            ?.let { row ->
                                // The title belongs to GitHub and is not edited here, so it travels
                                // back unchanged; only the body is the user's typing.
                                gitHubRepository.editIssue(
                                    repoId = row.repoId,
                                    number = row.number,
                                    title = row.title,
                                    body = text,
                                )
                            }

                    null -> Unit
                }
            },
            onOpenOnGitHub =
                (state.selectedRef as? NoteRef.GitHubIssue)?.let { ref ->
                    gitHubRepos.firstOrNull { it.id == ref.repoId }?.let { repo ->
                        {
                            openInBrowser("https://github.com/${repo.owner}/${repo.name}/issues/${ref.number}")
                        }
                    }
                },
            onToggleComplete =
                (state.selectedRef as? NoteRef.GitHubIssue)?.let { ref ->
                    {
                        val currentlyClosed = selectedDocument?.completion == Completion.Closed
                        state.runGitHub {
                            gitHubRepository.setComplete(ref.repoId, ref.number, completed = !currentlyClosed)
                            if (currentlyClosed) "Reopened the issue on GitHub" else "Closed the issue on GitHub"
                        }
                    }
                },
        )
    }

    NotesScreenDialogs(
        state = state,
        repository = repository,
        gitHubRepository = gitHubRepository,
        conflictItems = conflictItems,
        sync = sync,
        unsavedIssuesFor = { repoId -> gitHubIssues.count { it.repoId == repoId && it.dirty == 1L } },
        onRetrySync = onRetrySync,
        onResolveConflict = onResolveConflict,
    )
}
