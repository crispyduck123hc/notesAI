package com.example.notesai.ui

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
    )

    @Test
    fun localNotesCarryTheirRef() {
        val tree = buildNoteTree(listOf(root), listOf(note(7, "local note")), expanded = setOf(1))

        val row = tree.single { !it.isFolder }
        assertEquals(NoteRef.Local(7), row.ref)
        assertEquals(NoteSource.LOCAL, row.source)
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

        val node = tree.single { it.source == NoteSource.GITHUB }
        assertEquals("octocat/notes", node.label)
        assertTrue(node.isFolder)
        assertTrue(node.expandable, "it has issues, so it can be expanded")
        assertEquals(gitHubRepoNodeId(3), node.id, "the node id is synthetic and negative")
        assertTrue(node.id < 0, "negative ids cannot collide with local folders")
        assertTrue(tree.none { it.ref is NoteRef.GitHubIssue }, "a collapsed repository shows no issues")
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

        val issues = tree.filter { it.ref is NoteRef.GitHubIssue }
        assertEquals(listOf("#1 first", "#2 second"), issues.map { it.label }, "issues are ordered by number")
        assertEquals(listOf("Open", "Closed"), issues.map { it.badge })
        assertEquals(
            listOf(NoteRef.GitHubIssue(repoId = 3, number = 1), NoteRef.GitHubIssue(repoId = 3, number = 2)),
            issues.map { it.ref },
        )
        assertTrue(issues.all { it.source == NoteSource.GITHUB })
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

        val byLabel = tree.filter { it.ref is NoteRef.GitHubIssue }.associateBy { it.label }
        assertEquals(NoteRef.GitHubIssue(repoId = 3, number = 1), byLabel.getValue("#1 in notes").ref)
        assertEquals(NoteRef.GitHubIssue(repoId = 4, number = 1), byLabel.getValue("#1 in other").ref)
    }
}
