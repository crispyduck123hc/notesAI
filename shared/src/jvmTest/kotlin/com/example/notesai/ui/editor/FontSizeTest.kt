package com.example.notesai.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.example.notesai.PendingEdit
import com.example.notesai.data.NoteRepository
import com.example.notesai.db.NotesDatabase
import com.example.notesai.model.NoteDocument
import com.example.notesai.model.NoteRef
import kotlin.test.Test
import kotlin.test.assertEquals

/** The reading size: stored per account, applied live, stepped with A−/A+. */
@OptIn(ExperimentalTestApi::class)
class FontSizeTest {
    @Test
    fun theScaleRoundTripsThroughTheRepository() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { NotesDatabase.Schema.create(it) }
        val repository = NoteRepository(driver)

        assertEquals(1f, repository.editorFontScale(), "the default is the theme's own size")

        repository.recordEditorFontScale(1.15f)
        assertEquals(1.15f, repository.editorFontScale())

        // A corrupt or hand-edited value falls back instead of crashing the editor.
        repository.recordEditorFontScale(99f)
        repository.putMetadata(EDITOR_FONT_SCALE_KEY, "not-a-number")
        assertEquals(1f, repository.editorFontScale(), "an unknown scale falls back to the default")
    }

    @Test
    fun steppingChangesTheDrawnScaleAndPersists() =
        runComposeUiTest {
            // The parent owns the setting, exactly as NotesScreen does: it records and recomposes.
            var scale by mutableStateOf(1f)
            var recorded: Float? = null
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "body"),
                    onChange = { _, _ -> },
                    editorFontScale = scale,
                    onEditorFontScaleChange = { newScale ->
                        recorded = newScale
                        scale = newScale
                    },
                )
            }

            assertEquals(1f, fontSize())

            onNodeWithTag("format-menu").performClick()
            onNodeWithTag("font-size-up").performClick()

            assertEquals(1.15f, fontSize())
            assertEquals(1.15f, recorded)
        }

    @Test
    fun theFloorDisablesSteppingDown() =
        runComposeUiTest {
            var scale by mutableStateOf(0.85f)
            var recorded: Float? = null
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "body"),
                    onChange = { _, _ -> },
                    editorFontScale = scale,
                    onEditorFontScaleChange = { newScale ->
                        recorded = newScale
                        scale = newScale
                    },
                )
            }

            onNodeWithTag("format-menu").performClick()
            // The floor item is disabled — assert the semantics rather than clicking it, since a
            // disabled node rejects performClick outright.
            val downConfig = onNodeWithTag("font-size-down").fetchSemanticsNode().config
            downConfig.contains(SemanticsProperties.Disabled)

            assertEquals(0.85f, fontSize())
            assertEquals(null, recorded, "the floor does not call back with a change")
        }

    private fun androidx.compose.ui.test.ComposeUiTest.fontSize(): Float? = onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(EditorFontScaleKey)
}
