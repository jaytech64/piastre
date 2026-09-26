// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/**
 * One choice among a few, side by side as segmented buttons while every label
 * fits on one line in its share of the width, and stacked one per line when one
 * does not.
 *
 * Segmented buttons split the width evenly whatever the words, so at a large
 * font size a French label breaks mid-word — « Viremen/t », « Com/ptant ». The
 * text is never shrunk to avoid that: the size you chose is the point. The
 * decision is measured, like the tab bar's, in the language, font size and width
 * actually on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
) {
    val labels = options.map { label(it) }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val rowWidth = constraints.maxWidth
        val fits =
            remember(labels, rowWidth, style, density) {
                val widths = labels.map { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
                with(density) { choicesFit(widths, rowWidth, SEGMENT_CHROME.roundToPx()) }
            }
        if (fits) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(labels[index])
                    }
                }
            }
        } else {
            Column(Modifier.selectableGroup()) {
                options.forEachIndexed { index, option ->
                    StackedChoice(labels[index], option == selected, onClick = { onSelect(option) })
                }
            }
        }
    }
}

/** One option of a stacked [ChoiceRow]: a radio button and its label, the whole row a 48 dp target. */
@Composable
private fun StackedChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Whether every label fits on one line in an even share of [rowWidth], after
 * [chrome] — the padding and the check mark a segment spends before its text.
 * The check mark is counted on every segment, since the selection can move to any.
 */
internal fun choicesFit(
    labelWidths: List<Int>,
    rowWidth: Int,
    chrome: Int,
): Boolean {
    val room = rowWidth / labelWidths.size - chrome
    return labelWidths.all { it <= room }
}

/**
 * What a Material 3 segmented button spends besides its label: 12 dp of padding
 * either side, and the 18 dp check mark with the 8 dp after it.
 */
private val SEGMENT_CHROME = 12.dp * 2 + 18.dp + 8.dp
