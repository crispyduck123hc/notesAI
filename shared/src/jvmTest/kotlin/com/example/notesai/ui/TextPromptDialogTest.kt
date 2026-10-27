package com.example.notesai.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The dialogs are keyboard-first: Enter confirms, Escape dismisses, and the field already has focus.
 * "New folder" is two keystrokes if that works and four if it doesn't.
 */
@OptIn(ExperimentalTestApi::class)
class TextPromptDialogTest {
    @Test
    fun enterConfirms() =
        runComposeUiTest {
            var confirmed: String? = null
            setContent {
                TextPromptDialog(
                    title = "New folder",
                    fieldLabel = "Name",
                    confirmLabel = "Create",
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                )
            }

            // No click first: the field takes focus when the dialog opens.
            onNode(hasSetTextAction()).performTextInput("Work")
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

            assertEquals("Work", confirmed)
        }

    @Test
    fun theImeActionConfirmsToo() =
        runComposeUiTest {
            var confirmed: String? = null
            setContent {
                TextPromptDialog(
                    title = "New folder",
                    fieldLabel = "Name",
                    confirmLabel = "Create",
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                )
            }

            onNode(hasSetTextAction()).performTextInput("Ideas")
            onNode(hasSetTextAction()).performImeAction()

            assertEquals("Ideas", confirmed)
        }

    @Test
    fun escapeDismissesWithoutConfirming() =
        runComposeUiTest {
            var confirmed: String? = null
            var dismissed = false
            setContent {
                TextPromptDialog(
                    title = "New folder",
                    fieldLabel = "Name",
                    confirmLabel = "Create",
                    onDismiss = { dismissed = true },
                    onConfirm = { confirmed = it },
                )
            }

            onNode(hasSetTextAction()).performTextInput("Work")
            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }

            assertEquals(true, dismissed)
            assertNull(confirmed, "Escape must not create anything")
        }

    @Test
    fun anEmptyNameCannotBeConfirmed() =
        runComposeUiTest {
            var confirmed: String? = null
            setContent {
                TextPromptDialog(
                    title = "New folder",
                    fieldLabel = "Name",
                    confirmLabel = "Create",
                    onDismiss = {},
                    onConfirm = { confirmed = it },
                )
            }

            onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }

            assertNull(confirmed, "a blank name is not a folder")
        }
}
