package com.example.notesai

import androidx.compose.ui.window.ComposeUIViewController
import com.example.notesai.db.DatabaseDriverFactory

fun MainViewController() = ComposeUIViewController {
    val driverFactory = DatabaseDriverFactory()
    App(driverFactory = driverFactory)
}