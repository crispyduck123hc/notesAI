package com.example.notesai.github

import com.example.notesai.db.GitHubIssueNoteEntity

/**
 * Which of a repository's issues the folder shows.
 *
 * A view concern, deliberately: the mirror keeps every issue it has pulled, so hiding one here costs nothing and
 * changing the filter back shows it again. Nothing is deleted, and nothing about the pull changes.
 *
 * The obvious next dimension is a time range — last 24 hours, 7 days, a month, then a calendar range. It belongs
 * here as fields with defaults, and in [matches], because every caller passes the whole filter rather than the
 * individual switches: adding it will not touch the tree, the pane or the screen.
 */
data class IssueFilter(
    val showOpen: Boolean = true,
    val showClosed: Boolean = true,
) {
    /** True when the issue's state is one the reader asked to see. */
    fun matches(issue: GitHubIssueNoteEntity): Boolean = matches(issue.state)

    fun matches(state: String): Boolean =
        when (state) {
            "closed" -> showClosed
            else -> showOpen
        }

    /** Nothing selected is a filter that hides everything — worth saying rather than showing an empty list. */
    val isEmpty: Boolean get() = !showOpen && !showClosed

    fun togglingOpen(): IssueFilter = copy(showOpen = !showOpen)

    fun togglingClosed(): IssueFilter = copy(showClosed = !showClosed)
}

/** The issues this filter lets through, in the order they came in. */
fun List<GitHubIssueNoteEntity>.filteredBy(filter: IssueFilter): List<GitHubIssueNoteEntity> = filter(filter::matches)
