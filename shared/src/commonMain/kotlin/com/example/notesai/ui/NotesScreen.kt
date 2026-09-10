// shared/src/commonMain/kotlin/com/example/notesai/ui/NotesScreen.kt
package com.example.notesai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NoteEntity

@Composable
fun NotesScreen(repository: NoteRepository) {
    val notes by repository.getAllNotes().collectAsState(initial = emptyList<NoteEntity>())

    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Input Fields
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Note Title") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text("Note Content") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (title.isNotBlank() && content.isNotBlank()) {
                    repository.addNote(title, content)
                    title = ""
                    content = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Note")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Divider()

        // List of Notes
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(notes) { note ->
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(text = note.title, style = MaterialTheme.typography.titleMedium)
                    Text(text = note.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}