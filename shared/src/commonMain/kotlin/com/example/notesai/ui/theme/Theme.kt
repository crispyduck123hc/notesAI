package com.example.notesai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Every visual decision lives in `ui/theme`. Screens reference [MaterialTheme] and [Dimens]
 * rather than literal colours and sizes, so restyling means editing this directory and
 * nothing else.
 */

private val Iris = Color(0xFF5B4BC4)
private val IrisPale = Color(0xFFD9D2F5)
private val IrisDeep = Color(0xFF453A7A)
private val IrisMist = Color(0xFFE8E3FF)
private val Slate = Color(0xFF2B2A33)
private val Paper = Color(0xFFFDFCFF)
private val Midnight = Color(0xFF1B1922)
private val MidnightRaised = Color(0xFF2A2733)
private val SlateMuted = Color(0xFF5A5570)
private val IrisMuted = Color(0xFFC9C3DA)

internal val LightColors =
    lightColorScheme(
        primary = Iris,
        onPrimary = Color.White,
        primaryContainer = IrisPale,
        onPrimaryContainer = Slate,
        secondaryContainer = IrisPale,
        onSecondaryContainer = Slate,
        background = Paper,
        onBackground = Slate,
        surface = Paper,
        onSurface = Slate,
        surfaceVariant = IrisMist,
        onSurfaceVariant = SlateMuted,
        outline = Color(0xFF8B85A0),
    )

internal val DarkColors =
    darkColorScheme(
        primary = Color(0xFFC5BCF5),
        onPrimary = Slate,
        primaryContainer = IrisDeep,
        onPrimaryContainer = IrisMist,
        // The selected-row colour. Its companion `onSecondaryContainer` is what the row's text must use —
        // painting the background without also supplying the text colour is how a row ends up black on
        // dark purple.
        secondaryContainer = IrisDeep,
        onSecondaryContainer = IrisMist,
        background = Midnight,
        onBackground = IrisMist,
        surface = Midnight,
        onSurface = IrisMist,
        surfaceVariant = MidnightRaised,
        onSurfaceVariant = IrisMuted,
        outline = Color(0xFF6E6880),
    )

@Composable
fun NotesAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors) {
        // A MaterialTheme on its own paints nothing and sets no content colour: the window kept whatever
        // background the platform had (white, on desktop) while the scheme supplied light text for dark mode —
        // grey text on a white window. The Surface is what makes the palette reach the pixels.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
