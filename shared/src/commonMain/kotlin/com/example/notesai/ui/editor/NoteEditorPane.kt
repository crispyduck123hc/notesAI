package com.example.notesai.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import com.example.notesai.model.Completion
import com.example.notesai.model.NoteDocument
import com.example.notesai.ui.theme.Dimens
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

private const val WRITE_DEBOUNCE_MS = 300L

/**
 * Right pane: a single full-size text field bound to the selected note.
 *
 * The field's value is local state (so typing never waits on the DB), and writes are
 * debounced so a burst of keystrokes collapses into one DB write. The first line is
 * rendered as a title.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun NoteEditorPane(
    document: NoteDocument?,
    onChange: (String) -> Unit,
    /** Null for local notes, which have no state to toggle. */
    onToggleComplete: (() -> Unit)? = null,
    /** Null for local notes; issues can be handed to the browser. */
    onOpenOnGitHub: (() -> Unit)? = null,
) {
    if (document == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Select or create a note",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Local state is the source of truth while editing; the DB write is the sink. It carries the
    // selection too, because the bullet action has to put the caret after the marker it inserts.
    // Keyed on the document's identity so it re-initializes only when a different note is selected,
    // so the async Flow re-emission from our own write can't clobber what's typed.
    val textState = remember(document.ref) { mutableStateOf(TextFieldValue(document.content)) }

    // Debounced persistence: emit only after typing pauses for WRITE_DEBOUNCE_MS.
    LaunchedEffect(document.ref) {
        snapshotFlow { textState.value.text }
            .drop(1) // ignore the value already persisted
            .debounce(WRITE_DEBOUNCE_MS)
            .collectLatest { onChange(it) }
    }

    // Flush the pending text when switching notes or leaving the screen. `textState`
    // and `onChange` are captured from the composition where this effect launched, so
    // the flush targets the note that was actually being edited.
    DisposableEffect(document.ref) {
        onDispose { onChange(textState.value.text) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // A GitHub issue has a title of its own, which is not the first line of its body, so it gets a
        // header instead of the first-line styling below.
        val completion = document.completion
        if (completion != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Dimens.EditorPadding, end = Dimens.Gutter, top = Dimens.Gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = document.title.ifBlank { "(no title)" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (completion == Completion.Closed) "Closed" else "Open",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onOpenOnGitHub != null) {
                    Spacer(modifier = Modifier.width(Dimens.Gutter))
                    TextButton(onClick = onOpenOnGitHub) { Text("Open on GitHub") }
                }
                if (onToggleComplete != null) {
                    Spacer(modifier = Modifier.width(Dimens.Gutter))
                    TextButton(onClick = onToggleComplete) {
                        Text(if (completion == Completion.Closed) "Reopen" else "Mark complete")
                    }
                }
            }
            HorizontalDivider()
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Dimens.EditorPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { textState.value = toggleList(textState.value, ListKind.BULLET) },
            ) {
                Text("\u2022 Bullet list")
            }
            TextButton(
                onClick = { textState.value = toggleList(textState.value, ListKind.DASH) },
            ) {
                Text("${DASH_MARKER.trimEnd()} Dash list")
            }
        }

        OutlinedTextField(
            value = textState.value,
            // Typing "\* " makes a bullet, Enter continues one, Enter on an empty bullet ends the list,
            // and deleting the space after a bullet ends it too. Done as part of the value change rather
            // than as key events, so a soft keyboard on Android behaves the same as a hardware Return.
            onValueChange = { textState.value = applyEditorTyping(textState.value, it) },
            // The first line is a title only for local notes; an issue's body is just a body. Tabs are
            // drawn at a real width in both cases.
            visualTransformation = EditorTransformation(titleStyle = if (completion == null) TitleStyle else null),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = {
                Text(if (completion == null) "First line becomes the title\u2026" else "Issue body\u2026")
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(Dimens.EditorPadding)
                    // Tab indents and Shift+Tab outdents, instead of moving focus. The field would
                    // otherwise let the key travel to the focus manager, which is why an indent was
                    // impossible to type.
                    .onPreviewKeyEvent { event ->
                        val indentKey = event.key == Key.Tab && event.type == KeyEventType.KeyDown
                        if (indentKey) {
                            textState.value =
                                if (event.isShiftPressed) {
                                    outdentAtCaret(textState.value)
                                } else {
                                    indentAtCaret(textState.value)
                                }
                        }
                        indentKey
                    },
        )
    }
}
