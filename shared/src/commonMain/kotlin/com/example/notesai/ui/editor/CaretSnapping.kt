package com.example.notesai.ui.editor

/**
 * Caret snapping: an inline widget is one thing, so the caret may not sit inside it.
 *
 * The deletion rule handles removing a token; this handles *landing* in one -- arrow keys, a click near its edge, Home
 * and End, an IME moving the caret. Compose has no equivalent of CodeMirror's `atomicRanges`, so the correction is done
 * after the fact: wherever the caret ends up, it is pushed to the nearer edge of a token it fell inside.
 *
 * Collapsed selections only. A selection that spans a token is a legitimate thing to have (select it, cut it, replace
 * it), and snapping that would make text selection jumpy for no benefit.
 */
internal fun snapCaretOutOfWidgets(
    text: String,
    from: Int,
    to: Int,
): Int {
    val clamped = to.coerceIn(0, text.length)
    val token = WidgetTokens.ranges(text).firstOrNull { clamped > it.first && clamped <= it.last } ?: return clamped
    // Direction matters, and "nearest edge" was wrong: moving left from just past a token lands one character inside,
    // and snapping back to the edge it came from would trap the caret against the token, unable to cross it. So travel
    // decides -- moving left exits at the token's start, moving right exits past its end -- which also makes the caret
    // skip the token in one step, the behaviour CodeMirror's `atomicRanges` gives for free.
    return if (clamped <= from) token.first else token.last + 1
}
