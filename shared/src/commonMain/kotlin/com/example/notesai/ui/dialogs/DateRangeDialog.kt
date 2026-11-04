package com.example.notesai.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.example.notesai.ui.theme.Dimens
import kotlinx.datetime.LocalDate

/**
 * Asks for a calendar range: issues opened between two days, either end optional.
 *
 * Typed dates rather than a month grid: the range is usually known ("since the 1st"), typing it is two fields, and a
 * grid would need a second screenful for the other end. The format is spelled out because a plain text field has no
 * room to be clever, and a wrong format gets a message rather than a silent no-op.
 *
 * Both ends are inclusive — "1 March to 15 March" includes the 15th — which is what the person choosing means.
 */
@Composable
internal fun DateRangeDialog(
    from: LocalDate?,
    to: LocalDate?,
    onDismiss: () -> Unit,
    onApply: (from: LocalDate?, to: LocalDate?) -> Unit,
) {
    var fromText by remember(from) { mutableStateOf(from?.toString().orEmpty()) }
    var toText by remember(to) { mutableStateOf(to?.toString().orEmpty()) }

    val parsedFrom = parseDateOrNull(fromText)
    val parsedTo = parseDateOrNull(toText)
    val problem =
        when {
            fromText.isNotBlank() && parsedFrom == null -> "From needs a date like 2026-03-01"
            toText.isNotBlank() && parsedTo == null -> "To needs a date like 2026-03-15"
            parsedFrom != null && parsedTo != null && parsedFrom > parsedTo -> "The first date is after the second"
            else -> null
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Opened between") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { fromText = it },
                    label = { Text("From (YYYY-MM-DD)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = toText,
                    onValueChange = { toText = it },
                    label = { Text("To (YYYY-MM-DD)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(top = Dimens.Gutter),
                )
                Text(
                    text = problem ?: "Leave either field empty for an open-ended range.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimens.Gutter),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(parsedFrom, parsedTo) },
                enabled = problem == null,
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            // Clearing is its own action: "no range" is a real choice, not the same as cancelling the dialog.
            TextButton(
                onClick = { onApply(null, null) },
                enabled = fromText.isNotBlank() || toText.isNotBlank() || from != null || to != null,
            ) {
                Text("Clear")
            }
        },
    )
}

/** A typed date, or null when it is empty or not a date at all. */
internal fun parseDateOrNull(text: String): LocalDate? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return runCatching { LocalDate.parse(trimmed) }.getOrNull()
}
