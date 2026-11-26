package com.example.notesai.ai

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tools the model may call, executed against a real in-memory repository — the same writes
 * the UI makes, so creating a note here is creating a note everywhere.
 */
class NoteToolboxTest {
    private fun toolbox(): Pair<NoteToolbox, NoteRepository> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NotesDatabase.Schema.create(it) }
        val repository = NoteRepository(driver)
        return NoteToolbox(repository) to repository
    }

    private suspend fun withFolder(
        toolbox: NoteToolbox,
        repository: NoteRepository,
        name: String,
    ) {
        repository.addFolder(name = name, parentId = NoteRepository.ROOT_FOLDER_ID)
        // The model's first move would be to look.
        toolbox.execute("list_folders", null)
    }

    @Test
    fun listFoldersNamesTheFoldersItHas() =
        runTest {
            val (toolbox, repository) = toolbox()
            repository.addFolder(name = "Ideas", parentId = NoteRepository.ROOT_FOLDER_ID)

            val result = toolbox.execute("list_folders", null)

            assertTrue(result.toString().contains("\"Ideas\""))
        }

    @Test
    fun createNoteCreatesThroughTheRepository() =
        runTest {
            val (toolbox, repository) = toolbox()
            withFolder(toolbox, repository, "Ideas")

            val result =
                toolbox.execute(
                    "create_note",
                    buildJsonObject {
                        put("folder", "Ideas")
                        put("title", "Groceries")
                        put("content", "milk")
                    },
                )

            assertTrue(result.toString().contains("\"created\""))
            val note = repository.allNotesNow().single()
            assertEquals("Groceries\nmilk", note.content, "the first line is the title, like every note")
            assertEquals(false, note.deletedAt != null)
        }

    @Test
    fun anUnknownFolderIsReportedNotThrown() =
        runTest {
            val (toolbox, repository) = toolbox()
            withFolder(toolbox, repository, "Ideas")

            val result =
                toolbox.execute(
                    "create_note",
                    buildJsonObject {
                        put("folder", "Work")
                        put("title", "T")
                        put("content", "C")
                    },
                )

            assertTrue(result.toString().contains("does not exist"), "the model reads the reason")
            assertTrue(result.toString().contains("\"Ideas\""), "the folders it can use are named")
            assertTrue(repository.allNotesNow().isEmpty(), "nothing was created")
        }

    @Test
    fun aBlankRequestCreatesNothing() =
        runTest {
            val (toolbox, repository) = toolbox()

            val result =
                toolbox.execute(
                    "create_note",
                    buildJsonObject {
                        put("title", "")
                        put("content", "")
                    },
                )

            assertTrue(result.toString().contains("no note was created"))
            assertTrue(repository.allNotesNow().isEmpty())
        }

    @Test
    fun writesNeedConfirmationReadsDoNot() {
        val (toolbox, _) = toolbox()

        assertFalse(toolbox.requiresConfirmation("list_folders"))
        assertTrue(toolbox.requiresConfirmation("create_note"))
    }

    @Test
    fun unknownToolsAreReportedAsErrors() =
        runTest {
            val (toolbox, _) = toolbox()

            val result = toolbox.execute("delete_everything", null)

            assertTrue(result.toString().contains("Unknown tool"))
            assertNotNull(result)
        }

    @Test
    fun toolSchemasDeclareTheirTypeExplicitly() {
        // The compact JSON config omits values equal to their default; a schema without an
        // explicit "type" is refused by the API ("only allowed for OBJECT type") — this is the
        // regression test for exactly that refusal.
        val (toolbox, _) = toolbox()
        val request =
            GeminiGenerateRequest(
                contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = "hi")))),
                tools = toolbox.declarations,
            )
        val json = geminiJson.encodeToString(request)

        assertEquals(2, json.split("\"type\":\"object\"").size - 1, "both declarations carry an explicit type")
        assertTrue(json.contains("\"required\""), "the create_note schema keeps its required list")
    }
}
