package com.example.notesai.github

import com.example.notesai.db.GitHubIssueNoteEntity
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * How far back a reader wants to look, by when the issue was opened.
 *
 * Relative ranges only for now; a calendar range is the same dimension with both ends pinned, which is why the
 * filter holds [createdWithin] rather than a pair of dates.
 */
enum class CreatedWithin(
    val label: String,
    val span: Duration?,
) {
    ANY_TIME("Any", null),
    LAST_DAY("24h", 24.hours),
    LAST_WEEK("7d", 7.days),
    LAST_MONTH("1m", 30.days),
}

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
    /** How recently the issue must have been opened. [CreatedWithin.ANY_TIME] means no date filter. */
    val createdWithin: CreatedWithin = CreatedWithin.ANY_TIME,
) {
    /** True when the issue passes every dimension of the filter. [now] is passed in so tests stay deterministic. */
    fun matches(
        issue: GitHubIssueNoteEntity,
        now: Instant = Clock.System.now(),
    ): Boolean = matches(issue.state) && matchesCreated(issue.createdAt, now)

    /** The state dimension on its own. */
    fun matches(state: String): Boolean =
        when (state) {
            "closed" -> showClosed
            else -> showOpen
        }

    /**
     * The date dimension on its own.
     *
     * An issue whose creation date the app does not know — rows pulled before the column existed — never passes a
     * date filter. Unknown is not recent, and the next pull fills those rows in.
     */
    @Suppress("ReturnCount") // "no range", "no date" and "too old" are three different answers
    fun matchesCreated(
        createdAt: String,
        now: Instant = Clock.System.now(),
    ): Boolean {
        val span = createdWithin.span ?: return true
        val created = createdAt.toInstantOrNull() ?: return false
        val age = now - created
        return age <= span
    }

    /** Nothing selected is a filter that hides everything — worth saying rather than showing an empty list. */
    val isEmpty: Boolean get() = !showOpen && !showClosed

    fun togglingOpen(): IssueFilter = copy(showOpen = !showOpen)

    fun togglingClosed(): IssueFilter = copy(showClosed = !showClosed)

    fun createdWithin(range: CreatedWithin): IssueFilter = copy(createdWithin = range)
}

/** [this] as an instant, or null when it is empty or not a timestamp at all. */
internal fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

/** The issues this filter lets through, in the order they came in. */
fun List<GitHubIssueNoteEntity>.filteredBy(
    filter: IssueFilter,
    now: Instant = Clock.System.now(),
): List<GitHubIssueNoteEntity> = filter { filter.matches(it, now) }
