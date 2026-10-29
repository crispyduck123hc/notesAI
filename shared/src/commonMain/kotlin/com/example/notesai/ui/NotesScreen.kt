package com.example.notesai.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.notesai.auth.AccountInfo
import com.example.notesai.data.EntityType
import com.example.notesai.data.NoteRepository
import com.example.notesai.github.GitHubClient
import com.example.notesai.github.GitHubIssueRepository
import com.example.notesai.github.GitHubPushResult
import com.example.notesai.model.Completion
import com.example.notesai.model.NoteDocument
import com.example.notesai.model.NoteRef
import com.example.notesai.model.buildNoteTree
import com.example.notesai.model.folderAndDescendants
import com.example.notesai.model.gitHubRepoNodeId
import com.example.notesai.model.toDocument
import com.example.notesai.ui.dialogs.ConflictDialog
import com.example.notesai.ui.dialogs.ConflictUiItem
import com.example.notesai.ui.dialogs.FolderNameDialog
import com.example.notesai.ui.dialogs.LinkRepoDialog
import com.example.notesai.ui.dialogs.LinkableRepo
import com.example.notesai.ui.dialogs.MessageDialog
import com.example.notesai.ui.dialogs.MoveNoteDialog
import com.example.notesai.ui.dialogs.SyncErrorDialog
import com.example.notesai.ui.dialogs.TextPromptDialog
import com.example.notesai.ui.editor.NoteEditorPane
import com.example.notesai.ui.tree.GitHubPaneState
import com.example.notesai.ui.tree.NoteTreePane
import com.example.notesai.update.AvailableUpdate
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

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
    syncStatus: String?,
    syncError: String?,
    syncing: Boolean,
    gitHubRepository: GitHubIssueRepository,
    gitHubClient: GitHubClient,
    /** Null when GitHub is not connected. */
    gitHubLogin: String?,
    /** Non-null when a newer release than the running one has been published. */
    availableUpdate: AvailableUpdate? = null,
    onOpenUpdate: () -> Unit = {},
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

    var expanded by remember { mutableStateOf(setOf(NoteRepository.ROOT_FOLDER_ID)) }
    var selectedFolderId by remember { mutableStateOf(NoteRepository.ROOT_FOLDER_ID) }
    var selectedRef by remember { mutableStateOf<NoteRef?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renamingFolderId by remember { mutableStateOf<Long?>(null) }
    var movingNoteId by remember { mutableStateOf<Long?>(null) }
    var showConflicts by remember { mutableStateOf(false) }
    var showSyncError by remember { mutableStateOf(false) }

    // GitHub folder state. `busy` guards the buttons: a pull is a network round trip, and pressing it
    // twice must not start two of them.
    var gitHubBusy by remember { mutableStateOf(false) }
    var gitHubError by remember { mutableStateOf<String?>(null) }
    var gitHubNotice by remember { mutableStateOf<String?>(null) }
    var linkableRepos by remember { mutableStateOf<List<LinkableRepo>?>(null) }
    var linkLoading by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var forcePullTarget by remember { mutableStateOf<Long?>(null) }
    var newIssueRepo by remember { mutableStateOf<Long?>(null) }

    val scope = rememberCoroutineScope()

    val tree =
        remember(folders, notes, expanded, gitHubRepos, gitHubIssues) {
            buildNoteTree(folders, notes, expanded, gitHubRepos, gitHubIssues)
        }
    // The row id the tree highlights, and the entity behind the open note. Both are derived from the
    // selection rather than stored, so a note edited or pulled elsewhere still shows its current text.
    val selectedNoteId = (selectedRef as? NoteRef.Local)?.id
    val selectedNote = notes.firstOrNull { it.id == selectedNoteId }
    val selectedRepo = gitHubRepos.firstOrNull { gitHubRepoNodeId(it.id) == selectedFolderId }

    /**
     * Local folder ids are positive and GitHub nodes are negative, so a negative selection must never
     * be used as a folder id — `New Note` would otherwise write a note into a folder that does not
     * exist.
     */
    val selectedLocalFolderId = selectedFolderId.takeIf { it > 0 } ?: NoteRepository.ROOT_FOLDER_ID

    val selectedDocument: NoteDocument? =
        when (val ref = selectedRef) {
            is NoteRef.Local -> notes.firstOrNull { it.id == ref.id }?.toDocument()
            is NoteRef.GitHubIssue ->
                gitHubIssues
                    .firstOrNull { it.repoId == ref.repoId && it.number == ref.number }
                    ?.toDocument()

            null -> null
        }

    /** Runs a GitHub action with uniform busy/error reporting. Returns a message worth showing. */
    fun runGitHub(block: suspend () -> String?) {
        scope.launch {
            gitHubBusy = true
            try {
                block()?.let { gitHubNotice = it }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                // Broad on purpose: every GitHub failure is reported the same way, with whatever the
                // client already turned into a sentence.
                @Suppress("TooGenericExceptionCaught")
                t: Throwable,
            ) {
                gitHubError = t.message ?: t.toString()
            } finally {
                gitHubBusy = false
            }
        }
    }

    // Reads tombstones too (repository lookups, not `notes`), because a delete-vs-edit
    // conflict's local side is precisely a row the normal query hides.
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
            selectedFolderId = selectedFolderId,
            selectedRef = selectedRef,
            accountEmail = account.email,
            syncStatus = syncStatus,
            hasSyncError = syncError != null,
            syncing = syncing,
            conflictCount = conflictItems.size,
            availableUpdate = availableUpdate,
            onOpenUpdate = onOpenUpdate,
            gitHub =
                GitHubPaneState(
                    login = gitHubLogin,
                    repos = gitHubRepos,
                    selectedRepo = selectedRepo,
                    busy = gitHubBusy,
                    onConnect = {
                        runGitHub {
                            onConnectGitHub()
                            "Connected to GitHub"
                        }
                    },
                    onLink = {
                        linkableRepos = emptyList()
                        linkError = null
                        linkLoading = true
                        scope.launch {
                            try {
                                // Installations first: the app can only see repositories the GitHub App
                                // was installed on, and only ones this user can push to.
                                linkableRepos =
                                    gitHubClient.installations().flatMap { installation ->
                                        gitHubClient
                                            .installationRepositories(installation.id)
                                            .map { LinkableRepo(installationId = installation.id, repo = it) }
                                    }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (
                                @Suppress("TooGenericExceptionCaught")
                                t: Throwable,
                            ) {
                                linkError = t.message ?: t.toString()
                            } finally {
                                linkLoading = false
                            }
                        }
                    },
                    onPull = { repoId ->
                        runGitHub {
                            val result = gitHubRepository.pull(repoId, force = false)
                            val skipped =
                                if (result.skippedLocalEdits > 0) {
                                    ", ${result.skippedLocalEdits} kept local (unpushed)"
                                } else {
                                    ""
                                }
                            "Pulled ${result.pulled} issue(s), removed ${result.pruned}$skipped"
                        }
                    },
                    onForcePull = { repoId -> forcePullTarget = repoId },
                    onPush = { repoId ->
                        runGitHub {
                            val result = gitHubRepository.push(repoId)
                            describePush(result)
                        }
                    },
                    onUnlink = { repoId ->
                        gitHubRepository.unlinkRepo(repoId)
                        if (selectedRepo?.id == repoId) selectedRef = null
                    },
                    onNewIssue = { repoId -> newIssueRepo = repoId },
                    onToggleComplete = { repoId, number, completed ->
                        runGitHub {
                            gitHubRepository.setComplete(repoId, number, completed)
                            if (completed) "Closed the issue on GitHub" else "Reopened the issue on GitHub"
                        }
                    },
                ),
            onToggle = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
            onSelectFolder = { selectedFolderId = it },
            onSelectNote = { item ->
                item.ref?.let { selectedRef = it }
                selectedFolderId = item.parentFolderId
            },
            onDeleteFolder = { id ->
                // Clear selection if the open local note or active folder is being removed.
                val removed = folderAndDescendants(id, folders)
                if (selectedFolderId in removed) selectedFolderId = NoteRepository.ROOT_FOLDER_ID
                val openNote = notes.firstOrNull { it.id == selectedNoteId }
                if (openNote != null && openNote.folderId in removed) selectedRef = null
                repository.deleteFolder(id)
            },
            onDeleteNote = { id ->
                repository.deleteNote(id)
                if (selectedNoteId == id) selectedRef = null
            },
            onRenameFolder = { renamingFolderId = it },
            onMoveNote = { movingNoteId = it },
            onCreateNote = { selectedRef = NoteRef.Local(repository.addNote(folderId = selectedLocalFolderId)) },
            onCreateFolder = { showNewFolderDialog = true },
            onReviewConflicts = { showConflicts = true },
            onShowSyncError = { showSyncError = true },
            onSignOut = onSignOut,
        )

        VerticalDivider()

        NoteEditorPane(
            document = selectedDocument,
            onChange = { text ->
                when (val ref = selectedRef) {
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
                (selectedRef as? NoteRef.GitHubIssue)?.let { ref ->
                    gitHubRepos.firstOrNull { it.id == ref.repoId }?.let { repo ->
                        {
                            openInBrowser("https://github.com/${repo.owner}/${repo.name}/issues/${ref.number}")
                        }
                    }
                },
            onToggleComplete =
                (selectedRef as? NoteRef.GitHubIssue)?.let { ref ->
                    {
                        val currentlyClosed = selectedDocument?.completion == Completion.Closed
                        runGitHub {
                            gitHubRepository.setComplete(ref.repoId, ref.number, completed = !currentlyClosed)
                            if (currentlyClosed) "Reopened the issue on GitHub" else "Closed the issue on GitHub"
                        }
                    }
                },
        )
    }

    if (showNewFolderDialog) {
        FolderNameDialog(
            title = "New folder",
            confirmLabel = "Create",
            initialValue = "",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                repository.addFolder(name = name, parentId = selectedLocalFolderId)
                expanded = expanded + selectedLocalFolderId
                showNewFolderDialog = false
            },
        )
    }

    val renamingFolder = folders.firstOrNull { it.id == renamingFolderId }
    if (renamingFolder != null) {
        FolderNameDialog(
            title = "Rename folder",
            confirmLabel = "Rename",
            initialValue = renamingFolder.name,
            onDismiss = { renamingFolderId = null },
            onConfirm = { name ->
                repository.renameFolder(renamingFolder.id, name)
                renamingFolderId = null
            },
        )
    }

    val movingNote = notes.firstOrNull { it.id == movingNoteId }
    if (movingNote != null) {
        MoveNoteDialog(
            folders = folders,
            currentFolderId = movingNote.folderId,
            onDismiss = { movingNoteId = null },
            onSelect = { folderId ->
                repository.moveNote(movingNote.id, folderId)
                movingNoteId = null
            },
        )
    }

    if (showConflicts && conflictItems.isNotEmpty()) {
        ConflictDialog(
            items = conflictItems,
            onResolve = { id, keepLocal -> onResolveConflict(id, keepLocal) },
            onDismiss = { showConflicts = false },
        )
    }

    val problem = syncError
    if (showSyncError && problem != null) {
        SyncErrorDialog(
            message = problem,
            // Retrying is what makes the hint actionable: fix the cause, press the button,
            // and there is no need to wait for the next tick or restart the app.
            onRetry = {
                showSyncError = false
                onRetrySync()
            },
            onDismiss = { showSyncError = false },
        )
    }

    val linkable = linkableRepos
    if (linkable != null) {
        LinkRepoDialog(
            repos = linkable,
            loading = linkLoading,
            error = linkError,
            onSelect = { selected ->
                linkableRepos = null
                val owner =
                    selected.repo.owner
                        ?.login
                        .orEmpty()
                runGitHub {
                    val repoId = gitHubRepository.linkRepo(owner, selected.repo.name, selected.installationId)
                    // Expand the new folder so the first pull's results are visible.
                    expanded = expanded + gitHubRepoNodeId(repoId)
                    selectedFolderId = gitHubRepoNodeId(repoId)
                    "Linked ${selected.label}"
                }
            },
            onDismiss = { linkableRepos = null },
        )
    }

    val forcePullId = forcePullTarget
    if (forcePullId != null) {
        val unsaved = gitHubIssues.count { it.repoId == forcePullId && it.dirty == 1L }
        AlertDialog(
            onDismissRequest = { forcePullTarget = null },
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
                    forcePullTarget = null
                    runGitHub {
                        val result = gitHubRepository.pull(forcePullId, force = true)
                        "Force pulled ${result.pulled} issue(s), removed ${result.pruned}"
                    }
                }) {
                    Text("Force pull")
                }
            },
            dismissButton = {
                TextButton(onClick = { forcePullTarget = null }) { Text("Cancel") }
            },
        )
    }

    val newIssueRepoId = newIssueRepo
    if (newIssueRepoId != null) {
        TextPromptDialog(
            title = "New issue",
            fieldLabel = "Title",
            confirmLabel = "Create",
            onDismiss = { newIssueRepo = null },
            onConfirm = { title ->
                newIssueRepo = null
                runGitHub {
                    val created = gitHubRepository.createIssue(newIssueRepoId, title, body = "")
                    if (created != null) {
                        // Show it, expanded, so the body can be written straight away and pushed.
                        expanded = expanded + gitHubRepoNodeId(newIssueRepoId)
                        selectedRef = NoteRef.GitHubIssue(newIssueRepoId, created.number)
                        "Created #${created.number} on GitHub"
                    } else {
                        null
                    }
                }
            },
        )
    }

    val notice = gitHubNotice
    if (notice != null) {
        MessageDialog(
            title = "GitHub",
            message = notice,
            onConfirm = { gitHubNotice = null },
            onDismiss = { gitHubNotice = null },
        )
    }

    val gitHubProblem = gitHubError
    if (gitHubProblem != null) {
        MessageDialog(
            title = "GitHub",
            message = gitHubProblem,
            onConfirm = { gitHubError = null },
            onDismiss = { gitHubError = null },
        )
    }
}

/** What a push did, in one sentence — including the part the user has to decide about. */
private fun describePush(result: GitHubPushResult): String =
    buildString {
        append("Pushed ${result.pushed} issue(s)")
        if (result.changedRemotely.isNotEmpty()) {
            append(
                ". ${result.changedRemotely.size} changed on GitHub since you pulled " +
                    "(${result.changedRemotely.joinToString(", ") { "#$it" }}) and were left alone",
            )
        }
        if (result.failed.isNotEmpty()) {
            append(". ${result.failed.size} failed: ${result.failed.first().second}")
        }
    }
