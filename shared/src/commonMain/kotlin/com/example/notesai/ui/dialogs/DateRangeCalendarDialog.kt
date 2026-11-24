package com.example.notesai.ui.dialogs

import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Asks for a calendar range on a month grid: issues opened between two days, either end optional.
 *
 * Material 3's `DateRangePicker` — two taps select start and end, and a single tap selects an
 * open-ended range ("since that day"), which is the same semantics `IssueFilter` applies (both
 * ends inclusive; `createdTo` is applied as [createdTo + 1 day), so the picker's inclusive end
 * maps through unchanged). Exact dates are typed through the picker's own input-mode toggle —
 * the headline switch — which is why this dialog adds no text fields of its own.
 *
 * The selection arrives as UTC epoch milliseconds, and the picker's calendar model resolves them
 * in UTC (`getCanonicalDate` reads the instant with `TimeZone.UTC`), so both conversion helpers
 * here use UTC — otherwise a day chosen on the grid could come back as its neighbour. `LocalDate`
 * is what `IssueFilter` stores, so the filter's own day-boundary math is untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangeCalendarDialog(
    from: LocalDate?,
    to: LocalDate?,
    onDismiss: () -> Unit,
    onApply: (from: LocalDate?, to: LocalDate?) -> Unit,
) {
    val state =
        rememberDateRangePickerState(
            initialSelectedStartDateMillis = from?.startOfDayMillis(),
            initialSelectedEndDateMillis = to?.startOfDayMillis(),
        )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(state.selectedStartDateMillis.toLocalDate(), state.selectedEndDateMillis.toLocalDate())
                },
                enabled = state.selectedStartDateMillis != null || state.selectedEndDateMillis != null,
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            // Clearing is its own action: "no range" is a real choice, not the same as cancelling the dialog.
            TextButton(
                onClick = { onApply(null, null) },
                enabled = from != null || to != null,
            ) {
                Text("Clear")
            }
        },
    ) {
        DateRangePicker(
            state = state,
            // The headline's calendar/input-mode switch is the only way to type exact dates, so it
            // stays; a second typed-fields path beside it would be two ways to do one thing.
            showModeToggle = true,
        )
    }
}

/** [this] as the millisecond the picker's calendar model treats as that day's start (UTC midnight). */
private fun LocalDate.startOfDayMillis(): Long = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

/**
 * A UTC epoch millisecond from the picker, read back as the calendar day it stands for — in UTC,
 * because that is the zone the picker's own day arithmetic runs in (see [DateRangeCalendarDialog]).
 */
private fun Long?.toLocalDate(): LocalDate? = this?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
