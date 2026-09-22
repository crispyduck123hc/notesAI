// shared/src/commonMain/kotlin/com/example/notesai/ui/NotesScreen.kt
package com.example.notesai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NoteEntity

@Composable
fun NotesScreen(repository: NoteRepository) {
    val notes by repository.getAllNotes().collectAsState(initial = emptyList<NoteEntity>())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = { repository.addNote() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("New Note")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Divider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(notes, key = { it.id }) { note ->
                NoteRow(
                    note = note,
                    onUpdate = { title, content -> repository.updateNote(note.id, title, content) },
                    onDelete = { repository.deleteNote(note.id) }
                )
                Divider()
            }
        }
    }
}

@Composable
private fun NoteRow(
    note: NoteEntity,
    onUpdate: (title: String, content: String) -> Unit,
    onDelete: () -> Unit
) {
    // Local state is the source of truth while editing; the DB write is the sink.
    // remember(note.id) re-initializes only when a different note is shown, so the
    // async Flow re-emission from our own write doesn't clobber what's being typed.
    var title by remember(note.id) { mutableStateOf(note.title) }
    var content by remember(note.id) { mutableStateOf(note.content) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    onUpdate(it, content)
                },
                label = { Text("Title") },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onDelete) {
                Text("Delete")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = content,
            onValueChange = {
                content = it
                onUpdate(title, it)
            },
            label = { Text("Content") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
