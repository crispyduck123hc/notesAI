package com.example.notesai.ai

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The two AI settings, stored per account, read at call time — so changes apply immediately. */
class AiSettingsTest {
    private fun repository(): NoteRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NotesDatabase.Schema.create(it) }
        return NoteRepository(driver)
    }

    @Test
    fun theModelDefaultsToTheConstantAndSurvivesAChange() {
        val repository = repository()

        assertEquals(DEFAULT_AI_MODEL, repository.aiModel())

        repository.recordAiModel("gemini-3.1-flash-lite")
        assertEquals("gemini-3.1-flash-lite", repository.aiModel())
    }

    @Test
    fun aBlankModelFallsBackToTheDefault() {
        val repository = repository()

        repository.recordAiModel("   ")
        assertEquals(DEFAULT_AI_MODEL, repository.aiModel())
    }

    @Test
    fun theQuotaProjectRoundTripsAndClears() {
        val repository = repository()

        assertNull(repository.aiQuotaProject())

        repository.recordAiQuotaProject(" my-project-123 ")
        assertEquals("my-project-123", repository.aiQuotaProject(), "stored trimmed")

        repository.recordAiQuotaProject("   ")
        assertNull(repository.aiQuotaProject(), "a blank clears the setting")
    }

    @Test
    fun theChosenModelReachesTheRequestUrl() =
        runTest {
            val server = FakeAiServer()
            try {
                val client = GeminiClient(http = HttpClient(), aiAccessToken = { "a-token" }, baseUrl = server.baseUrl)
                client.generate("hi", model = "gemini-3.1-flash-lite")
                assertEquals("gemini-3.1-flash-lite", server.lastModel)
            } finally {
                server.stop()
            }
        }
}
