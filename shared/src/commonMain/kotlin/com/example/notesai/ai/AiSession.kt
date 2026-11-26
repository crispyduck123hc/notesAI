package com.example.notesai.ai

import com.example.notesai.ui.editor.askAiPrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What one session run reports back to the editor. */
internal sealed interface AiEvent {
    /** A piece of the reply, to insert into the note like typing. */
    data class Text(
        val chunk: String,
    ) : AiEvent

    /** A tool turn completed — confirmation approved or refused, execution succeeded or not. */
    data class ToolDone(
        val name: String,
        val approved: Boolean,
        val created: Boolean?,
    ) : AiEvent

    /** Text that is conversation, not note content — shown to the reader, never inserted. */
    data class Notice(
        val message: String,
    ) : AiEvent
}

/**
 * The tool loop over one free-text request.
 *
 * The model may ask the app to run a tool ([NoteToolbox]); write tools pass through
 * [approver] first — the model proposes, a human click decides — and the tool's result goes
 * back as a new turn so the model can continue. Text parts stream out as [AiEvent.Text] as
 * they arrive, exactly like the no-tools path. The loop is bounded: a model that keeps asking
 * for tools is stopped rather than allowed to spin.
 *
 * Function-call turns are echoed back **verbatim** (thought signature included) — the API
 * refuses continuations that drop the signature, verified live.
 */
internal class AiSession(
    private val client: GeminiClient,
    private val toolbox: NoteToolbox,
) {
    // The loop breaks in two ways by design: a plain text turn ends the session, a tool round
    // continues it; the round cap ends it again. (detekt: LoopWithTooManyJumpStatements)
    @Suppress("LoopWithTooManyJumpStatements")
    fun run(
        request: String,
        noteText: String,
        model: String = DEFAULT_AI_MODEL,
        approver: suspend (GeminiFunctionCall) -> Boolean,
    ): Flow<AiEvent> =
        flow {
            val contents =
                mutableListOf(
                    GeminiContent(role = "user", parts = listOf(GeminiPart(text = askAiPrompt(noteText = noteText, request = request)))),
                )
            var rounds = 0
            // Once a tool has run, the model's words are conversation ("I have created the note…")
            // — a real note was created and the model's confirmation must not land in the note's
            // text, which is exactly the duplication report that motivated this branch.
            var toolUsed = false
            while (true) {
                val modelParts = mutableListOf<GeminiPart>()
                var toolCall: GeminiFunctionCall? = null
                client
                    .streamConversation(contents = contents, tools = toolbox.declarations, model = model)
                    .collect { part ->
                        when {
                            part.functionCall != null -> {
                                modelParts += part
                                toolCall = part.functionCall
                            }
                            part.text?.isNotEmpty() == true -> {
                                modelParts += part
                                if (toolUsed) {
                                    emit(AiEvent.Notice(part.text))
                                } else {
                                    emit(AiEvent.Text(part.text))
                                }
                            }
                        }
                    }

                val call = toolCall ?: break // a plain text turn: the session is done
                rounds++
                contents += GeminiContent(role = "model", parts = modelParts)

                val approved = approver(call)
                val response =
                    if (approved) {
                        toolbox.execute(call.name, call.args)
                    } else {
                        buildJsonObject { put("status", "refused by the user") }
                    }
                toolUsed = true
                emit(AiEvent.ToolDone(call.name, approved, (response["created"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()))

                contents +=
                    GeminiContent(
                        role = "user",
                        parts = listOf(GeminiPart(functionResponse = GeminiFunctionResponse(name = call.name, response = response))),
                    )
                if (rounds >= MAX_TOOL_ROUNDS) {
                    // Bound the loop: whatever the model wanted, the user gets their note back.
                    contents +=
                        GeminiContent(
                            role = "user",
                            parts = listOf(GeminiPart(text = "No more tools are available. Finish the reply as plain text now.")),
                        )
                    client
                        .streamConversation(contents = contents, tools = null, model = model)
                        .collect { part -> part.text?.takeIf { it.isNotEmpty() }?.let { emit(AiEvent.Text(it)) } }
                    break
                }
            }
        }

    private companion object {
        const val MAX_TOOL_ROUNDS = 3
    }
}

/**
 * The editor's handle on a session, so the pane depends on an interface rather than on the
 * transport: a test supplies its own invoker and drives the events itself.
 */
internal fun interface AiSessionInvoker {
    fun run(
        request: String,
        noteText: String,
        approver: suspend (GeminiFunctionCall) -> Boolean,
    ): Flow<AiEvent>
}
