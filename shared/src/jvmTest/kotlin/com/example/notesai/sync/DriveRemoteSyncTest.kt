package com.example.notesai.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import com.example.notesai.drive.DriveClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end over the real HTTP layer: DriveClient -> DriveRemote -> NoteSyncEngine,
 * against a stand-in Drive.
 *
 * InMemoryRemote cannot catch mistakes in the request/response contract — URL shape, the
 * two-step create, the `files` list envelope, or Drive's numeric `version` field that the
 * baseline depends on. This closes that gap without needing OAuth credentials.
 */
class DriveRemoteSyncTest {

    private fun device(): NoteRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NotesDatabase.Schema.create(driver)
        return NoteRepository(driver)
    }

    private fun remote(http: HttpClient, server: FakeDriveServer) = DriveRemote(
        DriveClient(http = http, accessToken = { "test-token" }, baseUrl = server.baseUrl),
    )

    @Test
    fun twoDevicesSyncThroughTheDriveHttpLayer() = runBlocking {
        val server = FakeDriveServer()
        val http = HttpClient()
        try {
            val a = device()
            val folderId = a.addFolder("work")
            a.addNote("nested note", folderId)
            a.addNote("root note")

            val first = NoteSyncEngine(a, remote(http, server)).sync()
            assertEquals(3, first.pushed, "folder + two notes")
            assertEquals(3, server.fileCount)

            // A fresh device bootstraps entirely over HTTP.
            val b = device()
            val second = NoteSyncEngine(b, remote(http, server)).sync()
            assertTrue(second.pulled >= 3, "expected >=3 pulls, got $second")
            assertEquals(0, b.pendingOutbox().size, "pulling must not queue work")

            val folder = b.allFolders.first().single { it.name == "work" }
            val nested = b.allNotes.first().single { it.title == "nested note" }
            assertEquals(folder.id, nested.folderId, "the note must land in its folder, not at the root")

            // An edit round-trips...
            val rootNoteId = a.allNotes.first().single { it.title == "root note" }.id
            a.updateNote(rootNoteId, "root note edited")
            assertEquals(1, NoteSyncEngine(a, remote(http, server)).sync().pushed)
            assertEquals(1, NoteSyncEngine(b, remote(http, server)).sync().pulled)
            assertEquals(
                "root note edited",
                b.allNotes.first().single { it.title == "root note edited" }.title,
            )

            // ...and a second sync with nothing to do must be a no-op, which only holds if
            // Drive's version token round-trips through the baseline correctly.
            val idle = NoteSyncEngine(a, remote(http, server)).sync()
            assertTrue(!idle.changed, "expected an idle sync, got $idle")
        } finally {
            http.close()
            server.stop()
        }
    }

    @Test
    fun listingFollowsDrivePaging() = runBlocking {
        // Page size 2 with 5 notes: a client that ignores nextPageToken would only ever see
        // two of them, and would then re-upload the rest as if they were new.
        val server = FakeDriveServer(maxPageSize = 2)
        val http = HttpClient()
        try {
            val a = device()
            repeat(5) { index -> a.addNote("note $index") }
            assertEquals(5, NoteSyncEngine(a, remote(http, server)).sync().pushed)
            assertEquals(5, server.fileCount)

            val b = device()
            NoteSyncEngine(b, remote(http, server)).sync()
            assertEquals(5, b.allNotes.first().size, "every page must be followed")
            assertTrue(server.listCalls >= 3, "expected multiple pages, got ${server.listCalls}")
        } finally {
            http.close()
            server.stop()
        }
    }

    @Test
    fun preExistingRowsArePublishedOverHttp() = runBlocking {
        val server = FakeDriveServer()
        val http = HttpClient()
        try {
            val a = device()
            val folderUuid = "33333333-3333-4333-8333-333333333333"
            a.upsertRemoteFolder(
                uuid = folderUuid,
                name = "legacy folder",
                parentId = NoteRepository.ROOT_FOLDER_ID,
                updatedAt = 1L,
                deletedAt = null,
            )
            a.upsertRemoteNote(
                uuid = "44444444-4444-4444-8444-444444444444",
                title = "legacy note",
                content = "legacy note",
                createdAt = 1L,
                folderId = a.folderIdByUuid(folderUuid)!!,
                updatedAt = 1L,
                deletedAt = null,
                conflictOf = null,
            )

            assertEquals(2, NoteSyncEngine(a, remote(http, server)).sync().pushed)
            assertEquals(2, server.fileCount)

            val b = device()
            NoteSyncEngine(b, remote(http, server)).sync()
            val folder = b.allFolders.first().single { it.name == "legacy folder" }
            assertEquals(folder.id, b.allNotes.first().single().folderId)
        } finally {
            http.close()
            server.stop()
        }
    }
}
