package com.example.notesai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.DatabaseDriverFactory
import com.example.notesai.ui.NotesScreen
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.painterResource

import notesai.shared.generated.resources.Res
import notesai.shared.generated.resources.compose_multiplatform
import kotlin.time.Clock

@Composable
fun App(driverFactory: DatabaseDriverFactory) {
    val repository = remember { NoteRepository(driverFactory) }
    MaterialTheme {
        NotesScreen(repository = repository)
    }
}