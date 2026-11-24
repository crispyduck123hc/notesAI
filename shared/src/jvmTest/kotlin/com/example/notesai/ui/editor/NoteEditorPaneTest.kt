package com.example.notesai.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import com.example.notesai.PendingEdit
import com.example.notesai.model.NoteDocument
import com.example.notesai.model.NoteRef
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "first line", content = "first line\nbody"),
                    onChange = { _, _ -> },
                )
            }

            onNode(hasSetTextAction()).assertTextEquals(drawn("first line\nbody"))
        }

    @Test
    fun noDocumentShowsThePlaceholder() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(pendingEdit = PendingEdit(), document = null, onChange = { _, _ -> })
            }

            onNodeWithText("Select or create a note").assertExists()
        }

    @Test
    fun aBurstOfKeystrokesBecomesOneWrite() =
        runComposeUiTest {
            val writes = mutableListOf<String>()
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "", content = ""),
                    onChange = { text, _ -> writes += text },
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
                    pendingEdit = PendingEdit(),
                    document = document,
                    // Closes over `document` from the composition that created it, which is exactly
                    // how NotesScreen wires the real editor.
                    onChange = { text, _ -> writes += document.ref to text },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "shopping"),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "shopping"),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "\u2022 milk"),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "\u2022 milk\n\u2022 "),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = ""),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = ""),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = ""),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    // An empty bullet: the caret sits right after the marker's space.
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = INDENT_UNIT + BULLET_MARKER),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = INDENT_UNIT + INDENT_UNIT + BULLET_MARKER + "milk"),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "milk"),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "milk"),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "shopping"),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = ""),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = DASH_MARKER + "milk"),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "step"),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = ""),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = NUMBERED_MARKER + "step"),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = "milk"),
                    onChange = { _, _ -> },
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
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = CHECKLIST_OPEN + "milk"),
                    onChange = { _, _ -> },
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNodeWithText("Tick item", substring = true).performClick()

            // The note holds "[x] milk"; the field draws a filled circle in front of it.
            onNode(hasSetTextAction()).assertTextEquals(drawn("\u25CB milk"))
        }

    @Test
    fun enterInTheRealFieldContinuesAChecklist() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = CHECKLIST_DONE + "milk"),
                    onChange = { _, _ -> },
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

            onNode(hasSetTextAction()).assertTextEquals(drawn("\u25CB milk\n\u25CB "))
        }

    @Test
    fun ctrlEnterTicksTheItemInTheRealField() =
        runComposeUiTest {
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "t", content = CHECKLIST_OPEN + "milk"),
                    onChange = { _, _ -> },
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(Key.Enter)
                keyUp(Key.CtrlLeft)
            }

            onNode(hasSetTextAction()).assertTextEquals(drawn("\u25CB milk"))
        }

    @Test
    fun theUnsavedTextIsOfferedToWhoeverEndsTheProcess() =
        runComposeUiTest {
            // Decisive on purpose: nothing is typed, so the debounce never writes, and the only way `writes` can
            // hold anything is that the editor offered its buffer and the exit path ran it.
            val writes = mutableListOf<String>()
            val pendingEdit = PendingEdit()

            setContent {
                NoteEditorPane(
                    pendingEdit = pendingEdit,
                    document = NoteDocument(ref = NoteRef.Local(1), title = "first", content = "first\nbody"),
                    onChange = { text, _ -> writes += text },
                )
            }

            pendingEdit.commit()

            assertEquals(listOf("first\nbody"), writes, "quitting should write the open buffer synchronously")
        }

    @Test
    fun aPaneThatIsGoneIsNotWrittenIntoAtAQuit() =
        runComposeUiTest {
            val writes = mutableListOf<String>()
            val pendingEdit = PendingEdit()
            var showing by mutableStateOf(true)

            setContent {
                if (showing) {
                    NoteEditorPane(
                        pendingEdit = pendingEdit,
                        document = NoteDocument(ref = NoteRef.Local(1), title = "first", content = "first"),
                        onChange = { text, _ -> writes += text },
                    )
                }
            }

            // Disposing the pane flushes it once (the existing behaviour) and withdraws it, so a later commit adds
            // nothing: the repository may already be closed by then.
            showing = false
            waitForIdle()
            val afterDispose = writes.size
            pendingEdit.commit()

            assertEquals(afterDispose, writes.size, "a disposed editor must not write again: $writes")
        }

    @Test
    fun theFlushReportsWhenTheUserTypedNotWhenTheFlushRan() =
        runComposeUiTest {
            // `updatedAt` is what the conflict tie-break compares, so it has to be the keystroke's time. A dispose
            // flush can happen long after the last keystroke -- that is precisely how a stale buffer came to outrank
            // newer text and manufacture copies.
            val stamps = mutableListOf<Long>()
            var showing by mutableStateOf(true)

            setContent {
                if (showing) {
                    NoteEditorPane(
                        pendingEdit = PendingEdit(),
                        document = NoteDocument(ref = NoteRef.Local(1), title = "first", content = "first"),
                        onChange = { _, at -> stamps += at },
                    )
                }
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performTextInput(" typed")
            waitUntil(timeoutMillis = 2_000) { stamps.isNotEmpty() }
            val typedAt = stamps.last()

            // A long pause before the editor goes away: seconds of virtual-ish lag, well beyond the debounce.
            Thread.sleep(150)
            showing = false
            waitForIdle()

            assertEquals(typedAt, stamps.last(), "the flush must carry the typing time, not the flush time")
        }

    @Test
    fun theFieldReportsTheLayoutTheOverlayWillUse() =
        runComposeUiTest {
            // Phase 1's deliverable: `onTextLayout` reaches the app, so phase 2 can position an overlay from the
            // field's own layout instead of measuring text a second time. Asserted through the field's semantics.
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "a", content = "a\nb"),
                    onChange = { _, _ -> },
                )
            }

            onNode(hasSetTextAction()).performClick()
            onNode(hasSetTextAction()).performTextInput("\nc")

            waitUntil(timeoutMillis = 5_000) { layoutLines() == 3 }
            assertEquals(3, layoutLines(), "three note lines, three laid-out lines: no phantom lines")
            assertEquals(2, caretLine(), "the caret sits on the line its offset belongs to")
        }

    private fun ComposeUiTest.layoutLines(): Int? = onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(EditorLineCountKey)

    private fun ComposeUiTest.caretLine(): Int? = onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(EditorCaretLineKey)

    @Test
    fun tappingACircleInTheRealPaneEditsTheNote() =
        runComposeUiTest {
            // The whole path: the pane's field, its drawing, the overlay's hit target, the edit, the debounced save.
            // `waitUntil` polls with a timeout; `waitForIdle` would hang because a focused field never stops scheduling
            // cursor-blink frames.
            val saved = mutableListOf<String>()
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "[ ] milk", content = "[ ] milk\n[ ] eggs"),
                    onChange = { text, _ -> saved += text },
                )
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag(DECORATION_TAG_PREFIX + 1).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag(DECORATION_TAG_PREFIX + 1).performClick()
            waitUntil(timeoutMillis = 5_000) { saved.isNotEmpty() }

            assertEquals(
                "[ ] milk\n[x] eggs",
                saved.lastOrNull(),
                "the tapped item is ticked and the note is saved: $saved",
            )
        }

    @Test
    fun theCaretCannotRestInsideAWidgetToken() =
        runComposeUiTest {
            // Full atomicity: the deletion rule removes a token whole, and this is the other half -- wherever the caret
            // ends up, it is pushed out of the token's interior, so a widget behaves as one thing.
            val token = "!n:1a2b3c4d"
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document =
                        NoteDocument(
                            ref = NoteRef.Local(1),
                            title = "see $token now",
                            content = "see $token now",
                        ),
                    onChange = { _, _ -> },
                )
            }

            val field = onNode(hasSetTextAction())
            field.performClick()
            waitUntil(timeoutMillis = 2_000) { caretOffset() != null }

            // Walk the caret left across the whole line, one character at a time, recording every place it rests.
            val seen = mutableListOf<Int>()
            repeat(20) {
                seen += caretOffset() ?: 0
                field.performKeyInput { pressKey(Key.DirectionLeft) }
            }
            seen += caretOffset() ?: 0

            // The token occupies 4 until 14; positions 5..14 are its interior.
            assertTrue(
                seen.none { it in 5..14 },
                "the caret must never rest inside the token, saw $seen",
            )
            assertTrue(seen.contains(14) || seen.contains(4), "it does visit the token's edges, saw $seen")
        }

    /** The caret position, through the same semantics seam the layout test uses. */
    private fun ComposeUiTest.caretOffset(): Int? = onNode(hasSetTextAction()).fetchSemanticsNode().config.getOrNull(EditorCaretOffsetKey)

    @Test
    fun anAiReplyStreamsIntoTheFieldAsOneCommittedEdit() =
        runComposeUiTest {
            var committed: String? = null
            var promptSeen: String? = null
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "the body", content = "the body"),
                    onChange = { text, _ -> committed = text },
                    streamAi = { prompt ->
                        promptSeen = prompt
                        flow {
                            for (piece in listOf("AI ", "says hi")) emit(piece)
                        }
                    },
                )
            }

            onNodeWithText("Ask AI\u2026").performClick()
            onNode(hasSetTextAction() and hasText("What should the AI do", substring = true)).performTextInput("say something nice")
            onNodeWithText("Generate").performClick()

            // The stream writes into the field at the caret (offset 0 here); the debounced write
            // commits once, after the stream settles.
            waitUntil(timeoutMillis = 5_000) { committed == "the bodyAI says hi" }
            assertEquals(
                askAiPrompt(noteText = "the body", request = "say something nice"),
                promptSeen,
                "the note's text and the request are what travel",
            )
        }

    @Test
    fun stoppingTheAiKeepsWhatArrived() =
        runComposeUiTest {
            var committed: String? = null
            setContent {
                NoteEditorPane(
                    pendingEdit = PendingEdit(),
                    document = NoteDocument(ref = NoteRef.Local(1), title = "the body", content = "start"),
                    onChange = { text, _ -> committed = text },
                    streamAi = { _ ->
                        flow {
                            emit("one ")
                            // Never completes: the Stop button is what ends the stream.
                            awaitCancellation()
                        }
                    },
                )
            }

            onNodeWithText("Ask AI\u2026").performClick()
            onNode(hasSetTextAction() and hasText("What should the AI do", substring = true)).performTextInput("say one")
            onNodeWithText("Generate").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("\u25a0 Stop AI").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("\u25a0 Stop AI").performClick()

            // The partial insertion stays: it is ordinary note text now, deletable like any other,
            // and the button returns so another request can be made.
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Ask AI\u2026").fetchSemanticsNodes().isNotEmpty() }
            waitUntil(timeoutMillis = 5_000) { committed == "startone " }
        }
}
