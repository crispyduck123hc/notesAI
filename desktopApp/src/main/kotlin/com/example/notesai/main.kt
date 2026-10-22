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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.notesai.auth.TokenSlot
import com.example.notesai.auth.desktopTokenStore
import com.example.notesai.db.DatabaseDriverFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long to wait before reading the database, so a keystroke still sitting in the editor's
 * write-debounce lands first.
 */
private const val SHUTDOWN_GRACE_MILLIS = 500L

/** Never hold the user's window open longer than this for a best-effort upload. */
private const val SHUTDOWN_TIMEOUT_MILLIS = 3_000L

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
        val exitFlush = remember { ExitFlush() }
        val scope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }

        Window(
            onCloseRequest = {
                // Closing a window is the one reliable "you are about to quit" moment any platform
                // gives us, so drain the outbox here. It stays best-effort: a hard timeout, and exit
                // regardless — the queue is durable, so losing this race costs a retry at the next
                // launch, not data.
                if (!closing) {
                    closing = true
                    scope.launch {
                        if (exitFlush.hasQueuedWork()) {
                            delay(SHUTDOWN_GRACE_MILLIS)
                            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) { exitFlush.run() }
                        }
                        exitApplication()
                    }
                }
            },
            title = "notesAI",
        ) {
            App(
                driverFactory = driverFactory,
                tokenStore = tokenStore,
                gitHubTokenStore = gitHubTokenStore,
                exitFlush = exitFlush,
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
