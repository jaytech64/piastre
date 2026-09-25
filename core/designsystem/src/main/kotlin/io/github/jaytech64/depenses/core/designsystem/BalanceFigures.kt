// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * The figures under a balance, the way the ledger and the reports header both
 * draw them.
 *
 * Equal columns, label over amount, while every figure and every line under it
 * fits its own column. As soon as one does not, each figure takes a line
 * instead, label at the start and amount at the end: two French amounts in the
 * thousands at a large font size do not share a narrow phone's width, and
 * stacking them label over amount as well made the header tall enough to push
 * the chips below it out of the card. An amount is never the thing that gets
 * cut, and a comparison wrapped over three lines of half the card's width reads
 * worse than one line across it.
 *
 * In columns, supporting lines settle to the bottom, so the last line of each
 * figure shares a row with the last line of its neighbour: a comparison under
 * one figure sits level with the comparison under the next, even when only one
 * of them has a count above it. On a line of its own, each supporting line is
 * set against the end, under the amount it qualifies.
 *
 * A screen reader hears the label and the amount as one node, "Income,
 * $2,450.00" rather than two, then each supporting line after it.
 */
@Composable
fun BalanceFigures(figures: List<BalanceFigure>) {
    Layout(
        content = {
            figures.forEachIndexed { index, figure ->
                FigureLabel(figure.label, Modifier.layoutId(Slot(index, Part.LABEL)))
                FigureAmount(figure.label, figure.amount, Modifier.layoutId(Slot(index, Part.AMOUNT)))
                figure.supporting.forEach { line ->
                    Box(Modifier.layoutId(Slot(index, Part.SUPPORTING))) { line() }
                }
            }
        },
    ) { measurables, constraints ->
        val cells =
            measurables.groupBy { (it.layoutId as Slot).figure }.values.map { parts ->
                Cell(
                    label = parts.single { (it.layoutId as Slot).part == Part.LABEL },
                    amount = parts.single { (it.layoutId as Slot).part == Part.AMOUNT },
                    supporting = parts.filter { (it.layoutId as Slot).part == Part.SUPPORTING },
                )
            }
        val gap = FIGURE_GAP.roundToPx()
        val column = (constraints.maxWidth - gap * (cells.size - 1)) / cells.size
        val fitsColumns =
            cells.all { cell ->
                (listOf(cell.label, cell.amount) + cell.supporting).all {
                    it.maxIntrinsicWidth(Constraints.Infinity) <= column
                }
            }

        if (fitsColumns) {
            columns(cells, column, gap, constraints.maxWidth)
        } else {
            lines(cells, constraints.maxWidth)
        }
    }
}

@Composable
private fun FigureLabel(
    label: String,
    modifier: Modifier,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clearAndSetSemantics {},
    )
}

@Composable
private fun FigureAmount(
    label: String,
    amount: String,
    modifier: Modifier,
) {
    Text(
        amount,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.clearAndSetSemantics { contentDescription = "$label, $amount" },
    )
}

private enum class Part { LABEL, AMOUNT, SUPPORTING }

private data class Slot(
    val figure: Int,
    val part: Part,
)

private class Cell(
    val label: Measurable,
    val amount: Measurable,
    val supporting: List<Measurable>,
)

private fun MeasureScope.columns(
    cells: List<Cell>,
    column: Int,
    gap: Int,
    width: Int,
): MeasureResult {
    val within = Constraints(maxWidth = column)
    val labels = cells.map { it.label.measure(within) }
    val amounts = cells.map { it.amount.measure(within) }
    val supporting = cells.map { cell -> cell.supporting.map { it.measure(within) } }
    val labelHeight = labels.maxOf { it.height }
    val amountHeight = amounts.maxOf { it.height }
    val supportingHeight = supporting.maxOf { lines -> lines.sumOf { it.height } }
    return layout(width, labelHeight + amountHeight + supportingHeight) {
        cells.indices.forEach { index ->
            val x = index * (column + gap)
            labels[index].place(x, 0)
            amounts[index].place(x, labelHeight)
            var y = labelHeight + amountHeight + supportingHeight - supporting[index].sumOf { it.height }
            supporting[index].forEach { line ->
                line.place(x, y)
                y += line.height
            }
        }
    }
}

/**
 * One line per figure, label and amount sharing a baseline. A line whose two
 * halves still do not fit side by side falls back to label over amount.
 */
private fun MeasureScope.lines(
    cells: List<Cell>,
    width: Int,
): MeasureResult {
    val within = Constraints(maxWidth = width)
    val gap = FIGURE_GAP.roundToPx()
    val placed =
        cells.map { cell ->
            PlacedCell(
                label = cell.label.measure(within),
                amount = cell.amount.measure(within),
                supporting = cell.supporting.map { it.measure(within) },
            )
        }
    val heights =
        placed.map { cell ->
            val figure =
                if (cell.sharesLine(gap, width)) {
                    maxOf(cell.label.height, cell.amount.height)
                } else {
                    cell.label.height + cell.amount.height
                }
            figure + cell.supporting.sumOf { it.height }
        }
    return layout(width, heights.sum()) {
        var y = 0
        placed.forEach { cell ->
            if (cell.sharesLine(gap, width)) {
                val baseline = maxOf(cell.label[FirstBaseline], cell.amount[FirstBaseline])
                cell.label.place(0, y + baseline - cell.label[FirstBaseline])
                cell.amount.place(width - cell.amount.width, y + baseline - cell.amount[FirstBaseline])
                y += maxOf(cell.label.height, cell.amount.height)
            } else {
                cell.label.place(0, y)
                cell.amount.place(0, y + cell.label.height)
                y += cell.label.height + cell.amount.height
            }
            cell.supporting.forEach { line ->
                line.place(width - line.width, y)
                y += line.height
            }
        }
    }
}

private class PlacedCell(
    val label: Placeable,
    val amount: Placeable,
    val supporting: List<Placeable>,
) {
    fun sharesLine(
        gap: Int,
        width: Int,
    ): Boolean = label.width + gap + amount.width <= width
}

private val FIGURE_GAP = 16.dp
