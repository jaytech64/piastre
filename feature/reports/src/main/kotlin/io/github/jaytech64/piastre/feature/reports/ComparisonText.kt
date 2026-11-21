// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import kotlin.math.abs

/**
 * How far a figure moved, as every screen says it: `▲ 5 %`, `▼ < 1 %`, or
 * *No change*. Null when there is no earlier figure to have moved from, which
 * each screen words for itself.
 */
@Composable
internal fun Comparison.movement(): String? =
    when (this) {
        Comparison.NothingEither, Comparison.NothingBefore -> null
        Comparison.Unchanged -> stringResource(R.string.reports_no_change)
        is Comparison.Slight -> arrow(up) + stringResource(R.string.reports_under_percent, 1)
        is Comparison.Change -> arrow(percent > 0) + stringResource(R.string.reports_percent, abs(percent))
    }

/**
 * Spending less is good news and spending more is a warning, but only for a
 * move worth reading: no change and a move under a percent stay neutral, so
 * the colours keep meaning something on a page full of steady bills.
 */
@Composable
internal fun Comparison.judgedColor(): Color =
    when {
        this !is Comparison.Change -> MaterialTheme.colorScheme.onSurfaceVariant
        percent > 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }

private fun arrow(up: Boolean) = if (up) "▲ " else "▼ "
