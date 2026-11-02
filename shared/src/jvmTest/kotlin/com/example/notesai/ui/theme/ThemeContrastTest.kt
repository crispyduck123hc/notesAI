package com.example.notesai.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The colours have to be readable, in both schemes.
 *
 * The bug this exists for: nothing painted the background, so the window stayed white while the dark scheme
 * supplied light grey text, and selected rows were painted dark purple without their text colour following. Both
 * are contrast failures, and contrast is arithmetic — so it can be asserted rather than eyeballed.
 */
class ThemeContrastTest {
    /** WCAG relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(
        foreground: Color,
        background: Color,
    ): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun assertReadable(
        scheme: String,
        name: String,
        foreground: Color,
        background: Color,
    ) {
        val ratio = contrast(foreground, background)
        assertTrue(ratio >= 4.5, "$scheme $name is only ${"%.2f".format(ratio)}:1, below the 4.5:1 minimum")
    }

    private fun pairs(scheme: ColorScheme): Map<String, Pair<Color, Color>> =
        mapOf(
            "text on background" to (scheme.onBackground to scheme.background),
            "text on surface" to (scheme.onSurface to scheme.surface),
            "muted text on surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
            "text on a selected row" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
            "text on a primary button" to (scheme.onPrimary to scheme.primary),
            "text on a primary container" to (scheme.onPrimaryContainer to scheme.primaryContainer),
        )

    @Test
    fun theLightSchemeIsReadable() {
        pairs(LightColors).forEach { (name, colours) -> assertReadable("light", name, colours.first, colours.second) }
    }

    @Test
    fun theDarkSchemeIsReadable() {
        pairs(DarkColors).forEach { (name, colours) -> assertReadable("dark", name, colours.first, colours.second) }
    }

    @Test
    fun darkModeIsActuallyDark() {
        // The "grey text on a white window" bug in one assertion: if the background stayed light while the text
        // went light, the pair above fails — but a background that is not dark also breaks the promise of the mode.
        assertTrue(luminance(DarkColors.background) < 0.05, "a dark theme needs a dark background")
        assertTrue(luminance(LightColors.background) > 0.8, "and a light theme a light one")
    }

    @Test
    fun theTwoSchemesDiffer() {
        // Guards against one scheme silently being the other: dark text on a dark background passes nothing, but
        // an accidental copy would leave both "readable" and merely wrong.
        assertTrue(DarkColors.background != LightColors.background)
        assertTrue(DarkColors.onSurface != LightColors.onSurface)
    }
}
