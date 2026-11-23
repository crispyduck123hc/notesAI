package com.example.notesai.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3: the circles are clickable, and clicking them is an edit of the text.
 *
 * The assertions are deliberately about the *outcome* -- the note's characters -- not about the overlay: the overlay is
 * only a hit target, and a tap that produces the right text is the whole feature.
 *
 * `mainClock.autoAdvance = false` throughout: a focused field schedules cursor-blink frames forever, so waiting for idle
 * hangs. Frames are advanced a bounded number of times instead.
 */
@OptIn(ExperimentalTestApi::class)
class EditorChecklistTapTest {
    private companion object {
        const val FRAMES_TO_SETTLE = 30
    }

    /** The editor as the pane builds it: a field, its drawing, and the overlay over the same coordinate space. */
    private class Feature(
        note: String,
    ) {
        val state = TextFieldState(note)
        val layout = mutableStateOf<TextLayoutResult?>(null)
        val toggles = mutableListOf<Int>()
    }

    @Test
    fun tappingACircleTicksThatItem() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val feature = Feature("[ ] milk\n[ ] eggs")
            setContent {
                Box(modifier = Modifier.size(width = 600.dp, height = 300.dp)) {
                    BasicTextField(
                        state = feature.state,
                        outputTransformation = editorOutputTransformation(),
                        onTextLayout = { getResult -> feature.layout.value = getResult() },
                    )
                    EditorOverlay(
                        text = feature.state.text.toString(),
                        layout = feature.layout.value,
                        onDecorationClick = { decoration ->
                            feature.toggles += decoration.start
                            feature.state.edit {
                                val updated =
                                    toggleChecklistAt(
                                        androidx.compose.ui.text.input.TextFieldValue(
                                            text = feature.state.text.toString(),
                                            selection =
                                                androidx.compose.ui.text
                                                    .TextRange(0),
                                        ),
                                        decoration.start,
                                    )
                                replace(0, length, updated.text)
                            }
                        },
                    )
                }
            }
            repeat(FRAMES_TO_SETTLE) { mainClock.advanceTimeByFrame() }

            // The second circle, so a tap that always hits the first item would fail.
            onNodeWithTag(DECORATION_TAG_PREFIX + 1).performClick()

            assertEquals(1, feature.toggles.size, "exactly one toggle")
            assertEquals("[ ] milk\n".length, feature.toggles.single(), "the second marker, not the first")
            assertEquals("[ ] milk\n[x] eggs", feature.state.text.toString(), "only the tapped item changed")
        }

    @Test
    fun theCircleIsACheckboxToAssistiveTechnology() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val feature = Feature("[x] milk")
            setContent {
                Box(modifier = Modifier.size(width = 600.dp, height = 300.dp)) {
                    BasicTextField(
                        state = feature.state,
                        outputTransformation = editorOutputTransformation(),
                        onTextLayout = { getResult -> feature.layout.value = getResult() },
                    )
                    EditorOverlay(
                        text = feature.state.text.toString(),
                        layout = feature.layout.value,
                        onDecorationClick = { feature.toggles += it.start },
                    )
                }
            }
            repeat(FRAMES_TO_SETTLE) { mainClock.advanceTimeByFrame() }

            val node = onNodeWithTag(DECORATION_TAG_PREFIX + 0).fetchSemanticsNode()
            assertEquals(Role.Checkbox, node.config.getOrNull(SemanticsProperties.Role), "announced as a checkbox")
            assertEquals(
                androidx.compose.ui.state.ToggleableState.On,
                node.config.getOrNull(SemanticsProperties.ToggleableState),
                "a ticked item reads as checked",
            )
            onNodeWithTag(DECORATION_TAG_PREFIX + 0).assert(
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick),
            )
        }

    @Test
    fun aTapInTheTextIsNotAToggle() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val feature = Feature("[ ] milk")
            setContent {
                Box(modifier = Modifier.size(width = 600.dp, height = 300.dp)) {
                    BasicTextField(
                        state = feature.state,
                        outputTransformation = editorOutputTransformation(),
                        onTextLayout = { getResult -> feature.layout.value = getResult() },
                    )
                    EditorOverlay(
                        text = feature.state.text.toString(),
                        layout = feature.layout.value,
                        onDecorationClick = { feature.toggles += it.start },
                    )
                }
            }
            repeat(FRAMES_TO_SETTLE) { mainClock.advanceTimeByFrame() }

            // The field takes a tap in the middle of the line: no overlay element covers that point.
            onNode(
                androidx.compose.ui.test
                    .hasSetTextAction(),
            ).performTouchInput { click(centerRight) }

            assertTrue(feature.toggles.isEmpty(), "a click in the text must not toggle anything: ${feature.toggles}")
            assertEquals("[ ] milk", feature.state.text.toString())
        }

    @Test
    fun tappingALinkOpensItAndLeavesTheTextAlone() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val feature = Feature("read https://example.com. now")
            val opened = mutableListOf<String>()
            setContent {
                Box(modifier = Modifier.size(width = 600.dp, height = 300.dp)) {
                    BasicTextField(
                        state = feature.state,
                        outputTransformation = editorOutputTransformation(),
                        onTextLayout = { getResult -> feature.layout.value = getResult() },
                    )
                    EditorOverlay(
                        text = feature.state.text.toString(),
                        layout = feature.layout.value,
                        onLinkClick = { opened += it },
                    )
                }
            }
            repeat(FRAMES_TO_SETTLE) { mainClock.advanceTimeByFrame() }

            onNodeWithTag(DECORATION_TAG_PREFIX + 0).performClick()

            // The sentence's full stop is not part of the URL; opening "example.com." would fail.
            assertEquals(listOf("https://example.com"), opened)
            assertEquals("read https://example.com. now", feature.state.text.toString(), "a link click is not an edit")
        }

    @Test
    fun aLinkIsInertWhenItsCallerHasNoWayToOpenIt() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val feature = Feature("read https://example.com now")
            setContent {
                Box(modifier = Modifier.size(width = 600.dp, height = 300.dp)) {
                    BasicTextField(
                        state = feature.state,
                        outputTransformation = editorOutputTransformation(),
                        onTextLayout = { getResult -> feature.layout.value = getResult() },
                    )
                    // What the GitHub issue pane passes: the browser would leave the app for a page the note does not own.
                    EditorOverlay(
                        text = feature.state.text.toString(),
                        layout = feature.layout.value,
                        onLinkClick = null,
                    )
                }
            }
            repeat(FRAMES_TO_SETTLE) { mainClock.advanceTimeByFrame() }

            val node = onNodeWithTag(DECORATION_TAG_PREFIX + 0).fetchSemanticsNode()
            assertTrue(
                node.config.getOrNull(SemanticsActions.OnClick) == null,
                "an inert link must not advertise a click action",
            )
        }

    @Test
    fun aNoteReferenceIsAChipThatOpensTheNoteItNames() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val feature = Feature("see !n:1a2b3c4d for details")
            val opened = mutableListOf<String>()
            setContent {
                Box(modifier = Modifier.size(width = 600.dp, height = 300.dp)) {
                    BasicTextField(
                        state = feature.state,
                        outputTransformation = editorOutputTransformation(),
                        onTextLayout = { getResult -> feature.layout.value = getResult() },
                    )
                    EditorOverlay(
                        text = feature.state.text.toString(),
                        layout = feature.layout.value,
                        onResolveNoteTitle = { id -> "Meeting notes ($id)" },
                        onOpenNote = { opened += it },
                    )
                }
            }
            repeat(FRAMES_TO_SETTLE) { mainClock.advanceTimeByFrame() }

            // The chip is one element, sized to the token it covers, and announced as a button.
            val node = onNodeWithTag(DECORATION_TAG_PREFIX + 0)
            node.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            node.performClick()

            assertEquals(listOf("1a2b3c4d"), opened, "the chip opens the note its token names")
            assertEquals("see !n:1a2b3c4d for details", feature.state.text.toString(), "a chip click is not an edit")
        }

    @Test
    fun aReferenceChipCoversItsTokenAndSaysWhenTheNoteIsGone() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val feature = Feature("gone: !n:ffffffff")
            setContent {
                Box(modifier = Modifier.size(width = 600.dp, height = 300.dp)) {
                    BasicTextField(
                        state = feature.state,
                        outputTransformation = editorOutputTransformation(),
                        onTextLayout = { getResult -> feature.layout.value = getResult() },
                    )
                    // No resolver: the chip has nothing to show, and must say so rather than render an empty box.
                    EditorOverlay(
                        text = feature.state.text.toString(),
                        layout = feature.layout.value,
                    )
                }
            }
            repeat(FRAMES_TO_SETTLE) { mainClock.advanceTimeByFrame() }

            onNodeWithText("missing note").assertExists()
        }

    @Test
    fun aCompletedItemShowsATickInsideItsCircle() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val feature = Feature("[x] done\n[ ] todo")
            setContent {
                Box(modifier = Modifier.size(width = 600.dp, height = 300.dp)) {
                    BasicTextField(
                        state = feature.state,
                        outputTransformation = editorOutputTransformation(),
                        onTextLayout = { getResult -> feature.layout.value = getResult() },
                    )
                    EditorOverlay(
                        text = feature.state.text.toString(),
                        layout = feature.layout.value,
                        onDecorationClick = { decoration ->
                            feature.state.edit {
                                val updated =
                                    toggleChecklistAt(
                                        androidx.compose.ui.text.input.TextFieldValue(
                                            text = feature.state.text.toString(),
                                            selection =
                                                androidx.compose.ui.text
                                                    .TextRange(0),
                                        ),
                                        decoration.start,
                                    )
                                replace(0, length, updated.text)
                            }
                        },
                    )
                }
            }
            repeat(FRAMES_TO_SETTLE) { mainClock.advanceTimeByFrame() }

            // Exactly one tick, on the completed item.
            // Unmerged on purpose: the tick is marked invisible to accessibility, so it is not folded into the
            // checkbox's merged node -- but it is still in the tree, which is how a test can see it.
            val ticks = onAllNodesWithText(EditorDecoration.TICK, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(1, ticks.size, "one tick for one completed item")

            // Tick the second item: now there are two.
            onNodeWithTag(DECORATION_TAG_PREFIX + 1).performClick()
            repeat(FRAMES_TO_SETTLE) { mainClock.advanceTimeByFrame() }
            val after = onAllNodesWithText(EditorDecoration.TICK, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(2, after.size, "the newly completed item grows a tick")
            assertEquals("[x] done\n[x] todo", feature.state.text.toString())
        }

    @Test
    fun theTickSurvivesEveryFrameOfAnEdit() =
        runComposeUiTest {
            // The flicker: on each keystroke the note's text changes one frame before the field reports a new layout, and
            // the overlay used to clear itself for that frame. Now it keeps the last good placement, so the tick is
            // present on every frame -- checked by advancing one frame at a time and looking after each one.
            mainClock.autoAdvance = false
            val feature = Feature("[x] done")
            setContent {
                Box(modifier = Modifier.size(width = 600.dp, height = 300.dp)) {
                    BasicTextField(
                        state = feature.state,
                        outputTransformation = editorOutputTransformation(),
                        onTextLayout = { getResult -> feature.layout.value = getResult() },
                    )
                    EditorOverlay(text = feature.state.text.toString(), layout = feature.layout.value)
                }
            }
            repeat(FRAMES_TO_SETTLE) { mainClock.advanceTimeByFrame() }
            assertEquals(1, tickCount(), "the tick starts out there")

            // Type, then look on every frame: never absent, never doubled.
            feature.state.edit { append("!") }
            repeat(6) {
                mainClock.advanceTimeByFrame()
                assertEquals(1, tickCount(), "the tick must not flicker while the text changes")
            }
        }

    private fun ComposeUiTest.tickCount(): Int = onAllNodesWithText(EditorDecoration.TICK, useUnmergedTree = true).fetchSemanticsNodes().size
}
