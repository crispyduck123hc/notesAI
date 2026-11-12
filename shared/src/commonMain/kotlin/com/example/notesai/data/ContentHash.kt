package com.example.notesai.data

import com.example.notesai.auth.sha256

/**
 * A stable fingerprint of a note's text.
 *
 * Used only to recognise this device's own uploads: the outbox stores the hash of what it sent, and a later pass
 * compares it with the remote file's content. Hex because it is compared, never parsed.
 */
fun contentHash(content: String): String = sha256(content.encodeToByteArray()).joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
