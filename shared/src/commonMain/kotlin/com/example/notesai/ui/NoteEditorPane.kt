package com.example.notesai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.notesai.db.NoteEntity
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
    note: NoteEntity?,
    onChange: (String) -> Unit
) {
    if (note == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Select or create a note",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // Local state is the source of truth while editing; the DB write is the sink.
    // Keyed on note.id so it re-initializes only when a different note is selected,
    // so the async Flow re-emission from our own write can't clobber what's typed.
    val textState = remember(note.id) { mutableStateOf(note.content) }

    // Debounced persistence: emit only after typing pauses for WRITE_DEBOUNCE_MS.
    LaunchedEffect(note.id) {
        snapshotFlow { textState.value }
            .drop(1) // ignore the value already persisted
            .debounce(WRITE_DEBOUNCE_MS)
            .collectLatest { onChange(it) }
    }

    // Flush the pending text when switching notes or leaving the screen. `textState`
    // and `onChange` are captured from the composition where this effect launched, so
    // the flush targets the note that was actually being edited.
    DisposableEffect(note.id) {
        onDispose { onChange(textState.value) }
    }

    OutlinedTextField(
        value = textState.value,
        onValueChange = { textState.value = it },
        visualTransformation = TitleHeaderTransformation,
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { Text("First line becomes the title\u2026") },
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    )
}

// Styles the first line of the field as a title header without changing any
// characters, so OffsetMapping.Identity stays valid.
private val TitleHeaderTransformation = VisualTransformation { text ->
    val newlineIndex = text.text.indexOf('\n')
    val firstLineEnd = if (newlineIndex == -1) text.text.length else newlineIndex

    val styled = buildAnnotatedString {
        append(text.text)
        addStyle(
            SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
            start = 0,
            end = firstLineEnd
        )
    }

    TransformedText(styled, OffsetMapping.Identity)
}
