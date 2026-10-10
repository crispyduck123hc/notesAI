package com.example.notesai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
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

fun main() = application {
    val driverFactory = DatabaseDriverFactory()
    val tokenStore = desktopTokenStore()
    val exitFlush = remember { ExitFlush() }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            // Closing a window is the one reliable "you are about to quit" moment any platform
            // gives us, so drain the outbox here. It stays best-effort: a hard timeout, and
            // exit regardless — the queue is durable, so losing this race costs a retry at the
            // next launch, not data.
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
            exitFlush = exitFlush,
        )
    }
}
