package com.example.notesai.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Choosing which release to offer, driven by the JSON GitHub actually returns.
 *
 * The trap this pins: `/releases/latest` answers 404 while every release is a pre-release, so the
 * checker reads the list endpoint instead. These fixtures are that endpoint's shape.
 */
class UpdateCheckerTest {
    private fun release(
        tag: String,
        draft: Boolean = false,
    ) = """
        {
          "tag_name": "$tag",
          "html_url": "https://github.com/crispyduck123hc/notesAI/releases/tag/$tag",
          "draft": $draft
        }
        """.trimIndent()

    private fun releases(vararg tags: String) = tags.joinToString(prefix = "[", postfix = "]") { release(it) }

    @Test
    fun offersTheNewestRelease() {
        val update =
            UpdateChecker.newestUpdateIn(
                releases("v1.0.0-beta.1", "v1.2.0-beta", "v1.1.0-beta"),
                current = "1.1.0",
            )

        assertEquals("v1.2.0-beta", update?.tag)
        assertEquals(
            "https://github.com/crispyduck123hc/notesAI/releases/tag/v1.2.0-beta",
            update?.releaseUrl,
        )
    }

    @Test
    fun saysNothingWhenTheRunningVersionIsCurrent() {
        assertNull(UpdateChecker.newestUpdateIn(releases("v1.2.0-beta", "v1.1.0-beta"), current = "1.2.0"))
    }

    @Test
    fun draftsAreNotOffered() {
        val body =
            """[
                ${release("v1.2.1")},
                ${release("v9.9.9", draft = true)}
            ]"""

        assertEquals("v1.2.1", UpdateChecker.newestUpdateIn(body, current = "1.2.0")?.tag)
    }

    @Test
    fun aNonsenseTagDoesNotBecomeTheNewest() {
        val body =
            """[
                ${release("nightly")},
                ${release("v1.2.1")}
            ]"""

        assertEquals("v1.2.1", UpdateChecker.newestUpdateIn(body, current = "1.2.0")?.tag)
    }

    @Test
    fun anUnrecognisedPayloadIsNotAnUpdate() {
        assertNull(UpdateChecker.newestUpdateIn("""{"message":"Not Found"}""", current = "1.2.0"))
        assertNull(UpdateChecker.newestUpdateIn("[]", current = "1.2.0"))
    }

    @Test
    fun aRunningVersionThatDoesNotParseDisablesTheCheck() {
        assertNull(UpdateChecker.newestUpdateIn(releases("v9.9.9"), current = "not-a-version"))
    }
}
