package com.example.notesai.sync

import com.example.notesai.data.EntityType
import com.example.notesai.db.FolderEntity
import com.example.notesai.db.NoteEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncFilesTest {

    private val note = NoteEntity(
        id = 7L,
        title = "Hello",
        content = "Hello\nworld",
        createdAt = 1_000L,
        folderId = 1L,
        uuid = "6b84a396-1564-4521-a1bd-21f89159bb02",
        updatedAt = 2_000L,
        deletedAt = null,
        conflictOf = null,
    )

    private val folder = FolderEntity(
        id = 2L,
        name = "work",
        parentId = 1L,
        sortOrder = 0,
        uuid = "d650a43c-e92b-4e61-9862-eb4b87088f29",
        updatedAt = 3_000L,
        deletedAt = null,
    )

    @Test
    fun noteRoundTrips() {
        val file = note.toNoteFile(parentUuid = "parent-uuid")
        assertEquals(file, decodeNoteFile(file.encode()))
    }

    @Test
    fun folderRoundTrips() {
        val file = folder.toFolderFile(parentUuid = "parent-uuid")
        assertEquals(file, decodeFolderFile(file.encode()))
    }

    @Test
    fun encodingIsDeterministic() {
        val file = note.toNoteFile(parentUuid = "parent-uuid")
        assertEquals(file.encode(), file.encode())
    }

    @Test
    fun encodedNoteCarriesTypeVersionAndNullFields() {
        val text = note.toNoteFile(parentUuid = "parent-uuid").encode()
        val decoded = decodeNoteFile(text)

        assertEquals(EntityType.NOTE, decoded.type)
        assertEquals(CURRENT_FORMAT_VERSION, decoded.formatVersion)
        // Null fields are written explicitly so identical state is byte-identical.
        assertTrue(text.contains("\"deletedAt\": null"), text)
        assertTrue(text.contains("\"conflictOf\": null"), text)
    }

    @Test
    fun fileNamesRoundTrip() {
        assertEquals(
            RemoteFileRef(note.uuid, isFolder = false),
            parseFileName(noteFileName(note.uuid)),
        )
        assertEquals(
            RemoteFileRef(folder.uuid, isFolder = true),
            parseFileName(folderFileName(folder.uuid)),
        )
        assertNull(parseFileName("random.txt"))
    }

    @Test
    fun mappingBackToEntityDerivesTitleAndKeepsIds() {
        val restored = note.toNoteFile(parentUuid = "parent-uuid")
            .toEntity(localId = 42L, localFolderId = 9L)

        assertEquals(42L, restored.id)
        assertEquals(9L, restored.folderId)
        assertEquals(note.uuid, restored.uuid)
        assertEquals(note.content, restored.content)
        assertEquals("Hello", restored.title) // derived from the first line
        assertEquals(note.createdAt, restored.createdAt)
        assertEquals(note.updatedAt, restored.updatedAt)
    }

    @Test
    fun tombstoneAndConflictStateSurviveRoundTrip() {
        val deleted = note.copy(deletedAt = 5_000L, conflictOf = "original-uuid")
        val restored = decodeNoteFile(deleted.toNoteFile(parentUuid = "p").encode())

        assertEquals(5_000L, restored.deletedAt)
        assertEquals("original-uuid", restored.conflictOf)
    }
}
