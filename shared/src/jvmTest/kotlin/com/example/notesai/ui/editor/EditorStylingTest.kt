package com.example.notesai.ui.editor

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The theme's colours actually reach the field.
 *
 * A regression test for a real bug: after the editor moved from Material's `OutlinedTextField` to `BasicTextField`, the
 * text was painted black in both schemes (the style carried no colour and the field fell back to the platform default),
 * and the caret was invisible in dark mode because `BasicTextField`'s default brush is solid black.
 */
class EditorStylingTest {
    private val base = TextStyle(fontSize = 16.sp)

    @Test
    fun theTextColourComesFromTheThemeInBothSchemes() {
        val dark = darkColorScheme()
        val light = lightColorScheme()

        assertEquals(dark.onSurface, editorTextStyle(base, dark).color)
        assertEquals(light.onSurface, editorTextStyle(base, light).color)
        assertNotEquals(Color.Black, editorTextStyle(base, dark).color, "black text on a dark surface")
    }

    @Test
    fun theCaretIsVisibleAgainstTheBackground() {
        val dark = darkColorScheme()
        val brush = editorCursorBrush(dark)

        assertEquals(SolidColor(dark.primary), brush)
        assertNotEquals(SolidColor(Color.Black), brush, "the field's default brush, invisible in dark mode")
    }
}
