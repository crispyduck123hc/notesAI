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
}
