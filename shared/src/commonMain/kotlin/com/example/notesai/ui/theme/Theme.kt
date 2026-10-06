package com.example.notesai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

private val LightColors = lightColorScheme(
    primary = Iris,
    onPrimary = Color.White,
    primaryContainer = IrisPale,
    onPrimaryContainer = Slate,
    secondaryContainer = IrisPale,
    onSecondaryContainer = Slate,
    background = Paper,
    surface = Paper,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC5BCF5),
    onPrimary = Slate,
    primaryContainer = IrisDeep,
    onPrimaryContainer = IrisMist,
    secondaryContainer = IrisDeep,
    onSecondaryContainer = IrisMist,
)

@Composable
fun NotesAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
