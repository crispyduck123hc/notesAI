package com.example.notesai.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform

/**
 * Turns "the durable queue stopped being empty" into the passes a wake-up should run.
 *
 * The outbox is the source of truth for work owed, so it is also the honest thing to watch: no per-edit callback, no
 * network in the write path, and a wake-up cannot miss a record that `markDirty` produced. What it cannot see is
 * another device writing, which is why this is an addition to the periodic tick, never a replacement.
 *
 * Two passes per burst, which is what keeps the settle window honest:
 *
 * 1. **Immediately.** Pulls other devices' work and publishes anything already past the window: a queue left behind by
 *    a crash, a quit, or an offline spell.
 * 2. **After [settleMillis].** The edit that caused the first pass was too young to publish at the time; by the second
 *    pass it is exactly old enough, which is what makes push latency the window rather than the tick.
 *
 * Bursts are collapsed twice. [distinctUntilChanged] on "has work" means the first edit of a session is the signal --
 * later edits leave the queue non-empty and emit nothing -- and [conflate] means a pass already in progress absorbs
 * signals that arrive meanwhile instead of queueing a backlog of them.
 */
class SyncWakeup(
    private val settleMillis: Long,
    private val quietMillis: Long,
) {
    /**
     * @param outboxCount rows owed, from a query flow. Only the transition into "not empty" is a wake-up.
     */
    fun wakes(outboxCount: Flow<Long>): Flow<Unit> =
        outboxCount
            .map { it > 0 }
            .distinctUntilChanged()
            .filter { it }
            .transform {
                // Rate limit: a queue that empties and refills several times a second should still mean one pass.
                delay(quietMillis)
                emit(Unit)
            }.transform {
                emit(Unit)
                delay(settleMillis)
                emit(Unit)
            }.conflate()
}
