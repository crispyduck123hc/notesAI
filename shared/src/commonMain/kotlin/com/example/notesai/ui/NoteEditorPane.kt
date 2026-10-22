package com.example.notesai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.example.notesai.ui.theme.Dimens
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

private const val WRITE_DEBOUNCE_MS = 300L

/**
 * Right pane: a single full-size text field bound to the selected note.
 *
 * The field's value is local state (so typing never waits on the DB), and writes are
 * debounced so a burst of keystrokes collapses into one DB write. The first line is
 * rendered as a title.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun NoteEditorPane(
    document: NoteDocument?,
    onChange: (String) -> Unit,
    /** Null for local notes, which have no state to toggle. */
    onToggleComplete: (() -> Unit)? = null,
) {
    if (document == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Select or create a note",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Local state is the source of truth while editing; the DB write is the sink.
    // Keyed on the document's identity so it re-initializes only when a different note is selected,
    // so the async Flow re-emission from our own write can't clobber what's typed.
    val textState = remember(document.ref) { mutableStateOf(document.content) }

    // Debounced persistence: emit only after typing pauses for WRITE_DEBOUNCE_MS.
    LaunchedEffect(document.ref) {
        snapshotFlow { textState.value }
            .drop(1) // ignore the value already persisted
            .debounce(WRITE_DEBOUNCE_MS)
            .collectLatest { onChange(it) }
    }

    // Flush the pending text when switching notes or leaving the screen. `textState`
    // and `onChange` are captured from the composition where this effect launched, so
    // the flush targets the note that was actually being edited.
    DisposableEffect(document.ref) {
        onDispose { onChange(textState.value) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // A GitHub issue has a title of its own, which is not the first line of its body, so it gets a
        // header instead of the first-line styling below.
        val completion = document.completion
        if (completion != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Dimens.EditorPadding, end = Dimens.Gutter, top = Dimens.Gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = document.title.ifBlank { "(no title)" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (completion == Completion.Closed) "Closed" else "Open",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onToggleComplete != null) {
                    Spacer(modifier = Modifier.width(Dimens.Gutter))
                    TextButton(onClick = onToggleComplete) {
                        Text(if (completion == Completion.Closed) "Reopen" else "Mark complete")
                    }
                }
            }
            HorizontalDivider()
        }

        OutlinedTextField(
            value = textState.value,
            onValueChange = { textState.value = it },
            // The first line is a title only for local notes; an issue's body is just a body.
            visualTransformation =
                if (completion == null) TitleHeaderTransformation else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = {
                Text(if (completion == null) "First line becomes the title\u2026" else "Issue body\u2026")
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(Dimens.EditorPadding),
        )
    }
}

// Styles the first line of the field as a title header without changing any
// characters, so OffsetMapping.Identity stays valid.
private val TitleHeaderTransformation =
    VisualTransformation { text ->
        val newlineIndex = text.text.indexOf('\n')
        val firstLineEnd = if (newlineIndex == -1) text.text.length else newlineIndex

        val styled =
            buildAnnotatedString {
                append(text.text)
                addStyle(
                    SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                    start = 0,
                    end = firstLineEnd,
                )
            }

        TransformedText(styled, OffsetMapping.Identity)
    }
