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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
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
}
