package com.example.notesai.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.TransformedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drawn text and the mapping back to the real one.
 *
 * A visual transformation that changes lengths is the classic way to get a caret that jumps, so the
 * mapping is asserted rather than assumed.
 */
class EditorTransformationTest {
    private fun transform(
        text: String,
        titleStyle: SpanStyle? = null,
    ): TransformedText = EditorTransformation(titleStyle).filter(AnnotatedString(text))

    @Test
    fun aTabIsDrawnAtFullWidth() {
        val result = transform("a\tb")

        assertEquals("a" + " ".repeat(TAB_COLUMNS) + "b", result.text.text)
    }

    @Test
    fun offsetsEitherSideOfATabMapBothWays() {
        val result = transform("a\tb")
        val mapping = result.offsetMapping

        // Original "a\tb": a=0, tab=1, b=2, end=3.
        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(1, mapping.originalToTransformed(1))
        assertEquals(1 + TAB_COLUMNS, mapping.originalToTransformed(2))
        assertEquals(2 + TAB_COLUMNS, mapping.originalToTransformed(3))

        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(1, mapping.transformedToOriginal(1))
        // Anywhere inside the expanded tab is still the tab itself.
        assertEquals(1, mapping.transformedToOriginal(1 + TAB_COLUMNS - 1))
        assertEquals(2, mapping.transformedToOriginal(1 + TAB_COLUMNS))
        assertEquals(3, mapping.transformedToOriginal(2 + TAB_COLUMNS))
    }

    @Test
    fun anIndentOfSeveralTabsMapsBackOneTabAtATime() {
        val text = "\t\t• milk"
        val result = transform(text)

        for (offset in 0..text.length) {
            val roundTripped =
                result.offsetMapping.transformedToOriginal(result.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, roundTripped, "offset $offset did not survive the round trip")
        }
    }

    @Test
    fun textWithoutTabsIsDrawnUnchanged() {
        val result = transform("plain note\nsecond line")

        assertEquals("plain note\nsecond line", result.text.text)
        assertEquals(5, result.offsetMapping.originalToTransformed(5))
        assertEquals(5, result.offsetMapping.transformedToOriginal(5))
    }

    @Test
    fun theTitleStyleCoversTheFirstLineInDrawnCoordinates() {
        val style = SpanStyle(fontSize = TitleStyle.fontSize)
        val result = transform("\tTitle\nbody", titleStyle = style)

        val spans = result.text.spanStyles
        assertEquals(1, spans.size)
        assertEquals(0, spans[0].start)
        // The title is 1 tab + "Title" = TAB_COLUMNS + 5 columns wide, not 6 characters.
        assertEquals(TAB_COLUMNS + "Title".length, spans[0].end)
    }

    @Test
    fun withoutAStyleNothingIsStyled() {
        assertTrue(transform("Title\nbody").text.spanStyles.isEmpty())
    }
}
