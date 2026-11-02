package com.example.notesai.sync

/**
 * How long one HTTP request may take before it is abandoned and retried on a later pass.
 *
 * Shared rather than private to the app, because the sync engine needs it too: a pass that cannot fit one
 * request is not worth starting, and the shutdown flush's ceiling has to sit above it.
 */
const val REQUEST_TIMEOUT_MILLIS = 10_000L

/**
 * The least time a pass needs left on its clock to be worth doing anything at all.
 *
 * One request: below that, a pass can only start work it cannot finish, and a request cut off mid-flight is the
 * ambiguity that makes the next launch unsure whether a note reached Drive. Leaving the queue alone instead costs
 * nothing — the change is already on disk, and the next launch delivers it.
 */
internal const val MINIMUM_PASS_BUDGET_MILLIS = REQUEST_TIMEOUT_MILLIS
