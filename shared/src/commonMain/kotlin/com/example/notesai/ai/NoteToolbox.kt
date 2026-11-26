package com.example.notesai.ai

import com.example.notesai.data.NoteRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The app features the model may ask to use, declared as tools and executed locally.
 *
 * The model never touches storage: it produces a **request** for a tool, and this class maps
 * that request onto [NoteRepository] — the same writes the UI makes, so the outbox, sync and
 * conflict rules apply unchanged. Read tools execute silently; write tools are gated by the
 * caller's approval step (see [requiresConfirmation]) before [execute] is ever reached.
 */
internal class NoteToolbox(
    private val repository: NoteRepository,
) {
    /** What the model is told it may do. Deliberately small; the tool loop bounds its use. */
    val declarations: List<GeminiTool> =
        listOf(
            GeminiTool(
                functionDeclarations =
                    listOf(
                        GeminiFunctionDeclaration(
                            name = TOOL_LIST_FOLDERS,
                            description = "List the note folders that exist, so a new note can be placed correctly.",
                            parameters = GeminiSchema(type = "object", properties = emptyMap()),
                        ),
                        GeminiFunctionDeclaration(
                            name = TOOL_CREATE_NOTE,
                            description = "Create a new note in a folder. The note's first line is its title.",
                            parameters =
                                GeminiSchema(
                                    type = "object",
                                    properties =
                                        mapOf(
                                            "folder" to GeminiSchemaProperty(type = "string", description = "folder name, exactly as list_folders returned it; omit for the root"),
                                            "title" to GeminiSchemaProperty(type = "string", description = "the note's title"),
                                            "content" to GeminiSchemaProperty(type = "string", description = "the note's body text"),
                                        ),
                                    required = listOf("title", "content"),
                                ),
                        ),
                    ),
            ),
        )

    /**
     * Writes need a human click before they happen: the model proposes, the user approves.
     * Read tools are safe by construction (they reveal only folder names).
     */
    fun requiresConfirmation(name: String): Boolean = name == TOOL_CREATE_NOTE

    /**
     * Executes one tool request and returns the JSON the model sees as the tool's result.
     * Failures are returned as JSON, not thrown, so the model can read the reason and correct
     * itself (for example by calling list_folders after an unknown folder name).
     */
    suspend fun execute(
        name: String,
        args: JsonObject?,
    ): JsonObject =
        when (name) {
            TOOL_LIST_FOLDERS -> listFolders()
            TOOL_CREATE_NOTE -> createNote(args)
            else -> errorResponse("Unknown tool: $name")
        }

    private fun listFolders(): JsonObject =
        buildJsonObject {
            putJsonArray("folders") {
                repository
                    .allFoldersNow()
                    .filter { it.deletedAt == null }
                    .filter { it.uuid != NoteRepository.ROOT_FOLDER_UUID }
                    .forEach { folder ->
                        add(
                            buildJsonObject {
                                put("name", folder.name)
                                put("uuid", folder.uuid)
                            },
                        )
                    }
            }
        }

    // Each early return is a distinct outcome the model reads as the tool's result: unknown
    // folder, empty request, or success. (detekt: ReturnCount)
    @Suppress("ReturnCount")
    private suspend fun createNote(args: JsonObject?): JsonObject {
        val folderName = args?.string("folder").orEmpty().trim()
        val title = args?.string("title").orEmpty().trim()
        val content = args?.string("content").orEmpty()
        if (title.isEmpty() && content.isEmpty()) {
            return errorResponse("Both title and content were empty, so no note was created.")
        }

        val folderId =
            when {
                folderName.isEmpty() -> NoteRepository.ROOT_FOLDER_ID
                else -> {
                    val match =
                        repository
                            .allFoldersNow()
                            .firstOrNull { it.deletedAt == null && it.name.equals(folderName, ignoreCase = true) }
                    if (match == null) {
                        // Report, don't throw: the model reads the reason and can correct itself.
                        return buildJsonObject {
                            put("created", false)
                            put("error", "Folder '$folderName' does not exist.")
                            put("folders", listFolders()["folders"]!!)
                        }
                    }
                    repository.folderIdByUuid(match.uuid) ?: NoteRepository.ROOT_FOLDER_ID
                }
            }

        val text = if (content.isEmpty()) title else "$title\n$content"
        repository.addNote(text = text, folderId = folderId)
        return buildJsonObject {
            put("created", true)
            put("title", title)
            put("folder", if (folderName.isEmpty()) "root" else folderName)
        }
    }

    private fun errorResponse(message: String): JsonObject =
        buildJsonObject {
            put("error", message)
        }
}

private fun JsonObject.string(key: String): String? {
    val element = this[key]
    return (element as? kotlinx.serialization.json.JsonPrimitive)?.content
}

private const val TOOL_LIST_FOLDERS = "list_folders"
private const val TOOL_CREATE_NOTE = "create_note"
