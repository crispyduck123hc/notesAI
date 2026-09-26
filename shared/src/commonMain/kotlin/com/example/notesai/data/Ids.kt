package com.example.notesai.data

import kotlin.random.Random

private const val HEX = "0123456789abcdef"

/**
 * A random RFC 4122 version 4 UUID as a canonical lowercase string.
 *
 * Implemented over [Random] rather than a platform API so it works in common code
 * across Android, iOS, desktop and web.
 */
fun randomUuid(random: Random = Random.Default): String {
    val bytes = random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte() // version 4
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // variant 10xx

    return buildString(36) {
        bytes.forEachIndexed { index, byte ->
            if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }
}
