package com.example.notesai.ui

import com.example.notesai.db.FolderEntity
import com.example.notesai.db.NoteEntity

/**
 * A single row of the flattened note tree, ready to be rendered by a LazyColumn.
 * Deliberately free of any Compose dependency so it can be unit tested.
 */
internal data class NoteTreeItem(
    val id: Long,
    val label: String,
    val depth: Int,
    val isFolder: Boolean,
    val expandable: Boolean,
    val expanded: Boolean,
    val parentFolderId: Long
)

/**
 * Flattens the folder/note adjacency lists into a list of rows in display order.
 * Children are sorted alphabetically and only included when their parent is expanded.
 */
internal fun buildNoteTree(
    folders: List<FolderEntity>,
    notes: List<NoteEntity>,
    expanded: Set<Long>
): List<NoteTreeItem> {
    val childFolders = folders.groupBy { it.parentId }
    val childNotes = notes.groupBy { it.folderId }
    val root = folders.firstOrNull { it.parentId == null } ?: return emptyList()
    val out = mutableListOf<NoteTreeItem>()

    fun addFolder(folder: FolderEntity, depth: Int) {
        val subFolders = (childFolders[folder.id] ?: emptyList()).sortedBy { it.name.lowercase() }
        val subNotes = (childNotes[folder.id] ?: emptyList()).sortedBy { it.title.lowercase() }
        val isExpanded = folder.id in expanded

        out += NoteTreeItem(
            id = folder.id,
            label = folder.name,
            depth = depth,
            isFolder = true,
            expandable = subFolders.isNotEmpty() || subNotes.isNotEmpty(),
            expanded = isExpanded,
            parentFolderId = folder.parentId ?: folder.id
        )

        if (isExpanded) {
            subFolders.forEach { addFolder(it, depth + 1) }
            subNotes.forEach { note ->
                out += NoteTreeItem(
                    id = note.id,
                    label = note.title.ifBlank { "Untitled" },
                    depth = depth + 1,
                    isFolder = false,
                    expandable = false,
                    expanded = false,
                    parentFolderId = folder.id
                )
            }
        }
    }

    addFolder(root, 0)
    return out
}

/** The folder [id] itself plus all of its descendant folder ids. */
internal fun folderAndDescendants(id: Long, folders: List<FolderEntity>): Set<Long> {
    val byParent = folders.groupBy { it.parentId }
    val result = mutableSetOf(id)

    fun visit(folderId: Long) {
        byParent[folderId]?.forEach {
            result += it.id
            visit(it.id)
        }
    }

    visit(id)
    return result
}
