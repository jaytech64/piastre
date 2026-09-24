// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.Formatters
import java.util.Locale

/**
 * Income and expenses, unsigned because the label already says which way the
 * money went, and transfers only where they moved something — which is only ever
 * under a filter, since they are worth zero to the whole ledger. A net transfer
 * can go either way, so that one figure carries its sign.
 */
@Composable
internal fun MonthFigures(
    month: MonthSummary,
    locale: Locale,
) {
    FiguresRow {
        MonthFigure(stringResource(R.string.ledger_income), Formatters.money(month.income, locale))
        MonthFigure(stringResource(R.string.ledger_expenses), Formatters.money(month.expenses, locale))
        if (month.transfers.minor != 0L) {
            MonthFigure(stringResource(R.string.ledger_transfers), Formatters.signedMoney(month.transfers, locale))
        }
    }
}

/**
 * The label and the amount are laid out as two pieces, so a figure can sit label
 * over amount in a column or label beside amount on a line of its own. A screen
 * reader still hears one node: "Income, $2,450.00" rather than two.
 */
@Composable
private fun MonthFigure(
    label: String,
    amount: String,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics {},
    )
    Text(
        amount,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$label, $amount" },
    )
}

/**
 * Equal columns, label over amount, while every figure fits its own column.
 * As soon as one does not, each figure takes a line instead, label at the start
 * and amount at the end: two French amounts in the thousands at a large font
 * size do not share a narrow phone's width, and stacking them label over amount
 * as well made the header tall enough to push the chips below it out of the
 * card. An amount is never the thing that gets cut.
 *
 * [content] emits each figure as a label then an amount, in that order.
 */
@Composable
private fun FiguresRow(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val figures = measurables.chunked(2).map { (label, amount) -> label to amount }
        val gap = FIGURE_GAP.roundToPx()
        val column = (constraints.maxWidth - gap * (figures.size - 1)) / figures.size
        val fitsColumns =
            figures.all { (label, amount) ->
                label.maxIntrinsicWidth(Constraints.Infinity) <= column &&
                    amount.maxIntrinsicWidth(Constraints.Infinity) <= column
            }

        if (fitsColumns) {
            columns(figures, column, gap, constraints.maxWidth)
        } else {
            lines(figures, constraints.maxWidth)
        }
    }
}

private fun MeasureScope.columns(
    figures: List<Pair<Measurable, Measurable>>,
    column: Int,
    gap: Int,
    width: Int,
): MeasureResult {
    val cell = Constraints(maxWidth = column)
    val placed = figures.map { (label, amount) -> label.measure(cell) to amount.measure(cell) }
    val labelHeight = placed.maxOf { it.first.height }
    val height = labelHeight + placed.maxOf { it.second.height }
    return layout(width, height) {
        placed.forEachIndexed { index, (label, amount) ->
            val x = index * (column + gap)
            label.place(x, 0)
            amount.place(x, labelHeight)
        }
    }
}

/**
 * One line per figure, label and amount sharing a baseline. A line whose two
 * halves still do not fit side by side falls back to label over amount.
 */
private fun MeasureScope.lines(
    figures: List<Pair<Measurable, Measurable>>,
    width: Int,
): MeasureResult {
    val line = Constraints(maxWidth = width)
    val gap = FIGURE_GAP.roundToPx()
    val placed = figures.map { (label, amount) -> label.measure(line) to amount.measure(line) }
    val heights =
        placed.map { (label, amount) ->
            if (label.width + gap + amount.width <=
                width
            ) {
                maxOf(label.height, amount.height)
            } else {
                label.height + amount.height
            }
        }
    return layout(width, heights.sum()) {
        var y = 0
        placed.forEachIndexed { index, (label, amount) ->
            if (label.width + gap + amount.width <= width) {
                val baseline = maxOf(label[FirstBaseline], amount[FirstBaseline])
                label.place(0, y + baseline - label[FirstBaseline])
                amount.place(width - amount.width, y + baseline - amount[FirstBaseline])
            } else {
                label.place(0, y)
                amount.place(0, y + label.height)
            }
            y += heights[index]
        }
    }
}

private val FIGURE_GAP = 16.dp
