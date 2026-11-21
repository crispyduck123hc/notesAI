package com.example.notesai.ui.editor

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp

/**
 * The editor's input pipeline, adapted to the editing rules this codebase already had.
 *
 * `applyEditorTyping` and its helpers are written against a `TextFieldValue` -- "previous value, proposed value" -- which
 * is exactly what a [TextFieldBuffer] describes: [TextFieldBuffer.originalText] with [TextFieldBuffer.originalSelection]
 * is the previous value, and the buffer itself is the proposed one. So the rules stay untouched and un-duplicated; this
 * is only the adapter.
 *
 * One deliberate difference: a text field no longer hands us an IME *composition* range on the buffer, so the rules see
 * `composition = null`. Their length and shape guards still do the work -- a list is continued only by exactly one
 * newline inserted at a collapsed caret, a shortcut only by a single typed character -- but a composition that happens
 * to look identical to typing would now be treated as typing. If Android's soft keyboards ever show a symptom, the fix
 * belongs here: compare [TextFieldBuffer.changes] instead of relying on the composition range.
 */
internal fun editorInputTransformation(): InputTransformation =
    InputTransformation {
        val before = asValue(originalText, originalSelection)
        val after = asValue(asCharSequence(), selection)
        val result = applyEditorTyping(before, after)
        if (result == after) return@InputTransformation

        replace(0, length, result.text)
        selection = TextRange(result.selection.start, result.selection.end)
    }

/**
 * What the field lays out, built from the same [displayEdits] the phase-0 tests assert against: tabs at [TAB_COLUMNS]
 * columns, and checklist markers drawn as circles instead of as their Markdown-ish characters.
 *
 * Compose derives the offset mapping from these replacements, so the hand-written `OffsetMapping` the visual
 * transformation needed is gone. That mapping is the reason this file is small: a replacement records "this drawn range
 * came from that text range", and the caret follows it.
 *
 * [titleStyle] styles the first line -- a local note's first line is its title. A span style only: a `ParagraphStyle`
 * here would make Compose lay each paragraph boundary out as its own line, which is how the editor ended up with phantom
 * lines and a caret on the wrong one.
 */
internal fun editorOutputTransformation(titleStyle: SpanStyle? = null): OutputTransformation =
    OutputTransformation {
        val original = asCharSequence().toString()
        // Right to left, so every edit is applied against offsets that are still valid.
        displayEdits(original).sortedByDescending { it.start }.forEach { edit ->
            replace(edit.start, edit.end, edit.text)
        }

        if (titleStyle != null) {
            val firstLineEnd = original.indexOf('\n').let { if (it == -1) original.length else it }
            addStyle(titleStyle, start = 0, end = drawnOffset(original, firstLineEnd))
        }
    }

/** The style the first line of a local note is drawn in -- bigger and heavier, like a heading. */
internal val TitleStyle: SpanStyle = SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)

private fun asValue(
    text: CharSequence,
    selection: TextRange,
): TextFieldValue = TextFieldValue(text.toString(), TextRange(selection.start, selection.end))
