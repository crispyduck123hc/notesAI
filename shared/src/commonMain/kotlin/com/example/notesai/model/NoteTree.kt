package com.example.notesai.model

import androidx.compose.runtime.Immutable
import com.example.notesai.db.FolderEntity
import com.example.notesai.db.GitHubIssueNoteEntity
import com.example.notesai.db.GitHubRepoEntity
import com.example.notesai.db.NoteEntity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * One row of the flattened note tree, ready for a LazyColumn.
 *
 * A sealed hierarchy rather than one record with an `isFolder` flag, a nullable `ref` and a source
 * enum, because that record could describe impossible rows — a folder carrying a note reference, a
 * note that expands — and every reader had to know which combinations were real. Here each kind
 * holds only what means something for it, so the impossible rows cannot be constructed. The cost is
 * that a `when` has to name every kind; the benefit is that adding one makes the compiler show you
 * every place that needs to decide something about it.
 *
 * [@Immutable] lets Compose skip rows that did not change. Nothing here imports a composable, so the
 * whole tree is unit testable without a UI harness.
 */
@Immutable
internal sealed interface NoteTreeItem {
    /** Unique across a tree; the LazyColumn key and what a test can assert identity on. */
    val key: String

    /** The text on the row. */
    val label: String

    /** Indentation level: 0 for the root, 1 for folders and repositories, 2 for notes and issues. */
    val depth: Int

    /** A local folder: expands, and can be renamed, moved and deleted. */
    data class Folder(
        val id: Long,
        override val label: String,
        override val depth: Int,
        val expandable: Boolean,
        val expanded: Boolean,
        /** The root cannot be deleted: it is the anchor for everything else. */
        val isRoot: Boolean,
    ) : NoteTreeItem {
        override val key: String get() = "folder-$id"
    }

    /**
     * A linked GitHub repository. Not a folder in the database, but laid out like one — hence a
     * [nodeId] of its own (negative, so it cannot collide with a local row) and its own [repoId].
     */
    data class Repository(
        val repoId: Long,
        override val label: String,
        override val depth: Int,
        val expandable: Boolean,
        val expanded: Boolean,
    ) : NoteTreeItem {
        /** The id used by the expansion state and by folder selection for this node. */
        val nodeId: Long get() = gitHubRepoNodeId(repoId)

        override val key: String get() = "repo-$repoId"
    }

    /** A local note. Opens in the editor and can be moved between folders. */
    data class Note(
        val id: Long,
        override val label: String,
        override val depth: Int,
        /** Its folder — also the folder to select when the row is clicked. */
        val folderId: Long,
    ) : NoteTreeItem {
        override val key: String get() = "note-$id"

        val ref: NoteRef get() = NoteRef.Local(id)
    }

    /** A GitHub issue: closes and reopens on GitHub, is never deleted from here. */
    data class Issue(
        val repoId: Long,
        val number: Long,
        override val label: String,
        override val depth: Int,
        val badge: String,
        /** Local edits not yet pushed. */
        val unsaved: Boolean,
    ) : NoteTreeItem {
        override val key: String get() = "issue-$repoId-$number"

        val ref: NoteRef get() = NoteRef.GitHubIssue(repoId = repoId, number = number)

        /** Selecting an issue shows its repository's actions, so that is the node to select. */
        val parentNodeId: Long get() = gitHubRepoNodeId(repoId)

        val isClosed: Boolean get() = badge == CLOSED_BADGE

        companion object {
            const val CLOSED_BADGE: String = "Closed"
            const val OPEN_BADGE: String = "Open"
        }
    }
}

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
            NoteTreeItem.Folder(
                id = folder.id,
                label = folder.name,
                depth = depth,
                expandable = subFolders.isNotEmpty() || subNotes.isNotEmpty(),
                expanded = isExpanded,
                isRoot = folder.parentId == null,
            )

        if (!isExpanded) return

        subFolders.forEach { addFolder(it, depth + 1) }
        subNotes.forEach { note ->
            out +=
                NoteTreeItem.Note(
                    id = note.id,
                    label = note.title.ifBlank { "Untitled" },
                    depth = depth + 1,
                    folderId = folder.id,
                )
        }
    }

    addFolder(root, 0)
    addGitHubRepos(out, expanded, gitHubRepos, gitHubIssues)

    return out.toImmutableList()
}

/** Linked repositories as tree nodes, each expandable into its issues. */
private fun addGitHubRepos(
    out: MutableList<NoteTreeItem>,
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
            NoteTreeItem.Repository(
                repoId = repo.id,
                label = "${repo.owner}/${repo.name}",
                depth = 1,
                expandable = issues.isNotEmpty(),
                expanded = isExpanded,
            )

        if (!isExpanded) continue
        for (issue in issues) {
            out +=
                NoteTreeItem.Issue(
                    repoId = repo.id,
                    number = issue.number,
                    label = "#${issue.number} ${issue.title.ifBlank { "(no title)" }}",
                    depth = 2,
                    badge = if (issue.state == "closed") NoteTreeItem.Issue.CLOSED_BADGE else NoteTreeItem.Issue.OPEN_BADGE,
                    unsaved = issue.dirty == 1L,
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
