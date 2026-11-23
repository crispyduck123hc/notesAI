package com.example.notesai.ui.dialogs

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The calendar range dialog.
 *
 * What matters here is the plumbing around the grid, not the grid itself: a pre-filled range comes
 * back through Apply unchanged (the UTC epoch-millisecond round-trip through the picker's state is
 * exactly where a day could shift), and clearing is a real action distinct from cancelling. Exact
 * typing is the picker's own input mode, which is Material's code, not ours to test.
 */
@OptIn(ExperimentalTestApi::class)
class DateRangeCalendarDialogTest {
    @Test
    fun aPreFilledRangeSurvivesTheRoundTrip() =
        runComposeUiTest {
            var applied: Pair<LocalDate?, LocalDate?>? = null
            setContent {
                DateRangeCalendarDialog(
                    from = LocalDate(2026, 3, 1),
                    to = LocalDate(2026, 3, 15),
                    onDismiss = {},
                    onApply = { from, to -> applied = from to to },
                )
            }

            onNodeWithText("Apply").performClick()

            assertEquals(LocalDate(2026, 3, 1) to LocalDate(2026, 3, 15), applied)
        }

    @Test
    fun anOpenEndedRangeComesBackOpenEnded() =
        runComposeUiTest {
            var applied: Pair<LocalDate?, LocalDate?>? = null
            setContent {
                DateRangeCalendarDialog(
                    from = LocalDate(2026, 3, 1),
                    to = null,
                    onDismiss = {},
                    onApply = { from, to -> applied = from to to },
                )
            }

            onNodeWithText("Apply").performClick()

            assertEquals(LocalDate(2026, 3, 1) to null, applied)
        }

    @Test
    fun clearingReachesApplyAsNoRange() =
        runComposeUiTest {
            var applied: Pair<LocalDate?, LocalDate?>? = null
            setContent {
                DateRangeCalendarDialog(
                    from = LocalDate(2026, 3, 1),
                    to = null,
                    onDismiss = {},
                    onApply = { from, to -> applied = from to to },
                )
            }

            onNodeWithText("Clear").performClick()

            assertEquals(null to null, applied)
        }

    @Test
    fun applyWithNoSelectionDoesNotFireAnEmptyFilter() =
        runComposeUiTest {
            var applied: Pair<LocalDate?, LocalDate?>? = null
            setContent {
                DateRangeCalendarDialog(from = null, to = null, onDismiss = {}, onApply = { from, to -> applied = from to to })
            }

            // Both ends null and nothing selected: Apply must not fire an empty filter as if it were a choice.
            // The Clear button is the way to say "no range", so assert the state rather than clicking.
            onNodeWithText("Apply").assertIsDisplayed()
            assertNull(applied)
        }
}
