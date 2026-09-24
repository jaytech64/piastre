// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Whether every label fits its item on one line.
 *
 * All or nothing: a bar with words under some tabs and none under others reads
 * as broken. The items share the bar's width equally, less the gap Material
 * puts between them.
 */
internal fun labelsFit(
    labelWidths: List<Int>,
    barWidth: Int,
    gap: Int,
): Boolean {
    val slot = (barWidth - gap * (labelWidths.size - 1)) / labelWidths.size
    return labelWidths.all { it <= slot }
}

/**
 * [labelsFit] for these labels, in the style the bar draws them in, at the
 * font size and in the language actually on screen. A French label at 200 %
 * breaks mid-word — "Dépens/es" — well before an English one does.
 */
@Composable
internal fun rememberLabelsFit(
    labels: List<String>,
    barWidth: Dp,
): Boolean {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    return remember(labels, barWidth, style, density) {
        val widths = labels.map { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
        with(density) { labelsFit(widths, barWidth.roundToPx(), ITEM_GAP.roundToPx()) }
    }
}

/** `NavigationBarItemHorizontalPadding` in Material 3, which is internal there. */
private val ITEM_GAP = 8.dp
