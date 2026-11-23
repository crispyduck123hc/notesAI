package com.example.notesai.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * A single-character delete next to a widget token removes the whole token instead.
 *
 * This is the "atomic" half of an inline widget, and the reason it lives in the typing rules rather than in a key
 * handler: the same edit arrives from a hardware Backspace, a soft keyboard's delete and an IME, so all three have to
 * behave the same way. One character of `!n:1a2b3c4d` is meaningless, and leaving it behind would produce prose that
 * looks like a typo the user cannot explain.
 *
 * Only a *single-character* delete is widened. Selecting part of a token and deleting it is the user's own choice, and a
 * paste or a replacement is not a deletion at all.
 */
@Suppress("ReturnCount") // two early exits: not a single delete, then not next to a token
fun deleteWholeWidgetToken(
    previous: TextFieldValue,
    current: TextFieldValue,
): TextFieldValue? {
    val deletedAt = singleDeletionAt(previous, current) ?: return null
    val token = WidgetTokens.rangeAt(previous.text, deletedAt) ?: return null

    val text = previous.text
    return previous.copy(
        text = text.removeRange(token.first, token.last + 1),
        // The caret lands where the token started: the token is gone, so that is the seam it left behind.
        selection = TextRange(token.first),
        composition = null,
    )
}
