package com.example.notesai.github

import com.example.notesai.db.GitHubIssueNoteEntity
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
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

    /** Both ends chosen in the dialog; [IssueFilter.createdFrom] and [IssueFilter.createdTo] carry them. */
    CUSTOM("Custom", null),
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
    /**
     * The chosen calendar range, inclusive at both ends, as whole days.
     *
     * Days rather than instants because that is what a person picks: "opened between 1 March and 15 March" means the
     * 15th too, so the bounds are widened to the start of the first day and the end of the last. Either end may be
     * null on its own — "since March" and "up to March" are both useful ranges.
     */
    val createdFrom: LocalDate? = null,
    val createdTo: LocalDate? = null,
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
    @Suppress("ReturnCount") // "no date", "unknown date", "outside the range" are three different answers
    fun matchesCreated(
        createdAt: String,
        now: Instant = Clock.System.now(),
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Boolean {
        val inCalendarRange = createdFrom != null || createdTo != null
        if (createdWithin.span == null && !inCalendarRange) return true

        // Unknown is not recent, and it is not inside a chosen range either.
        val created = createdAt.toInstantOrNull() ?: return false

        if (createdWithin == CreatedWithin.CUSTOM || inCalendarRange) {
            if (!withinChosenDays(created, timeZone)) return false
        }
        val span = createdWithin.span ?: return true
        return now - created <= span
    }

    /**
     * Whether [created] falls inside the chosen days.
     *
     * A day is a local day, so the comparison happens in the reader's own zone: an issue opened at 23:00 on the last
     * chosen day still counts, which it would not if the bounds were taken as UTC midnight.
     */
    private fun withinChosenDays(
        created: Instant,
        timeZone: TimeZone,
    ): Boolean {
        val lowerBound = createdFrom?.atStartOfDayIn(timeZone) ?: Instant.DISTANT_PAST
        // Inclusive at the top: the chosen last day counts in full, so the bound is the start of the next one.
        val upperBoundExclusive =
            createdTo?.plus(1, DateTimeUnit.DAY)?.atStartOfDayIn(timeZone) ?: Instant.DISTANT_FUTURE
        return created >= lowerBound && created < upperBoundExclusive
    }

    /** Nothing selected is a filter that hides everything — worth saying rather than showing an empty list. */
    val isEmpty: Boolean get() = !showOpen && !showClosed

    /** A short description of the calendar range, for the chip. */
    val calendarRangeLabel: String?
        get() =
            when {
                createdFrom != null && createdTo != null -> "$createdFrom – $createdTo"
                createdFrom != null -> "since $createdFrom"
                createdTo != null -> "until $createdTo"
                else -> null
            }

    fun togglingOpen(): IssueFilter = copy(showOpen = !showOpen)

    fun togglingClosed(): IssueFilter = copy(showClosed = !showClosed)

    fun createdWithin(range: CreatedWithin): IssueFilter =
        copy(
            createdWithin = range,
            // Only the custom entry keeps dates; picking a relative range clears them, and clearing the range clears
            // both, so the chips and the dates can never disagree.
            createdFrom = if (range == CreatedWithin.CUSTOM) createdFrom else null,
            createdTo = if (range == CreatedWithin.CUSTOM) createdTo else null,
        )

    /** The calendar range from the dialog. A null [to] means "up to today", a null [from] means "since forever". */
    fun createdBetween(
        from: LocalDate?,
        to: LocalDate?,
    ): IssueFilter =
        copy(
            createdWithin = if (from == null && to == null) CreatedWithin.ANY_TIME else CreatedWithin.CUSTOM,
            createdFrom = from,
            createdTo = to,
        )
}

/** [this] as an instant, or null when it is empty or not a timestamp at all. */
internal fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

/** The issues this filter lets through, in the order they came in. */
fun List<GitHubIssueNoteEntity>.filteredBy(
    filter: IssueFilter,
    now: Instant = Clock.System.now(),
): List<GitHubIssueNoteEntity> = filter { filter.matches(it, now) }
