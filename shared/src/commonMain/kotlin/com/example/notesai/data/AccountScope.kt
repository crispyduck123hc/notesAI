package com.example.notesai.data

import com.example.notesai.auth.sha256

private const val HEX = "0123456789abcdef"
private const val NO_ACCOUNT = "no-account"

/**
 * The local database for a signed-in account.
 *
 * Two Google accounts must never share notes, so each one gets its own database file
 * rather than a shared file with an `accountId` column — the isolation is structural, so
 * no query can forget to filter.
 *
 * The name is a hash of the address rather than the address itself: it stays stable and
 * filesystem-safe regardless of the characters in the email, and it avoids writing an
 * email address onto disk in the clear.
 */
fun accountDatabaseName(email: String?): String {
    val key = email?.trim()?.lowercase().orEmpty().ifEmpty { NO_ACCOUNT }
    val digest = sha256(key.encodeToByteArray())

    return buildString {
        append("notes-")
        digest.take(8).forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }
}
