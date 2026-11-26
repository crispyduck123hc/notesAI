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
): String = buildAiPrompt(noteText, task = "The user's request: " + request)

/**
 * The shared skeleton. The task comes **first**: instruction-then-context is the ordering that
 * least invites a small model to echo its input, and echo is this feature's known failure mode.
 */
private fun buildAiPrompt(
    noteText: String,
    task: String,
): String =
    buildString {
        append("You are helping edit a note in a plain-text notes app.\n\n")
        append(task)
        append("\n\nThe note's current text:\n\n")
        append(noteText)
        append(
            "\n\nReply with only the new text to insert into the note — no preamble, no explanation. " +
                "Do not repeat the note's existing text: it is already in the note, and repeating " +
                "it would duplicate it.",
        )
    }
