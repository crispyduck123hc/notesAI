package com.example.notesai.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp

/** The box an overlay element occupies, exposed so tests can assert placement instead of eyeballing pixels. */
internal val EditorDecorationBoundsKey = SemanticsPropertyKey<Rect>("EditorDecorationBounds")

/** Test tag prefix for the overlay's elements: `editor-decoration-0`, `editor-decoration-1`, ... */
internal const val DECORATION_TAG_PREFIX = "editor-decoration-"

/**
 * The overlay surface: one interactive element per visible decoration, positioned from the field's own layout.
 *
 * The interesting property is what these elements are *not* allowed to do. An overlay sits above the text, so a naive
 * implementation swallows typing: the caret stops moving, dragging stops selecting, the wheel stops scrolling. Here each
 * element is exactly the size of one decoration, so pointer input outside those boxes never reaches the overlay at all;
 * inside one, [toggleable] consumes the gesture, which is right -- tapping a circle must not also move the caret.
 *
 * [toggleable] with `Role.Checkbox` is what makes this a real control rather than a drawn shape: assistive technology
 * gets a checkbox with its checked state and can activate it, and the activation runs the same code a mouse click does.
 *
 * The two preconditions that make the numbers exact:
 *
 * - [layout] is the field's *own* layout result, never a second measurement of the same text;
 * - this composable shares the field's coordinate space. The editor arranges that: a `BasicTextField` draws its text at
 *   its own origin, so an overlay that is its sibling at the same top-left is in the same space, and no padding has to
 *   be guessed.
 *
 * [scrollOffsetPx] is the field's scroll position, subtracted so the elements follow the text when a long note scrolls.
 */
@Composable
internal fun EditorOverlay(
    text: String,
    layout: TextLayoutResult?,
    modifier: Modifier = Modifier,
    scrollOffsetPx: Float = 0f,
    /** Called when a checklist decoration is activated, by pointer or by assistive technology. */
    onDecorationClick: (EditorDecoration) -> Unit = {},
    /**
     * Called when a link is activated, with the URL. Null makes links inert -- what the GitHub issue pane wants, because
     * a link there would leave the app for a page the note does not own.
     */
    onLinkClick: ((String) -> Unit)? = null,
) {
    if (layout == null) return
    val placed = remember(text, layout) { placeDecorations(text, layout) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        val viewport =
            with(density) {
                Rect(
                    left = 0f,
                    top = scrollOffsetPx,
                    right = maxWidth.toPx(),
                    bottom = scrollOffsetPx + maxHeight.toPx(),
                )
            }
        // One line of margin, so an item scrolled half into view is composed before the cursor reaches it.
        val margin = layout.getLineBottom(0) - layout.getLineTop(0)
        val visible = remember(placed, viewport, margin) { placed.visibleIn(viewport, marginPx = margin) }

        visible.forEachIndexed { index, placed ->
            DecorationControl(
                decoration = placed.decoration,
                modifier =
                    Modifier
                        .offset(
                            x = with(density) { placed.bounds.left.toDp() },
                            y = with(density) { (placed.bounds.top - scrollOffsetPx).toDp() },
                        ).size(
                            width = with(density) { placed.bounds.width.toDp() },
                            height = with(density) { placed.bounds.height.toDp() },
                        ).testTag(DECORATION_TAG_PREFIX + index)
                        .semantics { this[EditorDecorationBoundsKey] = placed.bounds },
                onToggle = { onDecorationClick(placed.decoration) },
                onOpenLink = onLinkClick,
            )
        }
    }
}

/**
 * One decoration as a control.
 *
 * Both kinds are the drawn glyph's size and live above the text, so pointer input outside them never reaches the
 * overlay, and inside one the control consumes the gesture -- right, because tapping a circle must not also move the
 * caret and following a link must not either.
 *
 * No ripple on either: an indication layer over glyphs reads as a smudge on the characters, the same reason the tree's
 * chevron lost its clickable. Feedback is a soft highlight behind the text, shown only while hovered, pressed or
 * focused, so a note at rest looks exactly as it did before the overlay existed.
 */
@Composable
private fun DecorationControl(
    decoration: EditorDecoration,
    modifier: Modifier,
    onToggle: () -> Unit,
    onOpenLink: ((String) -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val highlight =
        when {
            pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
            hovered || focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            else -> null
        }

    val shape = if (decoration.kind == EditorDecoration.Kind.LINK) RoundedCornerShape(3.dp) else CircleShape
    val base =
        modifier
            .clip(shape)
            .background(highlight ?: Color.Transparent)

    when (decoration.kind) {
        // `toggleable` with a checkbox role is what makes this a control rather than a drawn shape: assistive
        // technology gets the checked state and an activation action, and activation runs the same callback a click does.
        EditorDecoration.Kind.CHECKLIST ->
            Box(
                modifier =
                    base
                        .toggleable(
                            value = decoration.checked,
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Checkbox,
                            onValueChange = { onToggle() },
                        ).pointerHoverIcon(PointerIcon.Hand),
            )

        EditorDecoration.Kind.LINK -> {
            val url = decoration.url
            Box(
                modifier =
                    base
                        .then(
                            if (url != null && onOpenLink != null) {
                                Modifier
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null,
                                        role = Role.Button,
                                        onClickLabel = "Open link",
                                        onClick = { onOpenLink(url) },
                                    ).pointerHoverIcon(PointerIcon.Hand)
                            } else {
                                Modifier
                            },
                        ),
            )
        }
    }
}
