package com.example.notesai.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 2: the geometry the overlay will hit-test against.
 *
 * The property under test is not "the numbers are what I wrote down" but "the numbers agree with the text engine": the
 * x of a decoration is the glyph's own box, the y is the line's box. That is the only way this stays true across fonts,
 * densities and platforms, which is why the same note is measured at two font sizes and two densities.
 */
@OptIn(ExperimentalTestApi::class)
class EditorGeometryTest {
    private companion object {
        /** Enough frames for the first layout to happen, and few enough that a broken test fails instead of hanging. */
        const val FRAMES_TO_SETTLE = 30
    }

    /**
     * Lays the *drawn* text out with the text engine directly -- the same engine the field uses, without a composition.
     *
     * Measured rather than invented, and deliberately outside `runComposeUiTest`: a focused field schedules cursor-blink
     * frames forever, so a harness that waits for idle can sit there. `TextMeasurer` gives a real `TextLayoutResult`
     * with the same geometry API the overlay reads, at any density and font size.
     */
    private fun measure(
        text: String,
        fontSizeSp: Float = 16f,
        densityFactor: Float = 1f,
    ): TextLayoutResult {
        val measurer =
            TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(),
                defaultDensity = Density(densityFactor, 1f),
                defaultLayoutDirection = LayoutDirection.Ltr,
            )
        return measurer.measure(
            text = AnnotatedString(applyDisplayEdits(text)),
            style = TextStyle(fontSize = fontSizeSp.sp),
            constraints = Constraints(maxWidth = (600 * densityFactor).toInt()),
        )
    }

    @Test
    fun eachDecorationSitsOnItsOwnGlyph() {
        // The indent is the point: a marker's drawn position is not its offset in the note, so a rect computed from
        // note offsets would be two columns out here.
        val note = "[ ] milk\n\t[x] eggs\nwrite [ ] down\n[ ] tea"
        val decorations = editorDecorations(note)
        assertEquals(3, decorations.size, "three markers, one of them indented, one bracket pair in prose")

        val layouts = listOf(measure(note, 16f, 1f), measure(note, 22f, 2.5f))
        layouts.forEachIndexed { variant, layout ->
            val placed = placeDecorations(note, layout)!!
            assertEquals(decorations.size, placed.size, "variant $variant")

            placed.forEachIndexed { index, item ->
                val drawnStart = drawnOffset(note, item.decoration.start)
                val glyph = layout.getBoundingBox(drawnStart)
                val line = layout.getLineForOffset(drawnStart)
                val where = "variant $variant, decoration #$index"

                // Contains the circle the text engine drew.
                assertTrue(abs(item.bounds.left - glyph.left) <= 1f, "$where: left ${item.bounds.left} vs ${glyph.left}")
                assertTrue(item.bounds.right >= glyph.right - 1f, "$where: right ${item.bounds.right} vs ${glyph.right}")
                // A box, not the whole line: the circle plus the space the marker draws.
                assertTrue(
                    item.bounds.width <= glyph.width + layout.getBoundingBox(drawnStart + 1).width + 1f,
                    "$where: width ${item.bounds.width} is wider than the drawn glyph",
                )
                // Vertically it is the line, which is the tap target a person aims at.
                assertEquals(layout.getLineTop(line), item.bounds.top, "$where: top")
                assertEquals(layout.getLineBottom(line), item.bounds.bottom, "$where: bottom")
                assertTrue(item.bounds.top <= glyph.top && item.bounds.bottom >= glyph.bottom, "$where: contains the glyph")
            }
        }
    }

    @Test
    fun aDecorationIsDrawnOnTheLineItBelongsTo() {
        val note = "[ ] one\n[ ] two"
        val layout = measure(note)
        val placed = placeDecorations(note, layout)!!

        assertEquals(note.indexOf("[ ] two"), placed.last().decoration.start, "the second marker's note offset")
        assertTrue(placed.last().bounds.top > placed.first().bounds.top, "and it is drawn on a later line")
        // Note offsets and drawn offsets differ by the first marker's shrink, so this is not the same number.
        assertTrue(
            drawnOffset(note, placed.last().decoration.start) < placed.last().decoration.start,
            "the drawn offset is smaller than the note offset",
        )
    }

    @Test
    fun onlyDecorationsInTheViewportAreComposed() {
        val note = (1..40).joinToString("\n") { "[ ] item $it" }
        val layout = measure(note)
        val placed = placeDecorations(note, layout)!!
        assertEquals(40, placed.size)

        val lineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
        val viewport = Rect(0f, lineHeight * 5, 600f, lineHeight * 10)
        val visible = placed.visibleIn(viewport, marginPx = lineHeight)

        assertTrue(visible.size < placed.size, "a scrolled window must not compose every item")
        assertTrue(visible.size >= 5, "the five visible lines are composed, got ${visible.size}")
        assertTrue(visible.all { it.bounds.bottom >= viewport.top - lineHeight }, "nothing above the window")
        assertTrue(visible.all { it.bounds.top <= viewport.bottom + lineHeight }, "nothing below the window")
        assertTrue(placed.first() !in visible, "the first line is scrolled out of the window")
    }

    @Test
    fun theOverlayComposesOneElementPerVisibleDecoration() =
        runComposeUiTest {
            // `mainClock.autoAdvance = false` on purpose: a focused field schedules cursor-blink frames forever, so any
            // assertion that waits for idle hangs. Frames are advanced a bounded number of times instead.
            mainClock.autoAdvance = false
            val note = "[ ] a\n[x] b"
            val layout = mutableStateOf<TextLayoutResult?>(null)
            setContent {
                Box(modifier = Modifier.size(width = 600.dp, height = 300.dp)) {
                    BasicTextField(
                        state = TextFieldState(note),
                        outputTransformation = editorOutputTransformation(),
                        onTextLayout = { getResult -> layout.value = getResult() },
                    )
                    EditorOverlay(text = note, layout = layout.value)
                }
            }
            repeat(FRAMES_TO_SETTLE) { mainClock.advanceTimeByFrame() }

            onAllNodesWithTag(DECORATION_TAG_PREFIX + 0).assertCountEquals(1)
            onAllNodesWithTag(DECORATION_TAG_PREFIX + 1).assertCountEquals(1)
            onAllNodesWithTag(DECORATION_TAG_PREFIX + 2).assertCountEquals(0)

            // And the box reports the geometry computed for it.
            val expected = placeDecorations(note, layout.value!!)!!
            val node = onAllNodesWithTag(DECORATION_TAG_PREFIX + 0).fetchSemanticsNodes().single()
            assertEquals(expected.first().bounds, node.config.getOrNull(EditorDecorationBoundsKey))

            // Phase 2 draws nothing inside the boxes; this assertion is what phase 3 flips.
            assertEquals(null, node.config.getOrNull(SemanticsProperties.Text), "an empty box: nothing drawn yet")
        }

    @Test
    fun aLayoutOfTheRawNotePlacesNothing() {
        // The guard behind the contract: a caller that passes a layout of the untransformed text gets no boxes rather
        // than boxes in the wrong place.
        val note = "[ ] one\n[ ] two"
        val measurer =
            TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(),
                defaultDensity = Density(1f, 1f),
                defaultLayoutDirection = LayoutDirection.Ltr,
            )
        val raw = measurer.measure(text = AnnotatedString(note), constraints = Constraints(maxWidth = 600))

        assertEquals(2, raw.lineCount, "the raw text still has two lines")
        assertEquals(null, placeDecorations(note, raw), "but it is not the layout of the drawn text, so nothing is placed")
        assertEquals(2, placeDecorations(note, measure(note))!!.size, "the drawn layout places both")
    }

    @Test
    fun theBoxDoesNotIncludeThePaddedSpaceOfAReplacedGlyph() {
        // The drawn glyph is "○ " -- a circle and a space. The space is padding inside the text, so a hover highlight or
        // a tick centred on the whole box ends up to the right of the circle. This is the regression that caused it.
        val note = "[ ] milk"
        val layout = measure(note)
        val placed = placeDecorations(note, layout)!!.single()

        val circle = layout.getBoundingBox(drawnOffset(note, 0))
        assertTrue(
            placed.bounds.width <= circle.width + 1f,
            "box width ${placed.bounds.width} should be the circle's ${circle.width}, not the circle plus a space",
        )
        assertTrue(
            abs(placed.bounds.right - circle.right) <= 1f,
            "the right edge ${placed.bounds.right} should be the circle's ${circle.right}",
        )
        // The glyph box is tight around the circle and sits inside the hit target.
        assertTrue(placed.glyphBounds.width <= placed.bounds.width + 0.5f)
        assertTrue(placed.glyphBounds.top >= placed.bounds.top && placed.glyphBounds.bottom <= placed.bounds.bottom)
    }

    @Test
    fun aStaleLayoutSaysNothingInsteadOfSayingEmpty() {
        // `null` means "this layout is not mine", which is what lets the overlay keep its previous placement for one
        // frame instead of flickering. An empty list would be "definitely no decorations".
        val note = "[ ] milk"
        val raw = measureRaw(note)
        assertEquals(null, placeDecorations(note, raw))
        assertEquals(1, placeDecorations(note, measure(note))!!.size, "the matching layout does place it")

        val noDecorations = "just prose"
        assertEquals(emptyList(), placeDecorations(noDecorations, measure(noDecorations)), "genuinely none")
    }

    /** The same text measured without the display edits, i.e. the layout a caller gets if it forgets them. */
    private fun measureRaw(note: String): TextLayoutResult {
        val measurer =
            TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(),
                defaultDensity = Density(1f, 1f),
                defaultLayoutDirection = LayoutDirection.Ltr,
            )
        return measurer.measure(text = AnnotatedString(note), constraints = Constraints(maxWidth = 600))
    }
}
