package com.example.notesai.github

import com.example.notesai.db.GitHubIssueNoteEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Which issues the folder shows.
 *
 * A view concern: the mirror keeps every issue, so the filter is only ever a decision about rows. The reason it is
 * worth its own type and its own test is that the next phase adds a time range, and every caller already passes the
 * whole filter rather than a pair of switches.
 */
class IssueFilterTest {
    private fun issue(
        number: Long,
        state: String,
        created: String = "2026-01-01T00:00:00Z",
    ) = GitHubIssueNoteEntity(
        id = number,
        repoId = 1,
        number = number,
        title = "issue $number",
        body = "",
        state = state,
        remoteUpdatedAt = "2026-01-01T00:00:00Z",
        dirty = 0,
        pulledAt = 0,
        createdAt = created,
    )

    private val open = issue(1, "open")
    private val closed = issue(2, "closed")

    @Test
    fun bothStatesIsEverything() {
        val filter = IssueFilter()

        assertTrue(filter.matches(open))
        assertTrue(filter.matches(closed))
        assertFalse(filter.isEmpty)
    }

    @Test
    fun openOnlyHidesClosed() {
        val filter = IssueFilter(showClosed = false)

        assertTrue(filter.matches(open))
        assertFalse(filter.matches(closed))
    }

    @Test
    fun closedOnlyHidesOpen() {
        val filter = IssueFilter(showOpen = false)

        assertFalse(filter.matches(open))
        assertTrue(filter.matches(closed))
    }

    @Test
    fun neitherIsAnEmptyFilterRatherThanAnEmptyResult() {
        val filter = IssueFilter(showOpen = false, showClosed = false)

        assertTrue(filter.isEmpty, "the UI can say so instead of showing an unexplained empty tree")
        assertEquals(emptyList(), listOf(open, closed).filteredBy(filter))
    }

    @Test
    fun anIssueStateThisAppDoesNotKnowAboutIsTreatedAsOpen() {
        // GitHub has states beyond open/closed in other products; the mirror should not lose an issue to a filter
        // that has never heard of its state.
        assertTrue(IssueFilter(showClosed = false).matches("in progress"))
    }

    @Test
    fun togglingFlipsOneDimensionAtATime() {
        val filter = IssueFilter()

        assertEquals(IssueFilter(showOpen = false), filter.togglingOpen())
        assertEquals(IssueFilter(showClosed = false), filter.togglingClosed())
        assertEquals(filter, filter.togglingOpen().togglingOpen(), "twice is back where it started")
    }

    @Test
    fun theListHelperKeepsTheOrderItWasGiven() {
        val third = issue(3, "open")

        assertEquals(listOf(open, third), listOf(open, closed, third).filteredBy(IssueFilter(showClosed = false)))
    }

    // ---- Opened within: the date dimension --------------------------------------------------------

    private val now = Instant.parse("2026-03-01T12:00:00Z")

    private fun issueCreated(ago: Duration) = issue(9, "open", created = (now - ago).toString())

    @Test
    fun anyTimeShowsEverythingWhateverItsAge() {
        val filter = IssueFilter()

        assertTrue(filter.matches(issueCreated(3650.days), now))
    }

    @Test
    fun theDayRangeKeepsOnlyTheLastDay() {
        val filter = IssueFilter(createdWithin = CreatedWithin.LAST_DAY)

        assertTrue(filter.matches(issueCreated(2.hours), now), "two hours ago is inside a day")
        assertTrue(filter.matches(issueCreated(24.hours), now), "and so is exactly a day, on the boundary")
        assertFalse(filter.matches(issueCreated(25.hours), now), "a day and an hour is outside")
    }

    @Test
    fun theWeekAndMonthRangesHaveTheirOwnBoundaries() {
        val week = IssueFilter(createdWithin = CreatedWithin.LAST_WEEK)
        val month = IssueFilter(createdWithin = CreatedWithin.LAST_MONTH)

        assertTrue(week.matches(issueCreated(6.days), now))
        assertFalse(week.matches(issueCreated(8.days), now))
        assertTrue(month.matches(issueCreated(29.days), now))
        assertFalse(month.matches(issueCreated(31.days), now))
    }

    @Test
    fun anUnknownCreationDateNeverPassesADateFilter() {
        // Rows pulled before the column existed are empty. Unknown is not recent — and the next pull fills them in.
        val filter = IssueFilter(createdWithin = CreatedWithin.LAST_WEEK)

        assertFalse(filter.matchesCreated("", now))
        assertFalse(filter.matchesCreated("not a timestamp", now), "and neither is nonsense")
        assertTrue(IssueFilter().matchesCreated("", now), "but an unfiltered view still shows it")
    }

    @Test
    fun theTwoDimensionsApplyTogether() {
        val filter = IssueFilter(showClosed = false, createdWithin = CreatedWithin.LAST_WEEK)

        assertTrue(filter.matches(issue(1, "open", created = (now - 1.days).toString()), now))
        assertFalse(filter.matches(issue(2, "closed", created = (now - 1.days).toString()), now), "state says no")
        assertFalse(filter.matches(issue(3, "open", created = (now - 30.days).toString()), now), "date says no")
    }

    @Test
    fun choosingARangeKeepsTheOtherDimension() {
        val filter = IssueFilter(showOpen = false).createdWithin(CreatedWithin.LAST_MONTH)

        assertFalse(filter.showOpen, "picking a range must not silently reset the state chips")
        assertEquals(CreatedWithin.LAST_MONTH, filter.createdWithin)
    }

    @Test
    fun filteringAListUsesTheSameClock() {
        val issues = listOf(issue(1, "open", created = (now - 1.days).toString()), issue(2, "open", created = (now - 10.days).toString()))

        assertEquals(listOf(1L), issues.filteredBy(IssueFilter(createdWithin = CreatedWithin.LAST_WEEK), now).map { it.number })
    }
}
