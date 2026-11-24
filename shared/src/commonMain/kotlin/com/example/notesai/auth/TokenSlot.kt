package com.example.notesai.auth

/**
 * Which credential slot a [TokenStore] reads and writes.
 *
 * The app talks to independent providers *and* independent grants, so their tokens must not share a
 * slot: signing out of GitHub should not disturb the Google sign-in, and the AI grant expires and
 * refreshes on its own schedule without touching the Drive one.
 */
enum class TokenSlot(
    /** Stable identifier: part of the Keychain account, and of the fallback file name. */
    val key: String,
) {
    GOOGLE("google"),

    /** A second Google grant: the AI scope, consented on demand. See [AI_SCOPE]. */
    AI("ai"),
    GITHUB("github"),
}
