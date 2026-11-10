package com.example.notesai.ui.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.example.notesai.db.GitHubRepoEntity
import com.example.notesai.github.GitHubIssueRepository
import com.example.notesai.github.IssueFilter
import com.example.notesai.ui.tree.GitHubPaneState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The GitHub folder's controls, gathered for the tree pane.
 *
 * Split out of the screen because it is a different feature with its own state: linking, pulling, pushing, filing an
 * issue, and the filter. The screen only needs the resulting [GitHubPaneState], which is why the wiring lives here
 * rather than in the middle of the layout.
 */
@Composable
internal fun rememberGitHubPaneState(
    state: NotesScreenState,
    login: String?,
    repos: List<GitHubRepoEntity>,
    selectedRepo: GitHubRepoEntity?,
    repository: GitHubIssueRepository,
    issueCounts: IssueCounts,
    /** Read here and passed in rather than read off [state] inside the `remember`: a captured read would freeze. */
    filter: IssueFilter,
    busy: Boolean,
    onConnectGitHub: suspend () -> Unit,
): GitHubPaneState {
    // Linking takes two round trips of its own (installations, then their repositories), so it needs a scope and
    // reports into the same busy/error slots as everything else here.
    val scope = rememberCoroutineScope()

    return remember(state, login, repos, selectedRepo, repository, issueCounts, filter, busy, onConnectGitHub, scope) {
        GitHubPaneState(
            login = login,
            repos = repos,
            selectedRepo = selectedRepo,
            filter = filter,
            openCount = issueCounts.open,
            closedCount = issueCounts.closed,
            onFilterOpen = { state.issueFilter = state.issueFilter.togglingOpen() },
            onFilterClosed = { state.issueFilter = state.issueFilter.togglingClosed() },
            onCreatedWithin = { range -> state.issueFilter = state.issueFilter.createdWithin(range) },
            onPickCalendarRange = { state.showDateRange = true },
            busy = busy,
            onConnect = {
                state.runGitHub {
                    onConnectGitHub()
                    "Connected to GitHub"
                }
            },
            onLink = {
                state.linkableRepos = emptyList()
                state.linkError = null
                state.linkLoading = true
                scope.launch {
                    try {
                        state.linkableRepos = repository.linkableRepositories()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (
                        @Suppress("TooGenericExceptionCaught")
                        t: Throwable,
                    ) {
                        state.linkError = t.message ?: t.toString()
                    } finally {
                        state.linkLoading = false
                    }
                }
            },
            onPull = { repoId ->
                state.runGitHub {
                    val result = repository.pull(repoId, force = false)
                    val skipped =
                        if (result.skippedLocalEdits > 0) {
                            ", ${result.skippedLocalEdits} kept local (unpushed)"
                        } else {
                            ""
                        }
                    "Pulled ${result.pulled} issue(s), removed ${result.pruned}$skipped"
                }
            },
            onForcePull = { repoId -> state.forcePullTarget = repoId },
            onPush = { repoId ->
                state.runGitHub {
                    val result = repository.push(repoId)
                    describePush(result)
                }
            },
            onUnlink = { repoId ->
                repository.unlinkRepo(repoId)
                if (selectedRepo?.id == repoId) state.selectedRef = null
            },
            onNewIssue = { repoId -> state.newIssueRepo = repoId },
            onToggleComplete = { repoId, number, completed ->
                state.runGitHub {
                    repository.setComplete(repoId, number, completed)
                    if (completed) "Closed the issue on GitHub" else "Reopened the issue on GitHub"
                }
            },
        )
    }
}

/** How many issues a repository has in each state, for the filter chips. */
internal data class IssueCounts(
    val open: Int,
    val closed: Int,
)
