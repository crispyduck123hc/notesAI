package com.example.notesai.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Version precedence, because "is this newer" is the whole feature.
 *
 * The failure modes are quiet: compare strings and `1.10.0` is older than `1.9.0`; ignore the
 * pre-release suffix and someone gets nagged to install the beta they are already running.
 */
class SemanticVersionTest {
    private fun parse(text: String) = SemanticVersion.parseOrNull(text) ?: error("could not parse $text")

    @Test
    fun numbersCompareAsNumbersNotText() {
        assertTrue(parse("1.10.0").isNewerThan(parse("1.9.0")))
        assertTrue(parse("1.2.10").isNewerThan(parse("1.2.9")))
        assertTrue(parse("2.0.0").isNewerThan(parse("1.99.99")))
    }

    @Test
    fun aPreReleaseIsOlderThanItsRelease() {
        assertTrue(parse("1.2.1").isNewerThan(parse("1.2.1-beta")))
        assertTrue(parse("1.2.1-beta").isNewerThan(parse("1.2.0")))
        assertTrue(parse("1.3.0-beta").isNewerThan(parse("1.2.1")))
    }

    @Test
    fun preReleaseIdentifiersCompareTheWaySemverSays() {
        assertTrue(parse("1.2.1-beta.2").isNewerThan(parse("1.2.1-beta.1")))
        // Numeric identifiers compare numerically, and rank below words.
        assertTrue(parse("1.2.1-beta.10").isNewerThan(parse("1.2.1-beta.2")))
        assertTrue(parse("1.2.1-alpha").isNewerThan(parse("1.2.1-1")))
        assertTrue(parse("1.2.1-beta.1").isNewerThan(parse("1.2.1-beta")))
    }

    @Test
    fun theSameVersionIsNotNewer() {
        assertTrue(!parse("1.2.1").isNewerThan(parse("1.2.1")))
        assertTrue(!parse("1.2.1-beta").isNewerThan(parse("v1.2.1-beta")))
        assertEquals(0, parse("1.2.1").compareTo(parse("1.2.1")))
    }

    @Test
    fun tagsAndBuildMetadataAreTolerated() {
        assertEquals(parse("1.2.1"), parse("v1.2.1"))
        assertEquals(parse("1.2.1-beta.2"), parse("v1.2.1-beta.2+abc123"))
        assertEquals(parse("1.2.1"), parse("  1.2.1  "))
    }

    @Test
    fun rubbishIsRejectedRatherThanRanked() {
        // An unparseable tag must not count as "newer", or one stray tag nags everyone forever.
        assertNull(SemanticVersion.parseOrNull(""))
        assertNull(SemanticVersion.parseOrNull("v"))
        assertNull(SemanticVersion.parseOrNull("latest"))
        assertNull(SemanticVersion.parseOrNull("1.2"))
        assertNull(SemanticVersion.parseOrNull("1.2.3.4"))
        assertNull(SemanticVersion.parseOrNull("1.2.x"))
        assertNull(SemanticVersion.parseOrNull("-1.2.3"))
    }
}
