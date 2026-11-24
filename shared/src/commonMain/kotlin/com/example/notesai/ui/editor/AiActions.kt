package com.example.notesai.ui.editor

/**
 * The action catalog: pre-written requests over the same machinery as "Ask anything…".
 *
 * Every action shares [askAiPrompt]'s privacy statement — the note's current text is the only
 * context, the reply is asked to be insertable text — and differs only in the instruction. The
 * catalog is data, not composables, so the editor stays a consumer and tests can pin the prompts.
 */
enum class AiAction(
    val label: String,
) {
    SUMMARIZE("Summarize note"),
    CHECKLIST("Turn into checklist"),
    TITLE("Suggest title"),
    ACTIONS("Extract actions"),
}

/** The prompt for one catalog action. [AiAction.TITLE]'s result replaces the note's first line. */
fun aiActionPrompt(
    action: AiAction,
    noteText: String,
): String {
    val instruction =
        when (action) {
            AiAction.SUMMARIZE -> "Summarize the note in a few sentences."
            AiAction.CHECKLIST ->
                "Rewrite the note's content as a checklist, one \"[ ] \" item per task, in the note's own order. " +
                    "Keep the original wording where it already works."
            AiAction.TITLE -> "Suggest a short title for the note: a few words, no quotes, no trailing period."
            AiAction.ACTIONS ->
                "Extract the concrete action items the note implies, one per line as \"[ ] \" items. " +
                    "If there are none, reply with a single line saying so."
        }
    return buildString {
        append("You are helping edit a note in a plain-text notes app. The note's current text:\n\n")
        append(noteText)
        append("\n\nThe task: ")
        append(instruction)
        append(
            "\n\nReply with only the text to insert into the note — no preamble, no explanation. " +
                "To edit existing text, repeat the passage you are replacing.",
        )
    }
}
