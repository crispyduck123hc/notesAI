package com.example.notesai.ui.editor

/**
 * Assembles what one "Ask AI" invocation sends to the model.
 *
 * The note the action is invoked on is the only context sent, plus the user's request — that is
 * the whole privacy statement of the feature, and it lives here so it is stated in one place.
 * The reply is asked to be insertable text only, because whatever comes back goes straight into
 * the note at the caret.
 */
internal fun askAiPrompt(
    noteText: String,
    request: String,
): String =
    buildString {
        append("You are helping edit a note in a plain-text notes app. The note's current text:\n\n")
        append(noteText)
        append("\n\nThe user's request: ")
        append(request)
        append(
            "\n\nReply with only the text to insert into the note — no preamble, no explanation. " +
                "To edit existing text, repeat the passage you are replacing.",
        )
    }
