package com.example.notesai.ui.editor

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle

/**
 * The editor's text colour, taken from the theme.
 *
 * This exists because of a real bug: `BasicTextField` paints with the style it is handed, and `MaterialTheme.typography`
 * styles carry no colour. Material's `OutlinedTextField` used to substitute `TextFieldColors.textColor(...)`, so when the
 * editor moved to `BasicTextField` the note fell back to the platform default -- black -- and became unreadable in dark
 * mode while its placeholder (coloured explicitly) stayed correct.
 */
internal fun editorTextStyle(
    base: TextStyle,
    colors: ColorScheme,
): TextStyle = base.copy(color = colors.onSurface)

/**
 * The caret colour. `BasicTextField`'s default brush is solid black, which is invisible on a dark background.
 */
internal fun editorCursorBrush(colors: ColorScheme): Brush = SolidColor(colors.primary)
