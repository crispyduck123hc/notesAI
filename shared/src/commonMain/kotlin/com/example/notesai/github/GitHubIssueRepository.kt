package com.example.notesai.github

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import com.example.notesai.db.GitHubIssueNoteEntity
import com.example.notesai.db.GitHubRepoEntity
import com.example.notesai.db.NotesDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/** What one pull did, so the UI can say something truthful about it. */
data class GitHubPullResult(
    val pulled: Int,
    /** Rows the remote no longer has. */
    val pruned: Int,
    /** Rows left alone because they have local edits and the pull was not forced. */
    val skippedLocalEdits: Int,
)

/** What one push did. Nothing is silently overwritten: anything that moved is reported instead. */
data class GitHubPushResult(
    val pushed: Int,
    /** Issues edited on GitHub since the last pull; not pushed, the user decides. */
    val changedRemotely: List<Long>,
    /** Issues GitHub refused, with a reason. */
    val failed: List<Pair<Long, String>>,
)

/**
 * The GitHub folder: a linked repository and a local mirror of its issues.
 *
 * Explicit pull/push only — there is no background loop and no outbox. `dirty` means "edited since
 * the last pull or push", and the user's button decides when that travels.
 *
 * **These tables are not `NoteEntity`.** Drive sync projects every note into the user's Drive app-data
 * folder; a GitHub issue must never travel there. Keeping it in its own tables makes that structural
 * rather than a flag someone has to remember to check.
 */
class GitHubIssueRepository(
    driver: SqlDriver,
    private val client: GitHubClient,
) {
    private val queries = NotesDatabase(driver).notesDatabaseQueries

    val linkedRepos: Flow<List<GitHubRepoEntity>> =
        queries.selectAllGitHubRepos().asFlow().mapToList(Dispatchers.Default)

    fun issues(repoId: Long): Flow<List<GitHubIssueNoteEntity>> = queries.selectGitHubIssues(repoId).asFlow().mapToList(Dispatchers.Default)

    /** Every mirrored issue, across all linked repositories — what the tree renders. */
    val allIssues: Flow<List<GitHubIssueNoteEntity>> =
        queries.selectAllGitHubIssues().asFlow().mapToList(Dispatchers.Default)

    fun linkedRepo(repoId: Long): GitHubRepoEntity? = queries.selectGitHubRepoById(repoId).executeAsOneOrNull()

    fun issue(
        repoId: Long,
        number: Long,
    ): GitHubIssueNoteEntity? = queries.selectGitHubIssue(repoId, number).executeAsOneOrNull()

    // ---- Linking -----------------------------------------------------------

    /** Links a repository, or updates the installation it is reachable through. */
    fun linkRepo(
        owner: String,
        name: String,
        installationId: Long,
    ): Long {
        val existing = queries.selectGitHubRepoByOwnerName(owner, name).executeAsOneOrNull()
        if (existing != null) {
            queries.updateGitHubRepoInstallation(installationId = installationId, id = existing.id)
            return existing.id
        }
        queries.insertGitHubRepo(
            owner = owner,
            name = name,
            installationId = installationId,
            linkedAt = now(),
        )
        return queries.selectGitHubRepoByOwnerName(owner, name).executeAsOne().id
    }

    /**
     * Unlinks the repository. The issues stay on GitHub; only the local mirror is dropped.
     *
     * The mirror rows are deleted explicitly rather than relying on the foreign key's
     * `ON DELETE CASCADE`: SQLite only enforces that when `PRAGMA foreign_keys = ON`, which this
     * connection does not set, so the cascade silently would not happen.
     */
    fun unlinkRepo(repoId: Long) {
        queries.deleteGitHubIssuesForRepo(repoId)
        queries.deleteGitHubRepo(repoId)
    }

    /**
     * Creates an issue on GitHub and mirrors it.
     *
     * Not queued like an edit: the user asked for it explicitly, so it either exists on GitHub or it
     * failed loudly. The mirror row is written from GitHub's response, so the number and timestamps are
     * never guessed — the issue number is what identifies it locally from then on.
     */
    suspend fun createIssue(
        repoId: Long,
        title: String,
        body: String,
    ): GitHubIssueNoteEntity? {
        val repo = queries.selectGitHubRepoById(repoId).executeAsOneOrNull() ?: return null
        val created = client.createIssue(owner = repo.owner, repo = repo.name, title = title, body = body)
        queries.insertGitHubIssue(
            repoId = repoId,
            number = created.number,
            title = created.title,
            body = created.body.orEmpty(),
            state = created.state,
            remoteUpdatedAt = created.updatedAt,
            dirty = 0,
            pulledAt = now(),
        )
        return queries.selectGitHubIssue(repoId, created.number).executeAsOneOrNull()
    }

    // ---- Editing -----------------------------------------------------------

    /** Records a local edit. Nothing leaves the machine until [push]. */
    fun editIssue(
        repoId: Long,
        number: Long,
        title: String,
        body: String,
    ) {
        queries.markGitHubIssueDirty(title = title, body = body, repoId = repoId, number = number)
    }

    // ---- Pull --------------------------------------------------------------

    /**
     * Makes the local mirror match the repository.
     *
     * [force] is what the Pull button uses: local edits are discarded, because that is what "pull
     * everything over" means and the caller has confirmed it. Without [force], rows with local edits
     * are left alone and counted, so an ordinary refresh can never destroy unpushed work.
     */
    suspend fun pull(
        repoId: Long,
        force: Boolean,
    ): GitHubPullResult {
        val repo =
            queries.selectGitHubRepoById(repoId).executeAsOneOrNull()
                ?: return GitHubPullResult(pulled = 0, pruned = 0, skippedLocalEdits = 0)

        val remoteIssues = client.issues(owner = repo.owner, repo = repo.name)
        val remoteNumbers = remoteIssues.mapTo(mutableSetOf()) { it.number }
        val timestamp = now()

        var pulled = 0
        var skipped = 0
        for (issue in remoteIssues) {
            val existing = queries.selectGitHubIssue(repoId, issue.number).executeAsOneOrNull()
            if (!force && existing?.dirty == 1L) {
                skipped++
                continue
            }

            // `state` and `remoteUpdatedAt` come from GitHub; the body is what the note shows.
            if (existing == null) {
                queries.insertGitHubIssue(
                    repoId = repoId,
                    number = issue.number,
                    title = issue.title,
                    body = issue.body.orEmpty(),
                    state = issue.state,
                    remoteUpdatedAt = issue.updatedAt,
                    dirty = 0,
                    pulledAt = timestamp,
                )
            } else {
                queries.updateGitHubIssueFromPull(
                    title = issue.title,
                    body = issue.body.orEmpty(),
                    state = issue.state,
                    remoteUpdatedAt = issue.updatedAt,
                    pulledAt = timestamp,
                    repoId = repoId,
                    number = issue.number,
                )
            }
            pulled++
        }

        // The mirror is a cache of the repository, so rows whose issues are gone (transferred or
        // removed upstream) go too. Safe because the fetch above completed without throwing.
        val pruned =
            queries
                .selectGitHubIssues(repoId)
                .executeAsList()
                .count { row ->
                    if (row.number in remoteNumbers) {
                        false
                    } else {
                        queries.deleteGitHubIssue(repoId, row.number)
                        true
                    }
                }

        return GitHubPullResult(pulled = pulled, pruned = pruned, skippedLocalEdits = skipped)
    }

    // ---- Push --------------------------------------------------------------

    /**
     * Sends locally edited issues to GitHub.
     *
     * Before writing, each issue is fetched and its `updated_at` compared with what the mirror
     * recorded: if it moved, somebody else edited it since the pull, and that is reported rather than
     * overwritten. Only title and body are ever written — never comments, labels or state, so nothing
     * belonging to other people is disturbed.
     */
    suspend fun push(repoId: Long): GitHubPushResult {
        val repo =
            queries.selectGitHubRepoById(repoId).executeAsOneOrNull()
                ?: return GitHubPushResult(pushed = 0, changedRemotely = emptyList(), failed = emptyList())

        val dirtyRows = queries.selectGitHubIssues(repoId).executeAsList().filter { it.dirty == 1L }
        val pushed = mutableListOf<Long>()
        val changed = mutableListOf<Long>()
        val failed = mutableListOf<Pair<Long, String>>()

        for (row in dirtyRows) {
            try {
                val current = client.issue(owner = repo.owner, repo = repo.name, number = row.number)
                if (current.updatedAt != row.remoteUpdatedAt) {
                    changed += row.number
                    continue
                }

                val updated =
                    client.updateIssue(
                        owner = repo.owner,
                        repo = repo.name,
                        number = row.number,
                        edit = IssueEdit(title = row.title, body = row.body),
                    )
                // The write's response is the new truth: adopt its timestamp so the next push compares
                // against what is actually there.
                queries.markGitHubIssueClean(
                    title = updated.title,
                    body = updated.body.orEmpty(),
                    state = updated.state,
                    remoteUpdatedAt = updated.updatedAt,
                    repoId = repoId,
                    number = row.number,
                )
                pushed += row.number
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught")
                t: Throwable,
            ) {
                // Deliberately broad: one unreadable issue must not abandon the rest of the push, and
                // whatever went wrong is recorded against that issue rather than swallowed.
                failed += row.number to (t.message ?: t.toString())
            }
        }

        return GitHubPushResult(pushed = pushed.size, changedRemotely = changed, failed = failed)
    }

    // ---- Completion --------------------------------------------------------

    /**
     * Closes or reopens an issue.
     *
     * An immediate action rather than a queued one: the user pressed a button, so the result is
     * reported now. Closing is visible to everyone with access to the repository and is reversible,
     * which is why it is its own button and never a side effect of editing a note.
     */
    suspend fun setComplete(
        repoId: Long,
        number: Long,
        completed: Boolean,
    ): GitHubIssueNoteEntity? {
        val repo = queries.selectGitHubRepoById(repoId).executeAsOneOrNull() ?: return null
        val updated =
            client.updateIssue(
                owner = repo.owner,
                repo = repo.name,
                number = number,
                edit =
                    IssueEdit(
                        state = if (completed) "closed" else "open",
                        stateReason = if (completed) "completed" else "reopened",
                    ),
            )

        val existing = queries.selectGitHubIssue(repoId, number).executeAsOneOrNull()
        if (existing != null) {
            if (existing.dirty == 1L) {
                queries.markGitHubIssueState(
                    state = updated.state,
                    remoteUpdatedAt = updated.updatedAt,
                    repoId = repoId,
                    number = number,
                )
            } else {
                queries.markGitHubIssueClean(
                    title = updated.title,
                    body = updated.body.orEmpty(),
                    state = updated.state,
                    remoteUpdatedAt = updated.updatedAt,
                    repoId = repoId,
                    number = number,
                )
            }
        }
        return queries.selectGitHubIssue(repoId, number).executeAsOneOrNull()
    }
}

private fun now(): Long = Clock.System.now().toEpochMilliseconds()
