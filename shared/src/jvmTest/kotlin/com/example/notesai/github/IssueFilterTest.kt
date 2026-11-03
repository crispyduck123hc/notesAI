package com.example.notesai.github

import com.example.notesai.db.GitHubIssueNoteEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
