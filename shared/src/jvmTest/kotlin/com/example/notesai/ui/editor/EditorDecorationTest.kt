package com.example.notesai.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 0 of the editor-overlay plan: the decoration model, pinned to what the editor draws today.
 *
 * The model is what an overlay will position itself from, so the risk it introduces is *divergence* -- the renderer
 * deciding a circle goes here while the model says it goes there. `EditorTransformation` now renders from this model, so
 * they cannot drift structurally; the parity tests below are the guard that they still describe the same drawing,
 * including for the awkward inputs (indentation, markers mid-line, prose that merely looks like a marker).
 */
class EditorDecorationTest {
    /** Notes that between them exercise every rule the model has. Shared with the parity test. */
    private val corpus: List<String> =
        listOf(
            "",
            "[ ] milk",
            "[x] milk",
            "[ ] a\n[ ] b\n[x] c",
            "\t[ ] indented by a tab",
            "    [ ] indented by spaces",
            "write [ ] down",
            "- dash\n[ ] task\n1. numbered",
            "[ ]",
            "[ ]no space",
            "\n[ ] after an empty first line",
            "[X] capital x is not a marker",
            "[ ] wrapped line that goes on\nand on without a marker",
        )

    @Test
    fun onlyChecklistMarkersAtTheStartOfALineAreDecorated() {
        val cases =
            listOf(
                "" to emptyList(),
                "[ ] milk" to listOf(Decoration(0, 4, checked = false)),
                "[x] milk" to listOf(Decoration(0, 4, checked = true)),
                "[ ] a\n[ ] b\n[x] c" to
                    listOf(Decoration(0, 4), Decoration(6, 10), Decoration(12, 16, checked = true)),
                "\t[ ] indented by a tab" to listOf(Decoration(1, 5)),
                "    [ ] indented by spaces" to listOf(Decoration(4, 8)),
                "write [ ] down" to emptyList(),
                "- dash\n[ ] task\n1. numbered" to listOf(Decoration(7, 11)),
                "[ ]" to emptyList(),
                "[ ]no space" to emptyList(),
                "\n[ ] after an empty first line" to listOf(Decoration(1, 5)),
                "[X] capital x is not a marker" to emptyList(),
            )

        for ((note, expected) in cases) {
            assertEquals(expected, described(note), "decorations for ${escape(note)}")
        }
    }

    @Test
    fun aDecorationPointsAtTheCharactersThatAreActuallyThere() {
        // The overlay will act on this range, so it has to be the note's own characters, not the drawn ones.
        for (note in corpus) {
            for (decoration in editorDecorations(note)) {
                val marker = note.substring(decoration.start, decoration.end)
                assertTrue(
                    marker == CHECKLIST_OPEN || marker == CHECKLIST_DONE,
                    "range ${decoration.start}..${decoration.end} of ${escape(note)} is ${escape(marker)}",
                )
                assertEquals(
                    marker == CHECKLIST_DONE,
                    decoration.checked,
                    "the tick state comes from the characters: ${escape(note)}",
                )
            }
        }
    }

    @Test
    fun theDrawingIsExactlyTheDecorationsPlusExpandedTabs() {
        // The projection the output transformation writes into the field, asserted as text: one circle per decoration,
        // and every other character untouched apart from a tab becoming TAB_COLUMNS spaces.
        for (note in corpus) {
            val drawn = applyDisplayEdits(note)

            assertEquals(
                editorDecorations(note).size,
                drawn.count { it == '\u25CB' || it == '\u25C9' },
                "one drawn circle per decoration in ${escape(note)}",
            )
            assertEquals(
                note.length - editorDecorations(note).sumOf { it.end - it.start } +
                    editorDecorations(note).size * 2 +
                    note.count { it == '\t' } * (TAB_COLUMNS - 1),
                drawn.length,
                "markers become two columns, tabs become $TAB_COLUMNS in ${escape(note)}",
            )
            assertEquals(expectedDrawing(note), drawn, "drawn text for ${escape(note)}")
        }
    }

    @Test
    fun aSpanPlacedOnTheFirstLineMapsToWhereItIsDrawn() {
        // The title style covers the first line, and its end has to be expressed in drawn coordinates.
        assertEquals(0, drawnOffset("[ ] milk", 0))
        assertEquals(2, drawnOffset("[ ] milk", CHECKLIST_OPEN.length))
        // The bracket is drawn after the four columns the tab occupies, not after one.
        assertEquals(TAB_COLUMNS, drawnOffset("\t[ ] a", 1))
        assertEquals("first line", applyDisplayEdits("first line\nsecond").substring(0, drawnOffset("first line\nsecond", 10)))
    }

    @Test
    fun proseThatLooksLikeAMarkerIsDrawnAsWritten() {
        assertEquals("write [ ] down", applyDisplayEdits("write [ ] down"))
        assertEquals("a\tb", "a\tb".let { applyDisplayEdits(it) }.replace(" ".repeat(TAB_COLUMNS), "\t"))
    }

    private data class Decoration(
        val start: Int,
        val end: Int,
        val checked: Boolean = false,
    )

    /** An independent reconstruction of what should be drawn, so the projection is not compared with itself. */
    private fun expectedDrawing(note: String): String =
        buildString {
            var index = 0
            val decorations = editorDecorations(note)
            while (index < note.length) {
                val decoration = decorations.firstOrNull { it.start == index }
                if (decoration != null) {
                    append(decoration.drawnGlyph)
                    index = decoration.end
                } else {
                    append(if (note[index] == '\t') " ".repeat(TAB_COLUMNS) else note[index])
                    index++
                }
            }
        }

    private fun described(note: String): List<Decoration> = editorDecorations(note).map { Decoration(it.start, it.end, it.checked) }

    private fun escape(text: String): String = text.replace("\n", "\\n").replace("\t", "\\t")

    @Test
    fun aUrlIsFoundAndTrimmedWhereTheSentenceEnds() {
        val cases =
            listOf(
                "see https://example.com for more" to listOf("https://example.com"),
                "see https://example.com. and stop" to listOf("https://example.com"),
                "read http://a.b/c?d=e#f, then" to listOf("http://a.b/c?d=e#f"),
                "brackets (https://example.com) here" to listOf("https://example.com"),
                "the article https://en.wikipedia.org/wiki/Foo_(bar) is good" to
                    listOf("https://en.wikipedia.org/wiki/Foo_(bar)"),
                "two: https://a.io and https://b.io" to listOf("https://a.io", "https://b.io"),
                "HTTPS://EXAMPLE.COM/caps" to listOf("HTTPS://EXAMPLE.COM/caps"),
                "no links here" to emptyList(),
                "ftp://files.example.com is not a link" to emptyList(),
            )

        for ((note, expected) in cases) {
            val urls = linkSpans(note).map { note.substring(it.first, it.last + 1) }
            assertEquals(expected, urls, "links in ${escape(note)}")
        }
    }

    @Test
    fun aLinkIsNotDrawnDifferentlyItIsOnlyClickable() {
        // The characters of a URL stay exactly as written; a checklist marker is replaced, a link is not.
        val note = "[ ] read https://example.com\n[ ] and https://a.io."
        val drawn = applyDisplayEdits(note)

        assertEquals(note.length - 4 - 4 + 4, drawn.length, "only the two markers shrank")
        assertTrue(drawn.contains("https://example.com"), "the URL is drawn as written: $drawn")
        assertTrue(drawn.contains("https://a.io"), "and so is the second: $drawn")

        val links = editorDecorations(note).filter { it.kind == EditorDecoration.Kind.LINK }
        assertEquals(listOf("https://example.com", "https://a.io"), links.map { it.url })
        assertEquals(null, links.first().drawnGlyph, "a link has no glyph of its own")
    }

    @Test
    fun decorationsAreInDocumentOrderWhateverTheirKind() {
        val note = "https://a.io\n[ ] item"
        val decorations = editorDecorations(note)

        assertEquals(listOf(0, note.indexOf("[ ]")), decorations.map { it.start })
        assertEquals(EditorDecoration.Kind.LINK, decorations.first().kind)
        assertEquals(EditorDecoration.Kind.CHECKLIST, decorations.last().kind)
    }
}
