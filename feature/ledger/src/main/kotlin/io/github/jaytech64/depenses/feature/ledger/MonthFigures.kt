// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.jaytech64.depenses.core.designsystem.BalanceFigure
import io.github.jaytech64.depenses.core.designsystem.BalanceFigures
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
    BalanceFigures(
        buildList {
            add(BalanceFigure(stringResource(R.string.ledger_income), Formatters.money(month.income, locale)))
            add(BalanceFigure(stringResource(R.string.ledger_expenses), Formatters.money(month.expenses, locale)))
            if (month.transfers.minor != 0L) {
                add(
                    BalanceFigure(
                        stringResource(R.string.ledger_transfers),
                        Formatters.signedMoney(month.transfers, locale),
                    ),
                )
            }
        },
    )
}
