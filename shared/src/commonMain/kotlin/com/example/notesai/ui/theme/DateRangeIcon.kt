package com.example.notesai.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Material Symbols' "date_range" glyph, vendored as a vector.
 *
 * Vendored rather than taken from a library for one reason: Compose Multiplatform stopped
 * publishing `material-icons-core` after 1.7.3 (maven central confirms), and pinning a stale
 * artifact into a 1.11 classpath buys less than these twenty lines cost. The path data is the
 * standard Material Design 24dp `date_range` icon (Apache License 2.0, like the library it
 * comes from).
 *
 * `Icon` tints the vector with `LocalContentColor`, so the fill color here is a placeholder the
 * tint replaces.
 */
internal val DateRangeIcon: ImageVector =
    ImageVector
        .Builder(
            name = "DateRange",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData =
                    addPathNodes(
                        // Material Design date_range, 24dp: calendar body, three event dots, header.
                        "M9 11H7v2h2v-2zm4 0h-2v2h2v-2zm4 0h-2v2h2v-2zm4-7h-2V2h-2v2H9V2H7v2H5" +
                            "c-1.11 0-1.99.9-1.99 2L3 20c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2" +
                            "zm0 16H5V9h14v11zM19 8H5V6h14v2z",
                    ),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            )
        }.build()
