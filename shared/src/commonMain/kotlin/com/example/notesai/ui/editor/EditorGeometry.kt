package com.example.notesai.ui.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult

/**
 * A decoration with the box it occupies **on screen**, in the same coordinate space as the text layout.
 *
 * Phase 2 of the editor-overlay plan. The rule the whole design rests on: these numbers come from the field's own
 * [TextLayoutResult], never from measuring the text a second time. A second measurement is one font, density or
 * platform text engine away from putting the circle somewhere the character is not.
 */
internal data class PlacedDecoration(
    val decoration: EditorDecoration,
    val bounds: Rect,
)

/**
 * Where each decoration's drawn glyph sits, and nothing else.
 *
 * The note's own offsets are the input, so they are mapped to drawn offsets first ([drawnOffset]): a decoration is
 * two characters in the field (`"○ "`), four in the note (`"[ ] "`).
 *
 * Vertically the box is the whole line, not just the glyph: a circle the height of an `x` is not a tap target, and a
 * line-height box is what a person aims at. Markers always start a line, so the glyph cannot straddle a wrap.
 */
internal fun placeDecorations(
    text: String,
    layout: TextLayoutResult,
    decorations: List<EditorDecoration> = editorDecorations(text),
): List<PlacedDecoration> {
    // The box offsets are drawn offsets, so the layout has to be the *drawn* text. Handing over a layout of the raw
    // note would put every box on the wrong line (found the hard way: a test field without the output transformation
    // made both decorations land on line 0). Rather than guess, place nothing: the field still draws its own circles,
    // so a caller that gets this wrong loses tap targets rather than getting them in the wrong place.
    val drawnText = layout.layoutInput.text.text
    if (layout.lineCount == 0 || !drawnText.contentEquals(applyDisplayEdits(text))) return emptyList()

    val drawnLength = layout.layoutInput.text.length
    return decorations.mapNotNull { decoration ->
        val drawnStart = drawnOffset(text, decoration.start).coerceIn(0, drawnLength)
        // The end offset, not a glyph length: a checklist marker shrinks from four characters to two, a link does not
        // change at all, and this happens to be right for both.
        val drawnEnd = drawnOffset(text, decoration.end).coerceIn(drawnStart, drawnLength)
        if (drawnStart >= drawnLength) return@mapNotNull null

        val line = layout.getLineForOffset(drawnStart)
        val left = layout.getHorizontalPosition(drawnStart, usePrimaryDirection = true)
        // The glyph's own right edge, not the line's: the drawn text after the circle starts there.
        val right = layout.getHorizontalPosition(drawnEnd, usePrimaryDirection = true)
        PlacedDecoration(
            decoration = decoration,
            bounds =
                Rect(
                    left = left,
                    top = layout.getLineTop(line),
                    right = if (right > left) right else left + layout.getBoundingBox(drawnStart).width,
                    bottom = layout.getLineBottom(line),
                ),
        )
    }
}

/**
 * Only the decorations a person can currently see, so a long note does not compose a tap target per item off-screen.
 *
 * [viewport] is in layout coordinates: the field's scroll offset and height. The margin keeps a decoration that is
 * half-scrolled-in composable, which is what stops it from popping in under the cursor.
 */
internal fun List<PlacedDecoration>.visibleIn(
    viewport: Rect,
    marginPx: Float = 0f,
): List<PlacedDecoration> =
    filter { placed ->
        placed.bounds.bottom >= viewport.top - marginPx && placed.bounds.top <= viewport.bottom + marginPx
    }
