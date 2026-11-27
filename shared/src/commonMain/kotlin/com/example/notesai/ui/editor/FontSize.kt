package com.example.notesai.ui.editor

import com.example.notesai.data.NoteRepository

/**
 * The editor's text size, as a scale on the theme's body size.
 *
 * A **setting, not a per-note property**: one reading size per account, so a note never looks
 * different on another device. Discrete steps, because continuous sliders make the field's
 * layout (and the overlay's rects) churn while dragging; four steps cover comfortable reading.
 */
val EDITOR_FONT_SCALES = listOf(0.85f, 1.0f, 1.15f, 1.3f)

const val EDITOR_FONT_SCALE_KEY = "editor.fontScale"

fun NoteRepository.editorFontScale(): Float =
    metadata(EDITOR_FONT_SCALE_KEY)
        ?.toFloatOrNull()
        ?.takeIf { it in EDITOR_FONT_SCALES }
        ?: 1f

fun NoteRepository.recordEditorFontScale(scale: Float) {
    putMetadata(EDITOR_FONT_SCALE_KEY, scale.toString())
}
