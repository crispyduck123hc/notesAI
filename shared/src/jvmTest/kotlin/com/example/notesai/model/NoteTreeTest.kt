package com.example.notesai.model

import com.example.notesai.db.FolderEntity
import com.example.notesai.db.GitHubIssueNoteEntity
import com.example.notesai.db.GitHubRepoEntity
import com.example.notesai.db.NoteEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tree builder, now that it also has to lay out linked GitHub repositories.
 *
 * Pure function, no Compose runtime, so this covers the ordering and identity rules cheaply — the
 * parts that are easy to get subtly wrong and annoying to debug through a window.
 */
class NoteTreeTest {
    private val root =
        FolderEntity(
            id = 1,
            uuid = "root-uuid",
            name = "root",
            parentId = null,
            sortOrder = 0,
            updatedAt = 0,
            deletedAt = null,
        )

    private fun note(
        id: Long,
        title: String,
        folderId: Long = 1,
    ) = NoteEntity(
        id = id,
        title = title,
        content = title,
        createdAt = 0,
        folderId = folderId,
        uuid = "note-$id",
        updatedAt = 0,
        deletedAt = null,
        conflictOf = null,
    )

    private fun folder(
        id: Long,
        name: String,
        parentId: Long = 1,
    ) = FolderEntity(
        id = id,
        uuid = "folder-$id",
        name = name,
        parentId = parentId,
        sortOrder = 0,
        updatedAt = 0,
        deletedAt = null,
    )

    private fun repo(
        id: Long,
        owner: String,
        name: String,
    ) = GitHubRepoEntity(id = id, owner = owner, name = name, installationId = 1, linkedAt = 0)

    private fun issue(
        repoId: Long,
        number: Long,
        title: String,
        state: String,
    ) = GitHubIssueNoteEntity(
        id = number,
        repoId = repoId,
        number = number,
        title = title,
        body = "body of $number",
        state = state,
        remoteUpdatedAt = "2026-01-01T00:00:01Z",
        dirty = 0,
        pulledAt = 0,
        createdAt = "2026-01-01T00:00:00Z",
    )

    @Test
    fun localNotesCarryTheirRef() {
        val tree = buildNoteTree(listOf(root), listOf(note(7, "local note")), expanded = setOf(1))

        val row = tree.filterIsInstance<NoteTreeItem.Note>().single()
        assertEquals(NoteRef.Local(7), row.ref)
        assertEquals(1, row.folderId, "a note knows the folder it belongs to")
    }

    @Test
    fun aLinkedRepositoryAppearsEvenWhenCollapsed() {
        val tree =
            buildNoteTree(
                folders = listOf(root),
                notes = emptyList(),
                expanded = setOf(1),
                gitHubRepos = listOf(repo(3, "octocat", "notes")),
                // Seeded but not expanded: the node is expandable, yet shows no rows.
                gitHubIssues = listOf(issue(3, 1, "first", "open")),
            )

        val node = tree.filterIsInstance<NoteTreeItem.Repository>().single()
        assertEquals("octocat/notes", node.label)
        assertTrue(node.expandable, "it has issues, so it can be expanded")
        assertEquals(gitHubRepoNodeId(3), node.nodeId, "the node id is synthetic and negative")
        assertTrue(node.nodeId < 0, "negative ids cannot collide with local folders")
        assertTrue(
            tree.none { it is NoteTreeItem.Issue },
            "a collapsed repository shows no issues",
        )
    }

    @Test
    fun expandingARepositoryShowsItsIssuesWithStateBadges() {
        val repoNodeId = gitHubRepoNodeId(3)
        val tree =
            buildNoteTree(
                folders = listOf(root),
                notes = emptyList(),
                expanded = setOf(1, repoNodeId),
                gitHubRepos = listOf(repo(3, "octocat", "notes")),
                gitHubIssues = listOf(issue(3, 2, "second", "closed"), issue(3, 1, "first", "open")),
            )

        val issues = tree.filterIsInstance<NoteTreeItem.Issue>()
        assertEquals(listOf("#1 first", "#2 second"), issues.map { it.label }, "issues are ordered by number")
        assertEquals(listOf("Open", "Closed"), issues.map { it.badge })
        assertEquals(
            listOf(NoteRef.GitHubIssue(repoId = 3, number = 1), NoteRef.GitHubIssue(repoId = 3, number = 2)),
            issues.map { it.ref },
        )
        assertEquals(listOf(false, true), issues.map { it.isClosed })
    }

    @Test
    fun issuesFromDifferentRepositoriesDoNotMix() {
        val a = gitHubRepoNodeId(3)
        val b = gitHubRepoNodeId(4)
        val tree =
            buildNoteTree(
                folders = listOf(root),
                notes = emptyList(),
                expanded = setOf(1, a, b),
                gitHubRepos = listOf(repo(3, "octocat", "notes"), repo(4, "octocat", "other")),
                gitHubIssues = listOf(issue(3, 1, "in notes", "open"), issue(4, 1, "in other", "open")),
            )

        val byLabel = tree.filterIsInstance<NoteTreeItem.Issue>().associateBy { it.label }
        assertEquals(NoteRef.GitHubIssue(repoId = 3, number = 1), byLabel.getValue("#1 in notes").ref)
        assertEquals(NoteRef.GitHubIssue(repoId = 4, number = 1), byLabel.getValue("#1 in other").ref)
    }

    @Test
    fun everyRowHasItsOwnKey() {
        // The LazyColumn is keyed on these and duplicate keys crash the list. The old model made that
        // a trap: every GitHub issue row carried the same unused local id 0, so the key had to be
        // derived in the UI, away from the ids it was protecting.
        val tree =
            buildNoteTree(
                folders = listOf(root, folder(2, "folder")),
                notes = listOf(note(1, "note one"), note(2, "note two", folderId = 2)),
                expanded = setOf(1, 2, gitHubRepoNodeId(3)),
                gitHubRepos = listOf(repo(3, "octocat", "notes")),
                gitHubIssues = listOf(issue(3, 1, "first", "open"), issue(3, 2, "second", "closed")),
            )

        val keys = tree.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "duplicate row key in $keys")
        assertTrue(tree.size >= 6, "the fixture should cover all four kinds, got ${tree.map { it::class.simpleName }}")
    }

    @Test
    fun aNoteAndAnIssueMayShareANumberWithoutSharingAnIdentity() {
        // Row ids come from different tables, so "note 1" and "issue #1" are different things that can
        // legitimately carry the same number. That is why identity is the ref, not the id.
        val tree =
            buildNoteTree(
                folders = listOf(root),
                notes = listOf(note(1, "local")),
                expanded = setOf(1, gitHubRepoNodeId(3)),
                gitHubRepos = listOf(repo(3, "octocat", "notes")),
                gitHubIssues = listOf(issue(3, 1, "remote", "open")),
            )

        val local = tree.filterIsInstance<NoteTreeItem.Note>().single()
        val remote = tree.filterIsInstance<NoteTreeItem.Issue>().single()

        assertEquals(1L, local.id)
        assertEquals(1L, remote.number)
        assertTrue(local.key != remote.key, "identity is the ref, so the keys still differ")
    }
}
