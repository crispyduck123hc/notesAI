package com.example.notesai.ai

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tool loop, end to end against a scripted server: the model asks, the toolbox executes
 * through a real repository, the user's approval is consulted for writes, and the reply lands
 * as text events.
 */
class AiSessionTest {
    private fun fixture(): Triple<AiSession, NoteRepository, FakeAiServer> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NotesDatabase.Schema.create(it) }
        val repository = NoteRepository(driver)
        repository.addFolder(name = "Ideas", parentId = NoteRepository.ROOT_FOLDER_ID)
        val server = FakeAiServer()
        val client = GeminiClient(http = HttpClient(), aiAccessToken = { "a-token" }, baseUrl = server.baseUrl)
        return Triple(AiSession(client, NoteToolbox(repository)), repository, server)
    }

    private fun functionCallReply(
        tool: String,
        folder: String,
    ): String =
        GeminiGenerateResponse(
            candidates =
                listOf(
                    GeminiCandidate(
                        content =
                            GeminiContent(
                                role = "model",
                                parts =
                                    listOf(
                                        GeminiPart(
                                            functionCall =
                                                GeminiFunctionCall(
                                                    name = tool,
                                                    args =
                                                        buildJsonObject {
                                                            put("folder", JsonPrimitive(folder))
                                                            put("title", JsonPrimitive("Groceries"))
                                                            put("content", JsonPrimitive("milk"))
                                                        },
                                                ),
                                        ),
                                    ),
                            ),
                    ),
                ),
        ).let { geminiJson.encodeToString(it) }

    private fun textReply(text: String): String =
        GeminiGenerateResponse(
            candidates =
                listOf(
                    GeminiCandidate(content = GeminiContent(role = "model", parts = listOf(GeminiPart(text = text)))),
                ),
        ).let { geminiJson.encodeToString(it) }

    @Test
    fun theModelAsksTheToolboxExecutesAndTheReplyFollows() =
        runTest {
            val (session, repository, server) = fixture()
            try {
                server.scripted.add(functionCallReply("create_note", folder = "Ideas"))
                server.scripted.add(textReply("I created the note."))

                val events =
                    session
                        .run("make a groceries note", "the body", approver = { true })
                        .toList()

                assertTrue(events.contains(AiEvent.ToolDone("create_note", approved = true, created = true)))
                // The model's confirmation after the tool turn is conversation, not note content.
                assertEquals(
                    "I created the note.",
                    events.filterIsInstance<AiEvent.Notice>().joinToString("") { it.message },
                )
                assertTrue(events.filterIsInstance<AiEvent.Text>().isEmpty(), "a tool turn's summary never lands in the note")
                assertEquals(1, repository.allNotesNow().size)
                assertEquals("Groceries\nmilk", repository.allNotesNow().single().content)
                assertEquals(2, server.calls)
            } finally {
                server.stop()
            }
        }

    @Test
    fun aRefusedToolCallTellsTheModelSoAndCreatesNothing() =
        runTest {
            val (session, repository, server) = fixture()
            try {
                server.scripted.add(functionCallReply("create_note", folder = "Ideas"))
                server.scripted.add(textReply("Understood, I will not create it."))

                val events =
                    session
                        .run("make a groceries note", "the body", approver = { false })
                        .toList()

                assertTrue(events.contains(AiEvent.ToolDone("create_note", approved = false, created = null)))
                assertTrue(repository.allNotesNow().isEmpty(), "the user's refusal is final")
                assertEquals(
                    "Understood, I will not create it.",
                    events.filterIsInstance<AiEvent.Notice>().single().message,
                )
            } finally {
                server.stop()
            }
        }

    @Test
    fun anUnknownFolderIsCorrectedByASecondToolTurn() =
        runTest {
            val (session, repository, server) = fixture()
            try {
                server.scripted.add(functionCallReply("create_note", folder = "Nonexistent"))
                server.scripted.add(functionCallReply("create_note", folder = "Ideas"))
                server.scripted.add(textReply("Done now."))

                val events =
                    session
                        .run("make a groceries note", "the body", approver = { true })
                        .toList()

                assertEquals("Done now.", events.filterIsInstance<AiEvent.Notice>().single().message)
                assertEquals(1, repository.allNotesNow().size, "the second attempt, with a real folder, created it")
                assertEquals(3, server.calls)
            } finally {
                server.stop()
            }
        }

    @Test
    fun aModelThatKeepsCallingToolsIsBounded() =
        runTest {
            val (session, repository, server) = fixture()
            try {
                repeat(5) { server.scripted.add(functionCallReply("list_folders", folder = "unused")) }

                val events =
                    session
                        .run("do things", "the body", approver = { true })
                        .toList()

                // The loop stops at the round limit: no infinite tool conversation.
                assertTrue(server.calls <= 8, "calls were ${server.calls}")
                assertFalse(repository.allNotesNow().isNotEmpty() && events.isEmpty())
            } finally {
                server.stop()
            }
        }
}
