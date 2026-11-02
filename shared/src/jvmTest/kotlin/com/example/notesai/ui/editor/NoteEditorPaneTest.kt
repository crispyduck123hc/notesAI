package com.example.notesai.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.notesai.model.NoteDocument
import com.example.notesai.model.NoteRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor, driven for real: a virtual composition, a real text field, and the debounce and
 * flush-on-dispose behaviour that the Phase 3 refactor re-keyed.
 *
 * These exist because the rest of the suite cannot see this code at all — the refactor changed what
 * the editor is keyed on, and a mistake there writes one note's text into another.
 */
@OptIn(ExperimentalTestApi::class)
class NoteEditorPaneTest {
    /**
     * What the field *draws*, which is what its semantics report: a tab is one character in the note but
     * [TAB_COLUMNS] columns on screen. The characters themselves are asserted in `BulletPointsTest`.
     */
    private fun drawn(text: String): String = text.replace("\t", " ".repeat(TAB_COLUMNS))

    @Test
    fun aDocumentIsShownInTheField() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "first line", content = "first line\nbody"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).assertTextEquals(drawn("first line\nbody"))
        }

    @Test
    fun noDocumentShowsThePlaceholder() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(document = null, onChange = {})
            }

            onNodeWithText("Select or create a note").assertExists()
        }

    @Test
    fun aBurstOfKeystrokesBecomesOneWrite() =
        runComposeUiTest {
            val writes = mutableListOf<String>()
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "", content = ""),
                    onChange = { writes += it },
                )
            }

            onNode(hasSetTextAction()).performClick()
            // The debounce runs on real time in this harness rather than the virtual clock, so the
            // coalescing is what gets asserted: three inputs inside one window must not become three
            // database writes.
            onNode(hasSetTextAction()).performTextInput("a")
            onNode(hasSetTextAction()).performTextInput("b")
            onNode(hasSetTextAction()).performTextInput("c")

            waitUntil(timeoutMillis = 2_000) { writes.isNotEmpty() }
            waitUntil(timeoutMillis = 2_000) { writes.last() == "abc" }
            assertEquals(1, writes.size, "the burst should have coalesced into a single write: $writes")
        }

    @Test
    fun switchingNotesFlushesThePendingTextToTheNoteItCameFrom() =
        runComposeUiTest {
            // The subtle case: an unflushed edit belongs to the note that was open when it was typed.
            // The refactor keys that flush on `document.ref`, so this is the test that would catch a
            // refactor that let the text follow the selection instead.
            val writes = mutableListOf<Pair<NoteRef, String>>()
            var current by mutableStateOf<NoteRef>(NoteRef.Local(1))

            setContent {
                val document =
                    NoteDocument(
                        ref = current,
                        title = if (current == NoteRef.Local(1)) "one" else "two",
                        content = if (current == NoteRef.Local(1)) "one" else "two",
                    )
                NoteEditorPane(
                    document = document,
                    // Closes over `document` from the composition that created it, which is exactly
                    // how NotesScreen wires the real editor.
                    onChange = { text -> writes += document.ref to text },
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performTextInput(" edited")

            // Switch notes before the debounce fires.
            current = NoteRef.Local(2)
            waitForIdle()

            assertEquals(
                listOf<Pair<NoteRef, String>>(NoteRef.Local(1) to "one edited"),
                writes,
                "the pending text must be flushed to the note it was typed into",
            )
            onNode(hasSetTextAction()).assertTextEquals(drawn("two"))
            assertTrue(writes.none { it.first == NoteRef.Local(2) }, "note two must not receive note one's text")
        }

    @Test
    fun theBulletButtonPrefixesTheLineTheCaretIsOn() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "shopping"),
                    onChange = {},
                )
            }

            // Put the caret at the start of the line, then use the toolbar.
            onNode(hasSetTextAction()).performClick()
            onNodeWithText("Bullet", substring = true).performClick()

            onNode(hasSetTextAction()).assertTextEquals(drawn(BULLET_INDENT + BULLET_MARKER + "shopping"))
        }

    @Test
    fun theBulletButtonToggles() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "shopping"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNodeWithText("Bullet", substring = true).performClick()
            onNodeWithText("Bullet", substring = true).performClick()

            onNode(hasSetTextAction()).assertTextEquals(drawn("shopping"))
        }

    @Test
    fun enterContinuesABulletInTheRealField() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "\u2022 milk"),
                    onChange = {},
                )
            }

            // Caret at the end of the line, then a real Return through the field's input path.
            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

            onNode(hasSetTextAction()).assertTextEquals(drawn("\u2022 milk\n\u2022 "))
        }

    @Test
    fun enterOnAnEmptyBulletEndsTheListInTheRealField() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "\u2022 milk\n\u2022 "),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

            onNode(hasSetTextAction()).assertTextEquals(drawn("\u2022 milk\n"))
        }

    @Test
    fun typingStarSpaceInTheRealFieldMakesABullet() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = ""),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performTextInput("*")
            onNode(hasSetTextAction()).performTextInput(" ")

            // The two characters became one bullet, so the next thing typed is a list item.
            onNode(hasSetTextAction()).assertTextEquals(drawn(BULLET_INDENT + BULLET_MARKER))
        }

    @Test
    fun typingStarSpaceThenTextThenEnterStartsTheNextBullet() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = ""),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performTextInput("*")
            onNode(hasSetTextAction()).performTextInput(" ")
            onNode(hasSetTextAction()).performTextInput("milk")
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

            onNode(hasSetTextAction()).assertTextEquals(drawn(BULLET_INDENT + BULLET_MARKER + "milk\n" + BULLET_INDENT + BULLET_MARKER))
        }

    @Test
    fun tabIndentsInTheRealFieldInsteadOfMovingFocus() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = ""),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }

            // A tab character at the caret, drawn four columns wide. (A click puts the caret at the end,
            // so an empty note is the case without ambiguity about where it lands.)
            onNode(hasSetTextAction()).assertTextEquals(drawn(INDENT_UNIT))
        }

    @Test
    fun deletingTheSpaceAfterABulletEndsTheListInTheRealField() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    // An empty bullet: the caret sits right after the marker's space.
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = INDENT_UNIT + BULLET_MARKER),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Backspace) }

            // Deleting the space removed the marker and its indent too, so the field is empty: the
            // placeholder is back, which is the visible sign of it.
            onNodeWithText("First line becomes the title\u2026").assertExists()
        }

    @Test
    fun shiftTabOutdentsInTheRealField() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = INDENT_UNIT + INDENT_UNIT + BULLET_MARKER + "milk"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                keyUp(Key.ShiftLeft)
            }

            // One of the two indents gone, whatever column the caret happened to be in.
            onNode(hasSetTextAction()).assertTextEquals(drawn(INDENT_UNIT + BULLET_MARKER + "milk"))
        }

    @Test
    fun shiftTabOnAnUnindentedLineLeavesItAlone() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "milk"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                keyUp(Key.ShiftLeft)
            }

            onNode(hasSetTextAction()).assertTextEquals(drawn("milk"))
        }

    @Test
    fun tabInTheMiddleOfALineInsertsThere() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "milk"),
                    onChange = {},
                )
            }

            // A click leaves the caret at the end of the text, which is already "not the line start".
            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Tab) }

            onNode(hasSetTextAction()).assertTextEquals(drawn("milk" + INDENT_UNIT))
        }

    @Test
    fun theDashButtonMakesADashListInTheRealField() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "shopping"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNodeWithText("Dash list", substring = true).performClick()

            onNode(hasSetTextAction()).assertTextEquals(drawn(BULLET_INDENT + DASH_MARKER + "shopping"))
        }

    @Test
    fun typingHyphenSpaceInTheRealFieldMakesADashList() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = ""),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performTextInput("-")
            onNode(hasSetTextAction()).performTextInput(" ")

            onNode(hasSetTextAction()).assertTextEquals(drawn(BULLET_INDENT + DASH_MARKER))
        }

    @Test
    fun enterInTheRealFieldContinuesADashList() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = DASH_MARKER + "milk"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

            onNode(hasSetTextAction()).assertTextEquals(drawn(DASH_MARKER + "milk\n" + DASH_MARKER))
        }

    @Test
    fun theNumberedButtonStartsANumberedListInTheRealField() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "step"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNodeWithText("Numbered list", substring = true).performClick()

            onNode(hasSetTextAction()).assertTextEquals(drawn(BULLET_INDENT + NUMBERED_MARKER + "step"))
        }

    @Test
    fun typingNumberDotSpaceInTheRealFieldMakesANumberedList() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = ""),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performTextInput("3")
            onNode(hasSetTextAction()).performTextInput(".")
            onNode(hasSetTextAction()).performTextInput(" ")

            onNode(hasSetTextAction()).assertTextEquals(drawn(BULLET_INDENT + "3. "))
        }

    @Test
    fun enterInTheRealFieldAdvancesTheNumber() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = NUMBERED_MARKER + "step"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

            onNode(hasSetTextAction()).assertTextEquals(drawn(NUMBERED_MARKER + "step\n" + "2. "))
        }

    @Test
    fun theChecklistButtonMakesAnItemInTheRealField() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "milk"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNodeWithText("Checklist", substring = true).performClick()

            // The indent is drawn as columns and the marker as a circle.
            onNode(hasSetTextAction()).assertTextEquals(drawn(BULLET_INDENT) + "○ milk")
        }

    @Test
    fun tickingInTheRealFieldTurnsTheCircleIntoTickedText() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = CHECKLIST_OPEN + "milk"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNodeWithText("Tick item", substring = true).performClick()

            // The note holds "[x] milk"; the field draws a filled circle in front of it.
            onNode(hasSetTextAction()).assertTextEquals(drawn("\u25C9 milk"))
        }

    @Test
    fun enterInTheRealFieldContinuesAChecklist() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = CHECKLIST_DONE + "milk"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

            onNode(hasSetTextAction()).assertTextEquals(drawn("\u25C9 milk\n\u25CB "))
        }

    @Test
    fun ctrlEnterTicksTheItemInTheRealField() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = CHECKLIST_OPEN + "milk"),
                    onChange = {},
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.Enter)
                keyUp(Key.CtrlLeft)
            }

            onNode(hasSetTextAction()).assertTextEquals(drawn("\u25C9 milk"))
        }
}
