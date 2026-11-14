package com.example.notesai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.notesai.PendingEdit
import com.example.notesai.auth.TokenSlot
import com.example.notesai.auth.desktopTokenStore
import com.example.notesai.db.DatabaseDriverFactory

fun main() {
    // One instance per data directory. Two copies share a database and a Google account, which makes
    // them two editors writing over each other's notes — the input the sync engine turns into
    // duplicates.
    val acquisition = SingleInstanceLock.acquire()
    if (acquisition is LockAcquisition.AlreadyRunning) {
        // Also on stdout: a GUI app launched by double-click has no other way to say this, and it
        // makes the refusal visible in a log rather than only in a window.
        println("[notesAI] another instance is already running; not opening a second window")
        showAlreadyRunning()
        return
    }

    val lock = (acquisition as? LockAcquisition.Acquired)?.lock
    try {
        runApp()
    } finally {
        lock?.release()
    }
}

private fun runApp() =
    application {
        val driverFactory = DatabaseDriverFactory()
        val tokenStore = desktopTokenStore()
        // GitHub gets its own credential slot, so the two providers sign in and out independently.
        val gitHubTokenStore = desktopTokenStore(TokenSlot.GITHUB)
        val pendingEdit = remember { PendingEdit() }
        var closing by remember { mutableStateOf(false) }

        Window(
            onCloseRequest = {
                // Quitting waits for one local transaction and nothing else.
                //
                // The editor's write is debounced, so the last keystrokes are only in the composition, and
                // `commit()` writes them now — a note update and its outbox row in a single SQLite transaction. That
                // transaction is the durable record of what is owed, so from here on the queue, not the window,
                // owns delivery: the next pass on this device, or the next launch after a quit, does it.
                //
                // Deliberately no upload here. A pass started at quit is the one request most likely to be cut off
                // after the server has already taken the bytes, which is the ambiguity the engine resolves by
                // fingerprinting what it sends: a file whose content matches that fingerprint is recognised as this
                // device's own write on the next pass, so it is adopted instead of copied.
                if (!closing) {
                    closing = true
                    // A quit is not the moment to report a failure: if the write cannot be made, say so and close
                    // anyway. Refusing to close would trap the user in a window because the disk is full.
                    runCatching { pendingEdit.commit() }
                        .onFailure { println("[notesAI] could not save the open note on quit: ${it.message}") }
                    exitApplication()
                }
            },
            title = "notesAI",
        ) {
            App(
                driverFactory = driverFactory,
                tokenStore = tokenStore,
                gitHubTokenStore = gitHubTokenStore,
                pendingEdit = pendingEdit,
            )
        }
    }

/**
 * Says so rather than exiting silently: from a double-click, an app that vanishes looks like a
 * crash. There is no console to print to on a desktop launch.
 */
private fun showAlreadyRunning() =
    application {
        Window(onCloseRequest = { exitApplication() }, title = "notesAI") {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
            ) {
                Text(
                    text = "notesAI is already running.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text =
                        "Two copies would share one database and one Google account, and would " +
                            "write over each other's notes. Switch to the window that is already open.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { exitApplication() }) {
                    Text("Close")
                }
            }
        }
    }
