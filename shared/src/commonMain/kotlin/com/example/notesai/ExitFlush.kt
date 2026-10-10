package com.example.notesai

/**
 * Lets a platform entry point ask the signed-in screen to drain the outbox before the process
 * goes away.
 *
 * Only **desktop** uses this. Closing a window is a real, interceptable "you are about to
 * quit" moment. Android and iOS have no equivalent — the OS may kill a backgrounded app at
 * any point — so there the durable outbox plus a push on next start is the whole story.
 *
 * It is deliberately best-effort: the caller applies a timeout and exits regardless. A
 * durable queue plus one retry is worth far more than a hung shutdown, and exit is the least
 * reliable moment in an app's life for network I/O.
 */
class ExitFlush {
    private var pendingCount: () -> Int = { 0 }
    private var flush: suspend () -> Unit = {}

    /** Called by the signed-in screen to offer itself as the thing to drain. */
    fun register(pendingCount: () -> Int, flush: suspend () -> Unit) {
        this.pendingCount = pendingCount
        this.flush = flush
    }

    /** Called when that screen goes away, so we never flush a closed repository. */
    fun unregister() {
        pendingCount = { 0 }
        flush = {}
    }

    /** Whether it is worth keeping the process alive for a moment. */
    fun hasQueuedWork(): Boolean = pendingCount() > 0

    suspend fun run() {
        flush()
    }
}
