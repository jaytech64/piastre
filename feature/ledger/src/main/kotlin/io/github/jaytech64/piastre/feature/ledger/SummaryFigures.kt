// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.jaytech64.piastre.core.designsystem.BalanceFigure
import io.github.jaytech64.piastre.core.designsystem.BalanceFigures
import io.github.jaytech64.piastre.core.designsystem.Formatters
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
