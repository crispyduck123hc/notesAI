package com.example.notesai

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.notesai.db.DatabaseDriverFactory

fun main() = application {
    val driverFactory = DatabaseDriverFactory()
    Window(
        onCloseRequest = ::exitApplication,
        title = "notesAI",
    ) {
        App(driverFactory = driverFactory)
    }
}