package com.example.notesai.ui.dialogs

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The calendar range dialog.
 *
 * Two typed dates, because a month grid is two screens for a range and the dates are usually known. What matters is
 * that a wrong date says so instead of quietly doing nothing, and that "clear" is distinguishable from "cancel".
 */
@OptIn(ExperimentalTestApi::class)
class DateRangeDialogTest {
    @Test
    fun parseAcceptanceAndRejection() {
        assertEquals(LocalDate(2026, 3, 1), parseDateOrNull("2026-03-01"))
        assertEquals(LocalDate(2026, 3, 1), parseDateOrNull("  2026-03-01 "))
        assertNull(parseDateOrNull(""))
        assertNull(parseDateOrNull("1 March"))
        assertNull(parseDateOrNull("2026-13-01"), "there is no month 13")
    }

    @Test
    fun applyingReportsTheTwoDates() =
        runComposeUiTest {
            var applied: Pair<LocalDate?, LocalDate?>? = null
            setContent {
                DateRangeDialog(
                    from = null,
                    to = null,
                    onDismiss = {},
                    onApply = { from, to -> applied = from to to },
                )
            }

            onAllNodes(hasSetTextAction())[0].performTextInput("2026-03-01")
            onAllNodes(hasSetTextAction())[1].performTextInput("2026-03-15")
            onNodeWithText("Apply").performClick()

            assertEquals(LocalDate(2026, 3, 1) to LocalDate(2026, 3, 15), applied)
        }

    @Test
    fun anUnparseableDateDisablesApplyAndSaysWhy() =
        runComposeUiTest {
            setContent {
                DateRangeDialog(from = null, to = null, onDismiss = {}, onApply = { _, _ -> })
            }

            onAllNodes(hasSetTextAction())[0].performTextInput("March")

            onNodeWithText("Apply").assertIsNotEnabled()
            onNodeWithText("like 2026-03-01", substring = true).assertExists()
        }

    @Test
    fun aRangeThatRunsBackwardsIsRefused() =
        runComposeUiTest {
            setContent {
                DateRangeDialog(from = null, to = null, onDismiss = {}, onApply = { _, _ -> })
            }

            onAllNodes(hasSetTextAction())[0].performTextInput("2026-03-15")
            onAllNodes(hasSetTextAction())[1].performTextInput("2026-03-01")

            onNodeWithText("Apply").assertIsNotEnabled()
            onNodeWithText("after the second", substring = true).assertExists()
        }

    @Test
    fun clearHandsBackAnEmptyRangeRatherThanDismissing() =
        runComposeUiTest {
            var applied: Pair<LocalDate?, LocalDate?>? = null
            var dismissed = false
            setContent {
                DateRangeDialog(
                    from = LocalDate(2026, 3, 1),
                    to = LocalDate(2026, 3, 15),
                    onDismiss = { dismissed = true },
                    onApply = { from, to -> applied = from to to },
                )
            }

            // The dialog opens showing the range that is in force, so it can be adjusted rather than retyped.
            onNodeWithText("2026-03-01", substring = true).assertExists()
            onNodeWithText("Clear").performClick()

            assertEquals(null to null, applied)
            assertEquals(false, dismissed, "clearing is not cancelling")
        }

    @Test
    fun editingAnExistingRangeKeepsTheOtherEnd() =
        runComposeUiTest {
            var applied: Pair<LocalDate?, LocalDate?>? = null
            setContent {
                DateRangeDialog(
                    from = LocalDate(2026, 3, 1),
                    to = LocalDate(2026, 3, 15),
                    onDismiss = {},
                    onApply = { from, to -> applied = from to to },
                )
            }

            onAllNodes(hasSetTextAction())[1].performTextReplacement("")
            onNodeWithText("Apply").assertIsEnabled()
            onNodeWithText("Apply").performClick()

            assertEquals(LocalDate(2026, 3, 1) to null, applied)
        }
}
