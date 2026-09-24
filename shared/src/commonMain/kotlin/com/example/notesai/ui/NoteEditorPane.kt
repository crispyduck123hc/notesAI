package com.example.notesai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * Right pane: a single full-size text field bound to the selected note, written
 * through to the database on every keystroke. The first line is rendered as a title.
 */
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
    // remember(note.id) re-initializes only when a different note is selected, so the
    // async Flow re-emission from our own write doesn't clobber what's being typed.
    var text by remember(note.id) { mutableStateOf(note.content) }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it) // live write on every keystroke
        },
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
