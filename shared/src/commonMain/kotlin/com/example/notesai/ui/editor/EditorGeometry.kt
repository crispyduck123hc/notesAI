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
    /** The hit target: the decoration's width, but a whole line tall, because that is what a person aims at. */
    val bounds: Rect,
    /**
     * The drawn glyph's own box, tight around its ink.
     *
     * Kept separately because two different things need two different boxes: the hit target should be generous, while
     * anything *drawn inside* the decoration (a tick in the circle) has to be centred on the glyph, not on the target.
     */
    val glyphBounds: Rect,
)

/**
 * Where each decoration's drawn glyph sits, and nothing else.
 *
 * The note's own offsets are the input, so they are mapped to drawn offsets first ([drawnOffset]): a checklist marker
 * is four characters in the note (`"[ ] "`) and two in the field (`"○ "`).
 *
 * Two boxes come out of this: [PlacedDecoration.bounds] is the hit target -- the decoration's width, a whole line tall --
 * and [PlacedDecoration.glyphBounds] is the tight box of what was drawn. A replaced glyph's *trailing space* belongs to
 * neither: it is padding inside the drawn text, so including it pushed the hover highlight and the tick to the right of
 * the circle they belong to.
 *
 * Returns null when [layout] is not the layout of this [text] (see the guard below). Callers must treat that as "no news",
 * not as "no decorations": on every keystroke the text changes one frame before the field reports a new layout, and
 * clearing on that frame is what made the overlay flicker.
 */
internal fun placeDecorations(
    text: String,
    layout: TextLayoutResult,
    decorations: List<EditorDecoration> = editorDecorations(text),
): List<PlacedDecoration>? {
    // The box offsets are drawn offsets, so the layout has to be the *drawn* text. Handing over a layout of the raw
    // note would put every box on the wrong line (found the hard way: a test field without the output transformation
    // made both decorations land on line 0). Null says "this layout is not mine": the caller keeps what it had, which
    // is one frame stale and invisible, instead of dropping the overlay for a frame and flickering.
    val drawnText = layout.layoutInput.text.text
    if (layout.lineCount == 0 || !drawnText.contentEquals(applyDisplayEdits(text))) return null

    val drawnLength = layout.layoutInput.text.length
    return decorations.mapNotNull { decoration ->
        val drawnStart = drawnOffset(text, decoration.start).coerceIn(0, drawnLength)
        // A replaced glyph carries a trailing space for padding; it is not part of the control. Without this the hover
        // highlight and the tick sat a space's width to the right of the circle.
        val drawnEnd =
            (
                decoration.drawnGlyph?.let { drawnStart + it.trimEnd().length }
                    ?: drawnOffset(text, decoration.end)
            ).coerceIn(drawnStart, drawnLength)
        if (drawnStart >= drawnLength) return@mapNotNull null

        val glyph = layout.getBoundingBox(drawnStart)
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
                    right = if (right > left) right else left + glyph.width,
                    bottom = layout.getLineBottom(line),
                ),
            glyphBounds = glyph,
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
