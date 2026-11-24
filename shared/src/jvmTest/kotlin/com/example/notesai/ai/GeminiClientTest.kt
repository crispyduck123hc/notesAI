package com.example.notesai.ai

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Gemini client against a stand-in server: the HTTP path, the headers Google needs, the SSE
 * line protocol, and the error mapping that decides what the reader is told. No network, no
 * credentials — the same trade the Drive tests made.
 */
class GeminiClientTest {
    private fun client(
        server: FakeAiServer,
        token: String? = "a-token",
        quotaProject: String? = null,
    ): GeminiClient =
        GeminiClient(
            http = HttpClient(),
            aiAccessToken = { token },
            quotaProject = { quotaProject },
            baseUrl = server.baseUrl,
        )

    @Test
    fun generateReturnsTheModelText() =
        runTest {
            val server = FakeAiServer()
            try {
                assertEquals("The whole answer", client(server).generate("summarize this"))
                assertEquals("summarize this", server.lastPrompt)
            } finally {
                server.stop()
            }
        }

    @Test
    fun theBearerTokenAndTheQuotaProjectHeaderTravel() =
        runTest {
            val server = FakeAiServer()
            try {
                client(server, quotaProject = "my-project-123").generate("hi")
                assertEquals("Bearer a-token", server.lastAuthorization)
                assertEquals("my-project-123", server.lastQuotaProject)
            } finally {
                server.stop()
            }
        }

    @Test
    fun theQuotaProjectHeaderStaysHomeWhenThereIsNoProject() =
        runTest {
            val server = FakeAiServer()
            try {
                client(server, quotaProject = null).generate("hi")
                assertNull(server.lastQuotaProject)
            } finally {
                server.stop()
            }
        }

    @Test
    fun streamingConcatenatesTheChunksInOrder() =
        runTest {
            val server = FakeAiServer()
            try {
                val pieces = client(server).streamGenerate("hi").toList()
                assertEquals(listOf("First piece. ", "Second piece. ", "Third piece."), pieces)
            } finally {
                server.stop()
            }
        }

    @Test
    fun withoutAiEnabledTheClientRefusesUpFront() =
        runTest {
            val server = FakeAiServer()
            try {
                try {
                    client(server, token = null).generate("hi")
                    fail("expected AiException")
                } catch (e: AiException) {
                    assertEquals(AiErrorKind.ACCESS, e.kind)
                    assertTrue(e.message!!.contains("not enabled"))
                    assertNull(server.lastAuthorization, "no request should have left the app")
                }
            } finally {
                server.stop()
            }
        }

    private suspend fun refusedQuotaTest(
        status: Int,
        body: String,
        expectedKind: AiErrorKind,
        messageFragment: String,
    ) {
        val server = FakeAiServer()
        try {
            server.failWith = status to body
            try {
                client(server).generate("hi")
                fail("expected AiException")
            } catch (e: AiException) {
                assertEquals(expectedKind, e.kind)
                assertTrue(e.message!!.contains(messageFragment), "message was: ${e.message}")
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun quotaExhaustionIsNamedAsQuota() =
        runTest {
            refusedQuotaTest(429, errorJson(429, "RESOURCE_EXHAUSTED", "quota exceeded"), AiErrorKind.QUOTA, "free-tier quota")
        }

    @Test
    fun theApiBeingOffIsNamedWithItsFix() =
        runTest {
            refusedQuotaTest(
                403,
                errorJson(403, "PERMISSION_DENIED", "Generative Language API has not been used in project"),
                AiErrorKind.ACCESS,
                "Generative Language API is switched off",
            )
        }

    @Test
    fun aMissingQuotaProjectIsNamedWithItsFix() =
        runTest {
            refusedQuotaTest(
                403,
                errorJson(403, "PERMISSION_DENIED", "Caller does not have permission to use the quota project"),
                AiErrorKind.ACCESS,
                "which Cloud project the AI usage counts against",
            )
        }

    @Test
    fun anExpiredGrantSaysToConsentAgain() =
        runTest {
            refusedQuotaTest(401, errorJson(401, "UNAUTHENTICATED", "Invalid Credentials"), AiErrorKind.ACCESS, "re-enable AI")
        }

    @Test
    fun aBadRequestIsReportedAsTheAppsFault() =
        runTest {
            refusedQuotaTest(400, errorJson(400, "INVALID_ARGUMENT", "Model not found"), AiErrorKind.REQUEST, "Model not found")
        }

    @Test
    fun anUnrecognisedFailureStillNamesTheStatus() =
        runTest {
            refusedQuotaTest(500, "not json at all", AiErrorKind.OTHER, "HTTP 500")
        }
}
