package com.example.notesai.auth

/**
 * Which credential slot a [TokenStore] reads and writes.
 *
 * The app talks to two independent providers, so their tokens must not share a slot: signing out of
 * GitHub should not disturb the Google sign-in, and vice versa.
 */
enum class TokenSlot(
    /** Stable identifier: part of the Keychain account, and of the fallback file name. */
    val key: String,
) {
    GOOGLE("google"),
    GITHUB("github"),
}
