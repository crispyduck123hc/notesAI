package com.example.notesai.ui
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.notesai.data.NoteRepository

@Composable
fun NotesScreen(repository: NoteRepository) {
    val notes by repository.getAllNotes().collectAsState(initial = emptyList())

    LazyColumn {
        items(notes) { note ->
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = note.title, style = MaterialTheme.typography.titleMedium)
                Text(text = note.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}