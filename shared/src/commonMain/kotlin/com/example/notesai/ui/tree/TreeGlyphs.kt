package com.example.notesai.ui.tree

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.example.notesai.ui.theme.Dimens

/**
 * The disclosure chevron, drawn rather than typed.
 *
 * It used to be the characters `\u25B8` and `\u25BE`, which made its size a side effect of the font: you
 * could change it indirectly by changing the text style, but you could not say "12dp" and get 12dp.
 * Drawing two strokes inside a [size] box makes the footprint exact, keeps the joins clean at any size,
 * and costs one small file instead of pulling in the Material icons artifact — which is deprecated for
 * Compose Multiplatform and brings megabytes with it.
 */
@Composable
internal fun ChevronIcon(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.TreeChevronSize,
    strokeWidth: Dp = Dimens.TreeGlyphStroke,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier.size(size)) {
        val width = this.size.width
        val height = this.size.height
        val path =
            Path().apply {
                if (expanded) {
                    // A "v": down at the sides, meeting in the middle.
                    moveTo(width * 0.18f, height * 0.36f)
                    lineTo(width * 0.5f, height * 0.66f)
                    lineTo(width * 0.82f, height * 0.36f)
                } else {
                    // A ">": out to the right in the middle.
                    moveTo(width * 0.36f, height * 0.18f)
                    lineTo(width * 0.66f, height * 0.5f)
                    lineTo(width * 0.36f, height * 0.82f)
                }
            }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * The delete cross, drawn for the same reason: a `TextButton` around a "✕" character dragged Material's
 * 48dp minimum height into the row, so rows with a delete affordance were taller than rows without one.
 * Here the glyph is exactly [size] and the row decides the height.
 */
@Composable
internal fun CrossIcon(
    modifier: Modifier = Modifier,
    size: Dp = Dimens.TreeChevronSize,
    strokeWidth: Dp = Dimens.TreeGlyphStroke,
    tint: Color = LocalContentColor.current,
) {
    Canvas(modifier.size(size)) {
        val width = this.size.width
        val height = this.size.height
        val stroke = strokeWidth.toPx()
        drawLine(
            color = tint,
            start = Offset(width * 0.2f, height * 0.2f),
            end = Offset(width * 0.8f, height * 0.8f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(width * 0.8f, height * 0.2f),
            end = Offset(width * 0.2f, height * 0.8f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
