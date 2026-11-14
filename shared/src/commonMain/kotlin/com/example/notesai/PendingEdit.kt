package com.example.notesai

/**
 * The editor's unsaved text, offered to whatever ends the process.
 *
 * Quitting waits for exactly one thing: **the local transaction**. The editor's write is debounced
 * ([com.example.notesai.ui.editor.WRITE_DEBOUNCE_MS]), so text typed in the last few hundred milliseconds is still only in
 * the composition. Disposing the composition is not something an exit path can wait for, so the buffer is offered here
 * instead and written synchronously: `commit()` returns only once the note and its outbox row are committed.
 *
 * No network call is made from here, by design. The outbox row is the durable record of what is owed; the upload is
 * reconciled by the next pass, on this device or the next launch. See `NoteSyncEngine` for how a write that landed
 * without an acknowledgement is recognised as the app's own.
 *
 * Only **desktop** uses this. Android and iOS give no interceptable "about to quit" moment at all, so there the
 * debounce is flushed by the composition being torn down, and the outbox covers the rest.
 */
class PendingEdit {
    private var flush: (() -> Unit)? = null

    /**
     * Called by the editor, once per open document, with the write that persists its buffer. The flush is run on
     * whatever thread commits, so it must not assume the composition is still alive.
     */
    fun offer(flush: () -> Unit) {
        this.flush = flush
    }

    /** Called when that editor goes away, so a stale buffer is never written into a closed repository. */
    fun withdraw() {
        flush = null
    }

    /** Writes the offered buffer, if any. Synchronous, and safe to call when no editor ever offered one. */
    fun commit() {
        flush?.invoke()
    }
}
