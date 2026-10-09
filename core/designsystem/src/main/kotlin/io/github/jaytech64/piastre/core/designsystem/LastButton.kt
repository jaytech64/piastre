// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Where the last button of a [ButtonsRow] sits. */
enum class LastButton {
    /** Right after the others, like every other button. */
    IN_LINE,

    /** At the far end of its line, kept apart from the rest — a destructive action. */
    FAR_END,

    /** Right after the others while it fits beside them; at the far end once it has a line of its own. */
    FAR_END_WHEN_ALONE,
}

/**
 * Buttons side by side that move to the next line whole when they run out of room.
 *
 * A plain `Row` gives its last child whatever width is left, however little, and
 * at a large font size that child breaks its word across lines — « Sup/pri/mer »,
 * a time stacked as « 12 / h / 40 ». Here every child is measured at its own
 * width and never squeezed: one that does not fit starts the next line.
 */
@Composable
fun ButtonsRow(
    modifier: Modifier = Modifier,
    last: LastButton = LastButton.IN_LINE,
    gap: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        val gapPx = gap.roundToPx()
        val lines = flowLines(placeables.map { it.width }, constraints.maxWidth, gapPx)
        val lineHeights = lines.map { line -> line.maxOf { placeables[it].height } }
        val width =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                lines.maxOf { line -> line.sumOf { placeables[it].width } + gapPx * (line.size - 1) }
            }
        layout(width, lineHeights.sum()) {
            var y = 0
            lines.forEachIndexed { lineIndex, line ->
                var x = 0
                line.forEach { index ->
                    val placeable = placeables[index]
                    val atEnd = index == placeables.lastIndex && last.pushesToEnd(alone = line.size == 1)
                    val placedX = if (atEnd) width - placeable.width else x
                    placeable.placeRelative(placedX, y + (lineHeights[lineIndex] - placeable.height) / 2)
                    x += placeable.width + gapPx
                }
                y += lineHeights[lineIndex]
            }
        }
    }
}

private fun LastButton.pushesToEnd(alone: Boolean): Boolean =
    when (this) {
        LastButton.IN_LINE -> false
        LastButton.FAR_END -> true
        LastButton.FAR_END_WHEN_ALONE -> alone
    }

/**
 * The children of a [ButtonsRow], by index, line by line: each line takes as many
 * as fit in [maxWidth] with [gap] between them, and a child wider than the line
 * gets a line of its own rather than being cut.
 */
internal fun flowLines(
    widths: List<Int>,
    maxWidth: Int,
    gap: Int,
): List<List<Int>> {
    val lines = mutableListOf<MutableList<Int>>()
    var used = 0
    widths.forEachIndexed { index, width ->
        val line = lines.lastOrNull()
        if (line != null && used + gap + width <= maxWidth) {
            line += index
            used += gap + width
        } else {
            lines += mutableListOf(index)
            used = width
        }
    }
    return lines
}
