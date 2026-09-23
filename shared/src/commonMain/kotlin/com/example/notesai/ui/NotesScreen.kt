// shared/src/commonMain/kotlin/com/example/notesai/ui/NotesScreen.kt
package com.example.notesai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.notesai.data.NoteRepository
import com.example.notesai.data.noteTitle
import com.example.notesai.db.NoteEntity

@Composable
fun NotesScreen(repository: NoteRepository) {
    val notes by repository.getAllNotes().collectAsState(initial = emptyList<NoteEntity>())
    var selectedId by remember { mutableStateOf<Long?>(null) }

    // Auto-select the first note on first load (only when nothing is selected).
    LaunchedEffect(notes) {
        if (selectedId == null && notes.isNotEmpty()) {
            selectedId = notes.first().id
        }
    }

    val selected = notes.firstOrNull { it.id == selectedId }

    Row(modifier = Modifier.fillMaxSize()) {
        NoteListPane(
            notes = notes,
            selectedId = selectedId,
            onSelect = { selectedId = it },
            onCreate = { selectedId = repository.addNote() },
            onDelete = { id ->
                val next = notes.firstOrNull { it.id != id }?.id
                repository.deleteNote(id)
                if (selectedId == id) selectedId = next
            }
        )

        VerticalDivider()

        NoteEditorPane(
            note = selected,
            onChange = { text -> selected?.let { repository.updateNote(it.id, text) } } // live db update
        )
    }
}

@Composable
private fun NoteListPane(
    notes: List<NoteEntity>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onCreate: () -> Unit,
    onDelete: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight()
            .padding(8.dp)
    ) {
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
            Text("New Note")
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(notes, key = { it.id }) { note ->
                NoteTab(
                    title = note.content.noteTitle().ifBlank { "Untitled" },
                    selected = note.id == selectedId,
                    onClick = { onSelect(note.id) },
                    onDelete = { onDelete(note.id) }
                )
            }
        }
    }
}

@Composable
private fun NoteTab(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDelete) {
            Text("\u2715")
        }
    }
}

@Composable
private fun NoteEditorPane(
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
        placeholder = { Text("Title\u2026") },
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
