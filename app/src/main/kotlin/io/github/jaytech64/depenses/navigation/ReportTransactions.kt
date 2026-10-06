// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.navigation

import androidx.navigation.NavType
import androidx.navigation.navArgument
import io.github.jaytech64.depenses.core.model.ReportPeriod
import io.github.jaytech64.depenses.feature.ledger.OPENED_FROM_ARG
import io.github.jaytech64.depenses.feature.ledger.OPENED_ID_ARG
import io.github.jaytech64.depenses.feature.ledger.OPENED_ON_ARG
import io.github.jaytech64.depenses.feature.ledger.OPENED_ON_CATEGORY
import io.github.jaytech64.depenses.feature.ledger.OPENED_ON_GROUP
import io.github.jaytech64.depenses.feature.ledger.OPENED_ON_NO_CATEGORY
import io.github.jaytech64.depenses.feature.ledger.OPENED_TO_ARG
import io.github.jaytech64.depenses.feature.reports.SpendingOf

/**
 * The transactions behind a figure of a report: a ledger of its own, opened
 * over the report and narrowed to what was tapped, so that back returns to the
 * report and the Ledger tab keeps whatever filter was left on it.
 *
 * The two features do not know each other; this is where what a report asked
 * to see is turned into what a ledger can be opened on.
 */
internal const val REPORT_TRANSACTIONS_ROUTE =
    "reports/transactions/{$OPENED_ON_ARG}/{$OPENED_ID_ARG}/{$OPENED_FROM_ARG}/{$OPENED_TO_ARG}"

internal val reportTransactionsArguments =
    listOf(
        navArgument(OPENED_ON_ARG) { type = NavType.StringType },
        navArgument(OPENED_ID_ARG) { type = NavType.LongType },
        navArgument(OPENED_FROM_ARG) { type = NavType.LongType },
        navArgument(OPENED_TO_ARG) { type = NavType.LongType },
    )

/** The whole period, first day to last, even while it is still running. */
internal fun reportTransactionsRoute(
    of: SpendingOf,
    period: ReportPeriod,
): String {
    val (openedOn, id) =
        when (of) {
            is SpendingOf.OneCategory -> OPENED_ON_CATEGORY to of.categoryId
            is SpendingOf.OneGroup -> OPENED_ON_GROUP to of.groupId
            SpendingOf.NoCategory -> OPENED_ON_NO_CATEGORY to 0L
        }
    return "reports/transactions/$openedOn/$id/${period.start.toEpochDay()}/${period.endInclusive.toEpochDay()}"
}
