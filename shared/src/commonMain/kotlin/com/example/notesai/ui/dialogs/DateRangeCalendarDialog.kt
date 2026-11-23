package com.example.notesai.ui.dialogs

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.notesai.ui.theme.Dimens
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * Asks for a calendar range on a month grid: issues opened between two days, either end optional.
 *
 * Material 3's `DateRangePicker` — two taps select start and end, and a single tap selects an
 * open-ended range ("since that day"), which is the same semantics the typed dialog had. The
 * selection arrives as UTC epoch milliseconds, and the picker's calendar model resolves them in
 * UTC (`getCanonicalDate` reads the instant with `TimeZone.UTC`), so both conversion helpers here
 * use UTC — otherwise a day chosen on the grid could come back as its neighbour. `LocalDate` is
 * what `IssueFilter` stores, so the filter's own day-boundary math is untouched.
 *
 * The typed-fields dialog stays reachable as a keyboard fallback — the range is often known
 * exactly ("since the 1st"), and two fields is faster than a grid for that.
 *
 * Both ends are inclusive — "1 March to 15 March" includes the 15th — which is what the person
 * choosing means. `IssueFilter` applies `createdTo` as [createdTo + 1 day), so the picker's
 * inclusive end maps through unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateRangeCalendarDialog(
    from: LocalDate?,
    to: LocalDate?,
    onDismiss: () -> Unit,
    onApply: (from: LocalDate?, to: LocalDate?) -> Unit,
) {
    var showTyped by remember { mutableStateOf(false) }
    if (showTyped) {
        DateRangeDialog(from = from, to = to, onDismiss = onDismiss, onApply = onApply)
        return
    }

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
            title = { Text("Opened between") },
        )
        TextButton(
            onClick = { showTyped = true },
            modifier = Modifier.padding(start = Dimens.Gutter, bottom = Dimens.Gutter),
        ) {
            Text("Type dates\u2026")
        }
    }
}

/** [this] as the millisecond the picker's calendar model treats as that day's start (UTC midnight). */
private fun LocalDate.startOfDayMillis(): Long = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

/**
 * A UTC epoch millisecond from the picker, read back as the calendar day it stands for — in UTC,
 * because that is the zone the picker's own day arithmetic runs in (see [DateRangeCalendarDialog]).
 */
private fun Long?.toLocalDate(): LocalDate? =
    this?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date }
