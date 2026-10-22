package com.example.notesai.ui

import com.example.notesai.db.GitHubRepoEntity

/**
 * Everything the tree pane needs to know about the GitHub folder, gathered in one place so the pane
 * does not grow a dozen more parameters.
 *
 * Callbacks are plain lambdas on purpose: this is a UI wiring bag, not a value type. It carries
 * eleven members because the pane needs them together; splitting it further would only move the
 * parameters somewhere else, which is why `LongParameterList` is suppressed here.
 */
@Suppress("LongParameterList")
internal class GitHubPaneState(
    /** Null when GitHub is not connected; the pane offers "Connect GitHub…" instead of linking. */
    val login: String?,
    val repos: List<GitHubRepoEntity>,
    /** The repository whose node is selected, if any — its actions are shown under the tree. */
    val selectedRepo: GitHubRepoEntity?,
    /** True while a pull, push or completion is in flight, so the buttons can be disabled. */
    val busy: Boolean,
    val onConnect: () -> Unit,
    val onLink: () -> Unit,
    val onPull: (repoId: Long) -> Unit,
    val onForcePull: (repoId: Long) -> Unit,
    val onPush: (repoId: Long) -> Unit,
    val onUnlink: (repoId: Long) -> Unit,
    /** Closes or reopens an issue. Offered from the row menu and the editor. */
    val onToggleComplete: (repoId: Long, number: Long, completed: Boolean) -> Unit,
)
