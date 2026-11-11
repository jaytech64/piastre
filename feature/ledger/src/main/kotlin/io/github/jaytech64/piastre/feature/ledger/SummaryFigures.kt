// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jaytech64.piastre.core.designsystem.BalanceFigure
import io.github.jaytech64.piastre.core.designsystem.BalanceFigures
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.Money
import java.time.LocalDate
import java.util.Locale

/**
 * Income and expenses, unsigned because the label already says which way the
 * money went, and transfers only where they moved something — which is only ever
 * under an account filter, since on the whole ledger a transfer that counts is
 * already among the other two (ADR-0017). A net transfer can go either way, so
 * that one figure carries its sign.
 *
 * Rows in another currency are in none of them, and a line under them says how
 * many, as ADR-0002 asks of any total that leaves rows out.
 */
@Composable
internal fun SummaryFigures(
    summary: BalanceSummary,
    locale: Locale,
) {
    BalanceFigures(
        buildList {
            add(BalanceFigure(stringResource(R.string.ledger_income), Formatters.money(summary.income, locale)))
            add(BalanceFigure(stringResource(R.string.ledger_expenses), Formatters.money(summary.expenses, locale)))
            if (summary.transfers.minor != 0L) {
                add(
                    BalanceFigure(
                        stringResource(R.string.ledger_transfers),
                        Formatters.signedMoney(summary.transfers, locale),
                    ),
                )
            }
        },
    )
    if (summary.otherCurrencyCount > 0) {
        Text(
            pluralStringResource(
                R.plurals.ledger_other_currency,
                summary.otherCurrencyCount,
                summary.otherCurrencyCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Where the balance lands on [monthEnd] (ADR-0026), under a divider of its own
 * so it is not read as one of the figures that add up to the balance.
 *
 * The amount is measured before the label, so at a large font size the French
 * label wraps and the amount is never cut. A screen reader hears the two as one.
 */
@Composable
internal fun ExpectedBalance(
    expected: Money,
    monthEnd: LocalDate,
    locale: Locale,
) {
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_event),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(R.string.ledger_expected_by, Formatters.dayAndMonth(monthEnd, locale)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(Formatters.signedMoney(expected, locale), style = MaterialTheme.typography.titleMedium)
    }
}
