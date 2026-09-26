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
import com.example.notesai.data.NoteRepository

/**
 * Top-level screen: owns the selection/expansion state and wires the tree pane to
 * the editor pane. All presentation detail lives in the child composables.
 */
@Composable
fun NotesScreen(repository: NoteRepository) {
    val folders by repository.allFolders.collectAsState(initial = emptyList())
    val notes by repository.allNotes.collectAsState(initial = emptyList())

    var expanded by remember { mutableStateOf(setOf(NoteRepository.ROOT_FOLDER_ID)) }
    var selectedFolderId by remember { mutableStateOf(NoteRepository.ROOT_FOLDER_ID) }
    var selectedNoteId by remember { mutableStateOf<Long?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    val tree = remember(folders, notes, expanded) { buildNoteTree(folders, notes, expanded) }
    val selectedNote = notes.firstOrNull { it.id == selectedNoteId }

    Row(modifier = Modifier.fillMaxSize()) {
        NoteTreePane(
            tree = tree,
            selectedFolderId = selectedFolderId,
            selectedNoteId = selectedNoteId,
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
            onCreateNote = { selectedNoteId = repository.addNote(folderId = selectedFolderId) },
            onCreateFolder = { showNewFolderDialog = true }
        )

        VerticalDivider()

        NoteEditorPane(
            note = selectedNote,
            onChange = { text -> selectedNote?.let { repository.updateNote(it.id, text) } }
        )
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onConfirm = { name ->
                repository.addFolder(name = name, parentId = selectedFolderId)
                expanded = expanded + selectedFolderId
                showNewFolderDialog = false
            }
        )
    }
}
