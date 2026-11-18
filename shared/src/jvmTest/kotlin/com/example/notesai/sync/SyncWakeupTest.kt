package com.example.notesai.sync

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wake-up policy is a timing rule, so it is tested as one: small values, and the assertion is how many passes a
 * sequence of queue sizes produces. No sleeping on the wall clock beyond the settle window used here.
 */
class SyncWakeupTest {
    private val wakeup = SyncWakeup(settleMillis = 40, quietMillis = 5)

    private fun passes(vararg counts: Long): Int = runBlocking { wakeup.wakes(flowOf(*counts.toTypedArray())).toList().size }

    @Test
    fun aBurstOfEditsProducesTwoPasses() {
        // The first pass publishes what was already stale and pulls; the second publishes the edit that caused it.
        assertEquals(2, passes(0, 1, 1, 1, 1, 0))
    }

    @Test
    fun anEmptyQueueNeverStartsAPass() {
        assertEquals(0, passes(0, 0, 0))
    }

    @Test
    fun eachRefillIsItsOwnBurst() {
        // Two separate sessions of writing, so two immediate and two delayed passes.
        assertEquals(4, passes(0, 1, 0, 1, 0))
    }

    @Test
    fun aQueueThatNeverEmptiesIsOneBurst() {
        // `distinctUntilChanged` on "has work": only the transition matters, however many edits follow it.
        assertEquals(2, passes(1, 1, 1, 1, 1, 1, 1))
    }
}
