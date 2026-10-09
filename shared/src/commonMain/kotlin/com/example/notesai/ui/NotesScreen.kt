package com.example.notesai.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.notesai.auth.AccountInfo
import com.example.notesai.data.EntityType
import com.example.notesai.data.NoteRepository

/**
 * Top-level screen: owns the selection/expansion state and wires the tree pane to the
 * editor pane. All presentation detail lives in the child composables.
 */
@Composable
fun NotesScreen(
    repository: NoteRepository,
    account: AccountInfo,
    syncStatus: String?,
    syncing: Boolean,
    onResolveConflict: (id: Long, keepLocal: Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    val folders by repository.allFolders.collectAsState(initial = emptyList())
    val notes by repository.allNotes.collectAsState(initial = emptyList())
    val conflicts by repository.conflicts.collectAsState(initial = emptyList())

    var expanded by remember { mutableStateOf(setOf(NoteRepository.ROOT_FOLDER_ID)) }
    var selectedFolderId by remember { mutableStateOf(NoteRepository.ROOT_FOLDER_ID) }
    var selectedNoteId by remember { mutableStateOf<Long?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renamingFolderId by remember { mutableStateOf<Long?>(null) }
    var movingNoteId by remember { mutableStateOf<Long?>(null) }
    var showConflicts by remember { mutableStateOf(false) }

    val tree = remember(folders, notes, expanded) { buildNoteTree(folders, notes, expanded) }
    val selectedNote = notes.firstOrNull { it.id == selectedNoteId }

    // Reads tombstones too (repository lookups, not `notes`), because a delete-vs-edit
    // conflict's local side is precisely a row the normal query hides.
    val conflictItems = remember(conflicts) {
        conflicts.map { conflict ->
            val note = if (conflict.entityType == EntityType.NOTE) {
                repository.noteByUuid(conflict.entityUuid)
            } else {
                null
            }
            val folder = if (conflict.entityType == EntityType.FOLDER) {
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
            selectedNoteId = selectedNoteId,
            accountEmail = account.email,
            syncStatus = syncStatus,
            syncing = syncing,
            conflictCount = conflictItems.size,
            onToggle = { id ->
                expanded = if (id in expanded) expanded - id else expanded + id
            },
            onSelectFolder = { selectedFolderId = it },
            onSelectNote = { item ->
                selectedNoteId = item.id
                selectedFolderId = item.parentFolderId
            },
            onDeleteFolder = { id ->
                // Clear selection if the open note or active folder is being removed.
                val removed = folderAndDescendants(id, folders)
                if (selectedFolderId in removed) selectedFolderId = NoteRepository.ROOT_FOLDER_ID
                val openNote = notes.firstOrNull { it.id == selectedNoteId }
                if (openNote != null && openNote.folderId in removed) selectedNoteId = null
                repository.deleteFolder(id)
            },
            onDeleteNote = { id ->
                repository.deleteNote(id)
                if (selectedNoteId == id) selectedNoteId = null
            },
            onRenameFolder = { renamingFolderId = it },
            onMoveNote = { movingNoteId = it },
            onCreateNote = { selectedNoteId = repository.addNote(folderId = selectedFolderId) },
            onCreateFolder = { showNewFolderDialog = true },
            onReviewConflicts = { showConflicts = true },
            onSignOut = onSignOut,
        )

        VerticalDivider()

        NoteEditorPane(
            note = selectedNote,
            onChange = { text -> selectedNote?.let { repository.updateNote(it.id, text) } },
        )
    }

    if (showNewFolderDialog) {
        FolderNameDialog(
            title = "New folder",
            confirmLabel = "Create",
            initialValue = "",
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                repository.addFolder(name = name, parentId = selectedFolderId)
                expanded = expanded + selectedFolderId
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
}
