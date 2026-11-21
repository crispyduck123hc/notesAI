package com.example.notesai.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult

/** The box an overlay element occupies, exposed so tests can assert placement instead of eyeballing pixels. */
internal val EditorDecorationBoundsKey = SemanticsPropertyKey<Rect>("EditorDecorationBounds")

/** Test tag prefix for the overlay's elements: `editor-decoration-0`, `editor-decoration-1`, ... */
internal const val DECORATION_TAG_PREFIX = "editor-decoration-"

/**
 * The overlay surface: one element per visible decoration, positioned from the field's own layout.
 *
 * Phase 2 composes the boxes but draws nothing inside them. That is deliberate: this phase exists to prove the geometry,
 * and an empty box with a measurable bound is a better proof than a drawn circle, because a test can compare it against
 * the glyph box the text engine reports. Phase 3 fills these boxes with the real control (a checkbox with a click, a
 * role and hover states), which is why the elements are composables and not canvas drawing -- only a real node can carry
 * semantics and take a click.
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

        visible.forEachIndexed { index, decoration ->
            Box(
                modifier =
                    Modifier
                        .offset(
                            x = with(density) { decoration.bounds.left.toDp() },
                            y = with(density) { (decoration.bounds.top - scrollOffsetPx).toDp() },
                        ).size(
                            width = with(density) { decoration.bounds.width.toDp() },
                            height = with(density) { decoration.bounds.height.toDp() },
                        ).testTag(DECORATION_TAG_PREFIX + index)
                        .semantics { this[EditorDecorationBoundsKey] = decoration.bounds },
            )
        }
    }
}
