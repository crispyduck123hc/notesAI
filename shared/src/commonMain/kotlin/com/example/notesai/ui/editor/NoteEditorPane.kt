package com.example.notesai.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.notesai.PendingEdit
import com.example.notesai.model.Completion
import com.example.notesai.model.NoteDocument
import com.example.notesai.ui.dialogs.TextPromptDialog
import com.example.notesai.ui.theme.DateRangeIcon
import com.example.notesai.ui.theme.Dimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Clock

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
    pendingEdit: PendingEdit,
    /** Receives the buffer and when the user last typed it, which becomes the note's `updatedAt`. */
    onChange: (text: String, lastTypedAtMillis: Long) -> Unit,
    /** Null for local notes, which have no state to toggle. */
    onToggleComplete: (() -> Unit)? = null,
    /** Opens the issue-list date-range calendar; non-null only while a GitHub issue is open. */
    onPickCalendarRange: (() -> Unit)? = null,
    /** Streams an AI reply for a prompt; non-null only when the AI grant exists. */
    streamAi: ((String) -> Flow<String>)? = null,
    /** Reports a failed AI invocation; the screen shows it the way it shows other notices. */
    onAiFailed: (String) -> Unit = {},
    /** Null for local notes; issues can be handed to the browser. */
    onOpenOnGitHub: (() -> Unit)? = null,
    /** Opens a link found in the note's text. Null makes links inert, which is what an issue body wants. */
    onOpenLink: ((String) -> Unit)? = null,
    /** Title of the note a reference token names, so a chip can show it. */
    onResolveNoteTitle: (String) -> String? = { null },
    /** Called when a note-reference chip is activated, with the note-uuid prefix. */
    onOpenNote: ((String) -> Unit)? = null,
    /** Called by the toolbar's link button; the screen owns the picker, because it already has the note tree. */
    onLinkNote: (() -> Unit)? = null,
    /** A token the screen wants inserted at the caret. Consumed once, then cleared through [onInsertionConsumed]. */
    pendingInsertion: String? = null,
    onInsertionConsumed: () -> Unit = {},
) {
    val completion = document?.completion

    if (document == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Select or create a note",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Local state is the source of truth while editing; the DB write is the sink. `TextFieldState` owns the text,
    // the caret and the selection, which is what lets the field's own input pipeline apply the editing rules before
    // anything is drawn. Keyed on the document's identity so it re-initializes only when a different note is selected,
    // so the async Flow re-emission from our own write can't clobber what's typed.
    val state = remember(document.ref) { TextFieldState(document.content) }

    // Ask AI. The reply streams straight into the field at the caret the user had — "direct
    // insert" — and the field is read-only while it does: two writers to one buffer fight over
    // the caret, and generation lasts seconds. Stopping keeps what arrived (it is just text);
    // a failure reports through the screen's notice dialog.
    val paneScope = rememberCoroutineScope()
    var aiJob by remember { mutableStateOf<Job?>(null) }
    var showAiPrompt by remember { mutableStateOf(false) }
    var aiMenuExpanded by remember { mutableStateOf(false) }

    /** [replaceFirstLine] is the catalog's title action: the first line *is* the local note's title. */
    fun startAi(
        prompt: String,
        replaceFirstLine: Boolean = false,
    ) {
        val stream = streamAi ?: return
        var offset =
            if (replaceFirstLine) {
                0
            } else {
                state.selection.start.coerceIn(0, state.text.length)
            }
        // The range the first chunk replaces: everything up to (and excluding) the note's first
        // line break. For an empty note that is an empty range, so insertion is the natural case.
        val firstLineEnd =
            if (replaceFirstLine) {
                state.text.indexOf('\n').let { if (it == -1) state.text.length else it }
            } else {
                -1
            }
        var replacing = replaceFirstLine
        aiJob =
            paneScope.launch {
                try {
                    stream(prompt).collect { chunk ->
                        state.edit {
                            if (replacing) {
                                replace(offset, firstLineEnd, chunk)
                                replacing = false
                            } else {
                                replace(offset, offset, chunk)
                            }
                            offset += chunk.length
                            selection = TextRange(offset)
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (
                    @Suppress("TooGenericExceptionCaught")
                    t: Throwable,
                ) {
                    // What arrived stays: it is ordinary note text now, deletable like any other.
                    onAiFailed("AI could not finish: ${t.message ?: t}. The inserted text is kept.")
                } finally {
                    aiJob = null
                }
            }
    }

    // When the buffer last changed *because the user changed it*. A save can happen much later than the keystroke --
    // the debounce waits for a pause, and disposing the editor flushes whatever is left -- and `updatedAt` has to
    // record the keystroke, not the save, because that is what the conflict tie-break compares.
    val lastTypedAt = remember(document.ref) { mutableStateOf(nowMillis()) }

    /**
     * The note as its own characters and caret. The editing rules (`toggleList`, `indentAtCaret`, the input pipeline)
     * are written in these terms, so this is the adapter between them and `TextFieldState`.
     */
    fun currentValue(): TextFieldValue = TextFieldValue(state.text.toString(), TextRange(state.selection.start, state.selection.end))

    /** Applies a rule's result: one edit, so undo and selection behave like typing does. */
    fun edit(newValue: TextFieldValue) {
        lastTypedAt.value = nowMillis()
        state.edit {
            replace(0, length, newValue.text)
            selection = TextRange(newValue.selection.start, newValue.selection.end)
        }
    }

    val inputTransformation = remember { editorInputTransformation() }
    val outputTransformation =
        remember(completion == null) { editorOutputTransformation(if (completion == null) TitleStyle else null) }
    val layout = remember(document.ref) { mutableStateOf<TextLayoutResult?>(null) }

    // Debounced persistence: emit only after typing pauses for WRITE_DEBOUNCE_MS.
    // A widget is one thing, so the caret may not sit inside it. This repairs whatever moved it there -- an arrow key,
    // a click near its edge, Home/End, an IME -- rather than reimplementing cursor movement to prevent it.
    LaunchedEffect(document.ref) {
        var previous = state.selection.start
        snapshotFlow { state.selection to state.text.toString() }
            .collect { (selection, text) ->
                if (!selection.collapsed) {
                    previous = selection.start
                    return@collect
                }
                val snapped = snapCaretOutOfWidgets(text, from = previous, to = selection.start)
                previous = snapped
                if (snapped != selection.start) {
                    state.edit { this.selection = TextRange(snapped) }
                }
            }
    }

    // A widget token chosen in the screen's picker is inserted where the caret is, as one edit, and then consumed.
    LaunchedEffect(pendingInsertion) {
        val token = pendingInsertion ?: return@LaunchedEffect
        val value = currentValue()
        val at = value.selection.min.coerceIn(0, value.text.length)
        edit(
            value.copy(
                text = value.text.substring(0, at) + token + value.text.substring(at),
                selection = TextRange(at + token.length),
            ),
        )
        onInsertionConsumed()
    }

    LaunchedEffect(document.ref) {
        snapshotFlow { state.text.toString() }
            .drop(1) // ignore the value already persisted
            // Stamped before the debounce: the note's `updatedAt` records the keystroke, not the save.
            .onEach { lastTypedAt.value = nowMillis() }
            .debounce(WRITE_DEBOUNCE_MS)
            .collectLatest { onChange(it, lastTypedAt.value) }
    }

    // Flush the pending text when switching notes or leaving the screen. `textState`
    // and `onChange` are captured from the composition where this effect launched, so
    // the flush targets the note that was actually being edited.
    //
    // The same write is offered to the exit path, which cannot wait for the debounce and cannot wait for this
    // composition to be disposed either: quitting runs it synchronously, which is one local transaction and no
    // network call.
    DisposableEffect(document.ref) {
        pendingEdit.offer { onChange(state.text.toString(), lastTypedAt.value) }
        onDispose {
            pendingEdit.withdraw()
            onChange(state.text.toString(), lastTypedAt.value)
        }
    }

    if (showAiPrompt) {
        TextPromptDialog(
            title = "Ask AI",
            fieldLabel = "What should the AI do with this note?",
            confirmLabel = "Generate",
            onDismiss = { showAiPrompt = false },
            onConfirm = { request ->
                showAiPrompt = false
                startAi(askAiPrompt(noteText = state.text.toString(), request = request))
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // A GitHub issue has a title of its own, which is not the first line of its body, so it gets a
        // header instead of the first-line styling below.
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
                // The issue-list date filter, one tap away from the list it filters. Lives at the row's
                // end — to the right of Mark complete/Reopen — and only for GitHub issues, because a
                // local note has no date filter to open.
                if (onPickCalendarRange != null) {
                    Spacer(modifier = Modifier.width(Dimens.Gutter))
                    TextButton(onClick = onPickCalendarRange) {
                        Icon(DateRangeIcon, contentDescription = "Filter issues by opened date")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Dates")
                    }
                }
            }
            HorizontalDivider()
        }

        // Wraps rather than clipping: there are five list buttons now, and the pane is 280dp wide on a phone.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = Dimens.EditorPadding),
            verticalArrangement = Arrangement.Center,
        ) {
            TextButton(
                onClick = { edit(toggleList(currentValue(), ListKind.BULLET)) },
            ) {
                Text("\u2022 Bullet list")
            }
            TextButton(
                onClick = { edit(toggleList(currentValue(), ListKind.DASH)) },
            ) {
                Text("${DASH_MARKER.trimEnd()} Dash list")
            }
            TextButton(
                onClick = { edit(toggleList(currentValue(), ListKind.NUMBERED)) },
            ) {
                Text("${NUMBERED_MARKER.trimEnd()} Numbered list")
            }
            TextButton(
                onClick = { edit(toggleList(currentValue(), ListKind.CHECKLIST)) },
            ) {
                Text("\u25CB Checklist")
            }
            TextButton(
                // Apple ticks by tapping the circle; a plain text field cannot tell which character was tapped, so
                // the tick is an action on the caret's line — here and on Ctrl+Enter below.
                onClick = { edit(toggleChecklistState(currentValue())) },
            ) {
                Text("\u2713 Tick item")
            }
            if (onLinkNote != null) {
                TextButton(onClick = onLinkNote) { Text("\u21aa Link note") }
            }
            // The note's text and the request are what leave the device, only when the user asks —
            // that is the feature's whole privacy statement, stated in the prompt builders. While
            // it streams, the button is the Stop: the stream writes into the field, so the user
            // must not be writing into the stream.
            if (streamAi != null) {
                Box {
                    TextButton(
                        onClick = {
                            val running = aiJob
                            if (running != null) {
                                running.cancel()
                            } else {
                                aiMenuExpanded = true
                            }
                        },
                    ) {
                        Text(if (aiJob != null) "\u25a0 Stop AI" else "Ask AI\u2026")
                    }
                    DropdownMenu(expanded = aiMenuExpanded, onDismissRequest = { aiMenuExpanded = false }) {
                        AiAction.entries.forEach { action ->
                            DropdownMenuItem(
                                text = { Text(action.label) },
                                onClick = {
                                    aiMenuExpanded = false
                                    startAi(
                                        aiActionPrompt(action, state.text.toString()),
                                        replaceFirstLine = action == AiAction.TITLE,
                                    )
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Ask anything\u2026") },
                            onClick = {
                                aiMenuExpanded = false
                                showAiPrompt = true
                            },
                        )
                    }
                }
            }
        }

        // A `BasicTextField` rather than a Material outlined field, so the text is drawn at this Box's own origin.
        // That is what lets the overlay be its sibling in the same coordinate space: no padding has to be guessed,
        // which is the difference between a tap target that is on the circle and one that is near it.
        val scrollState = rememberScrollState()

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(Dimens.EditorPadding),
        ) {
            if (state.text.isEmpty()) {
                Text(
                    text = if (completion == null) "First line becomes the title\u2026" else "Issue body\u2026",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BasicTextField(
                state = state,
                // While the AI stream is writing into the buffer, user input is paused: two writers
                // to one field fight over the caret, and the field paints what is arriving anyway.
                readOnly = aiJob != null,
                // Typing "\* " makes a bullet, Enter continues one, Enter on an empty bullet ends the list,
                // and deleting the space after a bullet ends it too. Done in the input pipeline rather than as
                // key events, so a soft keyboard on Android behaves the same as a hardware Return.
                inputTransformation = inputTransformation,
                // The first line is a title only for local notes; an issue's body is just a body. Tabs are
                // drawn at a real width in both cases.
                outputTransformation = outputTransformation,
                // The layout the overlay positions itself from.
                onTextLayout = { getResult -> layout.value = getResult() },
                lineLimits = TextFieldLineLimits.MultiLine(),
                // `BasicTextField` paints with the style it is given, and `bodyLarge` carries no colour. Material's
                // outlined field used to substitute `TextFieldColors.textColor`; without that the text fell back to
                // the platform default (black), so the note was unreadable in dark mode. The caret had the same
                // problem -- its default brush is solid black -- and is invisible against a dark background.
                textStyle = editorTextStyle(MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme),
                cursorBrush = editorCursorBrush(MaterialTheme.colorScheme),
                scrollState = scrollState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .editorLayoutSemantics(layout, state)
                        // Tab indents and Shift+Tab outdents, instead of moving focus. The field would
                        // otherwise let the key travel to the focus manager, which is why an indent was
                        // impossible to type.
                        .onPreviewKeyEvent { event ->
                            val indentKey = event.key == Key.Tab && event.type == KeyEventType.KeyDown
                            if (indentKey) {
                                edit(
                                    if (event.isShiftPressed) {
                                        outdentAtCaret(currentValue())
                                    } else {
                                        indentAtCaret(currentValue())
                                    },
                                )
                                return@onPreviewKeyEvent true
                            }
                            // Ctrl+Enter ticks the current checklist item, the way Markdown All in One binds Alt+C.
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed) {
                                edit(toggleChecklistState(currentValue()))
                                return@onPreviewKeyEvent true
                            }
                            false
                        },
            )

            // Nothing is drawn in it yet: phase 2 is the geometry, and the elements it composes are the boxes phase 3
            // fills with a real control.
            EditorOverlay(
                text = state.text.toString(),
                layout = layout.value,
                scrollOffsetPx = scrollState.value.toFloat(),
                // A tap on a circle edits the note's text: the same operation the toolbar button and Ctrl+Enter perform,
                // addressed by position instead of by the caret's line. The text stays the document.
                onDecorationClick = { decoration -> edit(toggleChecklistAt(currentValue(), decoration.start)) },
                onLinkClick = onOpenLink,
                onResolveNoteTitle = onResolveNoteTitle,
                onOpenNote = onOpenNote,
            )
        }
    }
}

/** How many lines the field actually laid out. Phase 1's proof that `onTextLayout` reaches the app. */
internal val EditorLineCountKey = SemanticsPropertyKey<Int>("EditorLineCount")

/** Which laid-out line the caret is on, so a test can catch a caret that lands on a phantom line. */
internal val EditorCaretLineKey = SemanticsPropertyKey<Int>("EditorCaretLine")

/** The caret's offset in the note, so a test can assert where it is allowed to rest. */
internal val EditorCaretOffsetKey = SemanticsPropertyKey<Int>("EditorCaretOffset")

private fun Modifier.editorLayoutSemantics(
    layout: androidx.compose.runtime.MutableState<TextLayoutResult?>,
    state: TextFieldState,
): Modifier {
    val lines = layout.value?.lineCount
    val caretLine = layout.value?.getLineForOffset(state.selection.start.coerceIn(0, state.text.length))
    return semantics {
        if (lines != null) this[EditorLineCountKey] = lines
        if (caretLine != null) this[EditorCaretLineKey] = caretLine
        this[EditorCaretOffsetKey] = state.selection.start
    }
}

/** Wall clock, kept file-local so the composable reads like the rest of the code. */
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
