package com.example.notesai.ui.editor

import androidx.compose.ui.text.AnnotatedString
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
    fun theRendererDrawsExactlyTheDecorationsTheModelDescribes() {
        // The parity contract: same count, same glyph, same place. This is the test that would have caught the two
        // descriptions drifting apart while the overlay work happens.
        for (note in corpus) {
            val drawn = EditorTransformation().filter(AnnotatedString(note))
            val decorations = editorDecorations(note)

            assertEquals(
                decorations.size,
                drawn.text.text.count { it == '\u25CB' || it == '\u25C9' },
                "one drawn circle per decoration in ${escape(note)}",
            )
            for (decoration in decorations) {
                val at = drawn.offsetMapping.originalToTransformed(decoration.start)
                assertEquals(
                    decoration.drawnGlyph,
                    drawn.text.text.substring(at, at + decoration.drawnGlyph.length),
                    "decoration at ${decoration.start} of ${escape(note)}",
                )
            }
        }
    }

    @Test
    fun everythingOutsideADecorationIsDrawnUnchangedApartFromTabs() {
        for (note in corpus) {
            val drawn = EditorTransformation().filter(AnnotatedString(note)).text.text
            val expected =
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

            assertEquals(expected, drawn, "drawn text for ${escape(note)}")
        }
    }

    private data class Decoration(
        val start: Int,
        val end: Int,
        val checked: Boolean = false,
    )

    private fun described(note: String): List<Decoration> = editorDecorations(note).map { Decoration(it.start, it.end, it.checked) }

    private fun escape(text: String): String = text.replace("\n", "\\n").replace("\t", "\\t")
}
