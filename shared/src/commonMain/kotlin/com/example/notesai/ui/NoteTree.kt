package com.example.notesai.ui

import androidx.compose.runtime.Immutable
import com.example.notesai.db.FolderEntity
import com.example.notesai.db.GitHubIssueNoteEntity
import com.example.notesai.db.GitHubRepoEntity
import com.example.notesai.db.NoteEntity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Where a tree row's content comes from. Drives which actions make sense for it. */
internal enum class NoteSource { LOCAL, GITHUB }

/**
 * A single row of the flattened note tree, ready to be rendered by a LazyColumn.
 * Marked [@Immutable] so Compose can skip unchanged rows. The builder functions below
 * have no Compose dependency beyond the annotation and are unit testable.
 */
@Immutable
internal data class NoteTreeItem(
    val id: Long,
    val label: String,
    val depth: Int,
    val isFolder: Boolean,
    val expandable: Boolean,
    val expanded: Boolean,
    val parentFolderId: Long,
    /** Non-null for note rows; the identity a click selects and the editor opens. */
    val ref: NoteRef? = null,
    val source: NoteSource = NoteSource.LOCAL,
    /** Secondary text shown on the row — an issue's state, for now. */
    val badge: String? = null,
)

/**
 * Synthetic id for a linked repository's node.
 *
 * The tree's expansion state is keyed on ids, and these nodes are not folders in the database, so
 * they get an id of their own. Negative, so it cannot collide with a local row id.
 */
internal fun gitHubRepoNodeId(repoId: Long): Long = -repoId

/**
 * Flattens the folder/note adjacency lists into a list of rows in display order.
 * Children are sorted alphabetically and only included when their parent is expanded.
 *
 * Linked GitHub repositories appear inside the root, after the local tree.
 */
internal fun buildNoteTree(
    folders: List<FolderEntity>,
    notes: List<NoteEntity>,
    expanded: Set<Long>,
    gitHubRepos: List<GitHubRepoEntity> = emptyList(),
    gitHubIssues: List<GitHubIssueNoteEntity> = emptyList(),
): ImmutableList<NoteTreeItem> {
    val childFolders = folders.groupBy { it.parentId }
    val childNotes = notes.groupBy { it.folderId }
    val root = folders.firstOrNull { it.parentId == null } ?: return emptyList<NoteTreeItem>().toImmutableList()
    val out = mutableListOf<NoteTreeItem>()

    fun addFolder(
        folder: FolderEntity,
        depth: Int,
    ) {
        val subFolders = (childFolders[folder.id] ?: emptyList()).sortedBy { it.name.lowercase() }
        val subNotes = (childNotes[folder.id] ?: emptyList()).sortedBy { it.title.lowercase() }
        val isExpanded = folder.id in expanded

        out +=
            NoteTreeItem(
                id = folder.id,
                label = folder.name,
                depth = depth,
                isFolder = true,
                expandable = subFolders.isNotEmpty() || subNotes.isNotEmpty(),
                expanded = isExpanded,
                parentFolderId = folder.parentId ?: folder.id,
            )

        if (isExpanded) {
            subFolders.forEach { addFolder(it, depth + 1) }
            subNotes.forEach { note ->
                out +=
                    NoteTreeItem(
                        id = note.id,
                        label = note.title.ifBlank { "Untitled" },
                        depth = depth + 1,
                        isFolder = false,
                        expandable = false,
                        expanded = false,
                        parentFolderId = folder.id,
                        ref = NoteRef.Local(note.id),
                    )
            }
        }
    }

    addFolder(root, 0)
    addGitHubRepos(out, root, expanded, gitHubRepos, gitHubIssues)

    return out.toImmutableList()
}

/** Linked repositories as tree nodes, each expandable into its issues. */
private fun addGitHubRepos(
    out: MutableList<NoteTreeItem>,
    root: FolderEntity,
    expanded: Set<Long>,
    gitHubRepos: List<GitHubRepoEntity>,
    gitHubIssues: List<GitHubIssueNoteEntity>,
) {
    val issuesByRepo = gitHubIssues.groupBy { it.repoId }

    for (repo in gitHubRepos.sortedBy { "${it.owner}/${it.name}".lowercase() }) {
        val nodeId = gitHubRepoNodeId(repo.id)
        val issues = (issuesByRepo[repo.id] ?: emptyList()).sortedBy { it.number }
        val isExpanded = nodeId in expanded

        out +=
            NoteTreeItem(
                id = nodeId,
                label = "${repo.owner}/${repo.name}",
                depth = 1,
                isFolder = true,
                expandable = issues.isNotEmpty(),
                expanded = isExpanded,
                parentFolderId = root.id,
                source = NoteSource.GITHUB,
            )

        if (!isExpanded) continue
        for (issue in issues) {
            out +=
                NoteTreeItem(
                    // Issue rows are identified by their ref; the local id is unused for them.
                    id = 0L,
                    label = "#${issue.number} ${issue.title.ifBlank { "(no title)" }}",
                    depth = 2,
                    isFolder = false,
                    expandable = false,
                    expanded = false,
                    parentFolderId = nodeId,
                    ref = NoteRef.GitHubIssue(repoId = repo.id, number = issue.number),
                    source = NoteSource.GITHUB,
                    badge = if (issue.state == "closed") "Closed" else "Open",
                )
        }
    }
}

/** The folder [id] itself plus all of its descendant folder ids. */
internal fun folderAndDescendants(
    id: Long,
    folders: List<FolderEntity>,
): Set<Long> {
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
