package com.example.notesai.ui.notes

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.example.notesai.data.NoteRepository
import com.example.notesai.github.GitHubIssueRepository
import com.example.notesai.model.NoteRef
import com.example.notesai.model.gitHubRepoNodeId
import com.example.notesai.ui.dialogs.ConflictDialog
import com.example.notesai.ui.dialogs.ConflictUiItem
import com.example.notesai.ui.dialogs.DateRangeDialog
import com.example.notesai.ui.dialogs.FolderNameDialog
import com.example.notesai.ui.dialogs.LinkRepoDialog
import com.example.notesai.ui.dialogs.MessageDialog
import com.example.notesai.ui.dialogs.MoveNoteDialog
import com.example.notesai.ui.dialogs.SyncErrorDialog
import com.example.notesai.ui.dialogs.TextPromptDialog

/**
 * Every dialog the notes screen can open, in one place.
 *
 * Split out of the screen for two reasons: the screen is about layout, and these are about editing one thing at a
 * time. A redesign that changes where a control lives does not have to read them, and a dialog that changes does not
 * have to be found among the panes.
 *
 * Each block is the same shape — `state.someTarget != null` decides whether it is open, and the dialog's own
 * callbacks clear that target — so the whole file reads as a list of "when this is set, ask about it".
 */
@Composable
internal fun NotesScreenDialogs(
    state: NotesScreenState,
    repository: NoteRepository,
    gitHubRepository: GitHubIssueRepository,
    conflictItems: List<ConflictUiItem>,
    syncError: String?,
    /** Unsaved issues in a repository, for the force-pull warning. */
    unsavedIssuesFor: (repoId: Long) -> Int,
    onRetrySync: () -> Unit,
    onResolveConflict: (id: Long, keepLocal: Boolean) -> Unit,
) {
    if (state.showDateRange) {
        DateRangeDialog(
            from = state.issueFilter.createdFrom,
            to = state.issueFilter.createdTo,
            onDismiss = { state.showDateRange = false },
            onApply = { from, to ->
                state.issueFilter = state.issueFilter.createdBetween(from, to)
                state.showDateRange = false
            },
        )
    }

    // Where "new folder" puts the folder: the selected folder, or the root when a note or a repository node is
    // selected (a repository node's negative id is not a folder).
    val selectedLocalFolderId =
        state.selectedFolderId.takeIf { it > 0 } ?: NoteRepository.ROOT_FOLDER_ID

    if (state.showNewFolderDialog) {
        FolderNameDialog(
            title = "New folder",
            confirmLabel = "Create",
            initialValue = "",
            onDismiss = { state.showNewFolderDialog = false },
            onConfirm = { name ->
                repository.addFolder(name = name, parentId = selectedLocalFolderId)
                state.expanded = state.expanded + selectedLocalFolderId
                state.showNewFolderDialog = false
            },
        )
    }

    val folders = repository.allFoldersNow()
    val renamingFolder = folders.firstOrNull { it.id == state.renamingFolderId }
    if (renamingFolder != null) {
        FolderNameDialog(
            title = "Rename folder",
            confirmLabel = "Rename",
            initialValue = renamingFolder.name,
            onDismiss = { state.renamingFolderId = null },
            onConfirm = { name ->
                repository.renameFolder(renamingFolder.id, name)
                state.renamingFolderId = null
            },
        )
    }

    val movingNote = repository.allNotesNow().firstOrNull { it.id == state.movingNoteId }
    if (movingNote != null) {
        MoveNoteDialog(
            folders = folders,
            currentFolderId = movingNote.folderId,
            onDismiss = { state.movingNoteId = null },
            onSelect = { folderId ->
                repository.moveNote(movingNote.id, folderId)
                state.movingNoteId = null
            },
        )
    }

    if (state.showConflicts && conflictItems.isNotEmpty()) {
        ConflictDialog(
            items = conflictItems,
            onResolve = { id, keepLocal -> onResolveConflict(id, keepLocal) },
            onDismiss = { state.showConflicts = false },
        )
    }

    val problem = syncError
    if (state.showSyncError && problem != null) {
        SyncErrorDialog(
            message = problem,
            // Retrying is what makes the hint actionable: fix the cause, press the button,
            // and there is no need to wait for the next tick or restart the app.
            onRetry = {
                state.showSyncError = false
                onRetrySync()
            },
            onDismiss = { state.showSyncError = false },
        )
    }

    val linkable = state.linkableRepos
    if (linkable != null) {
        LinkRepoDialog(
            repos = linkable,
            loading = state.linkLoading,
            error = state.linkError,
            onSelect = { selected ->
                state.linkableRepos = null
                val owner =
                    selected.repo.owner
                        ?.login
                        .orEmpty()
                state.runGitHub {
                    val repoId = gitHubRepository.linkRepo(owner, selected.repo.name, selected.installationId)
                    // Expand the new folder so the first pull's results are visible.
                    state.expanded = state.expanded + gitHubRepoNodeId(repoId)
                    state.selectedFolderId = gitHubRepoNodeId(repoId)
                    "Linked ${selected.label}"
                }
            },
            onDismiss = { state.linkableRepos = null },
        )
    }

    val forcePullId = state.forcePullTarget
    if (forcePullId != null) {
        val unsaved = unsavedIssuesFor(forcePullId)
        AlertDialog(
            onDismissRequest = { state.forcePullTarget = null },
            title = { Text("Force pull?") },
            text = {
                Text(
                    if (unsaved == 0) {
                        "This replaces the local copy of every issue with what is on GitHub."
                    } else {
                        "This replaces the local copy of every issue with what is on GitHub, and " +
                            "discards $unsaved unsaved change(s) in this folder."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    state.forcePullTarget = null
                    state.runGitHub {
                        val result = gitHubRepository.pull(forcePullId, force = true)
                        "Force pulled ${result.pulled} issue(s), removed ${result.pruned}"
                    }
                }) {
                    Text("Force pull")
                }
            },
            dismissButton = {
                TextButton(onClick = { state.forcePullTarget = null }) { Text("Cancel") }
            },
        )
    }

    val newIssueRepoId = state.newIssueRepo
    if (newIssueRepoId != null) {
        TextPromptDialog(
            title = "New issue",
            fieldLabel = "Title",
            confirmLabel = "Create",
            onDismiss = { state.newIssueRepo = null },
            onConfirm = { title ->
                state.newIssueRepo = null
                state.runGitHub {
                    val created = gitHubRepository.createIssue(newIssueRepoId, title, body = "")
                    if (created != null) {
                        // Show it, state.expanded, so the body can be written straight away and pushed.
                        state.expanded = state.expanded + gitHubRepoNodeId(newIssueRepoId)
                        state.selectedRef = NoteRef.GitHubIssue(newIssueRepoId, created.number)
                        "Created #${created.number} on GitHub"
                    } else {
                        null
                    }
                }
            },
        )
    }

    val notice = state.gitHubNotice
    if (notice != null) {
        MessageDialog(
            title = "GitHub",
            message = notice,
            onConfirm = { state.gitHubNotice = null },
            onDismiss = { state.gitHubNotice = null },
        )
    }

    val gitHubProblem = state.gitHubError
    if (gitHubProblem != null) {
        MessageDialog(
            title = "GitHub",
            message = gitHubProblem,
            onConfirm = { state.gitHubError = null },
            onDismiss = { state.gitHubError = null },
        )
    }
}
