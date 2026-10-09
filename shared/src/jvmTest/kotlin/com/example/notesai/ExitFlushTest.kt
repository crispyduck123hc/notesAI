package com.example.notesai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExitFlushTest {

    @Test
    fun runsTheRegisteredHandlerOnlyWhileSomethingIsQueued() = runBlocking {
        val exitFlush = ExitFlush()

        // Nothing signed in yet: the exit path must not keep the process alive.
        assertFalse(exitFlush.hasQueuedWork())
        exitFlush.run() // no handler registered: must be a harmless no-op

        var flushed = false
        exitFlush.register(pendingCount = { 3 }, flush = { flushed = true })
        assertTrue(exitFlush.hasQueuedWork())

        exitFlush.run()
        assertTrue(flushed, "the registered flush should have run")

        // Unregistering happens as the account screen goes away, right before the repository
        // is closed — so it must both stop the work check and forget the handler.
        exitFlush.unregister()
        assertFalse(exitFlush.hasQueuedWork())

        flushed = false
        exitFlush.run() // must not call into a closed repository
        assertFalse(flushed)
    }
}
