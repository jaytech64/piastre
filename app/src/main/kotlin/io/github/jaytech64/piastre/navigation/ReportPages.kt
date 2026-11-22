// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.jaytech64.piastre.feature.reports.MERCHANTS_MONTH_ARG
import io.github.jaytech64.piastre.feature.reports.MERCHANTS_ROUTE
import io.github.jaytech64.piastre.feature.reports.MerchantsScreen
import io.github.jaytech64.piastre.feature.reports.ReportsScreen
import io.github.jaytech64.piastre.feature.reports.TRENDS_ID_ARG
import io.github.jaytech64.piastre.feature.reports.TRENDS_ON_ARG
import io.github.jaytech64.piastre.feature.reports.TRENDS_ON_EVERYTHING
import io.github.jaytech64.piastre.feature.reports.TRENDS_ROUTE
import io.github.jaytech64.piastre.feature.reports.TrendsScreen
import io.github.jaytech64.piastre.feature.reports.merchantsRoute
import io.github.jaytech64.piastre.feature.reports.trendsRoute

/** Reports, on [reportsRoute], and the pages a report opens over itself that return to it. */
internal fun NavGraphBuilder.reportPages(
    navController: NavHostController,
    reportsRoute: String,
) {
    composable(reportsRoute) {
        ReportsScreen(
            onOpenTransactions = { of, period ->
                navController.navigate(reportTransactionsRoute(of, period))
            },
            onOpenBudgets = { month -> navController.navigate("$BUDGETS_ROUTE_BASE/$month") },
            onOpenTrends = { groupId -> navController.navigate(trendsRoute(groupId)) },
            onOpenMerchants = { month -> navController.navigate(merchantsRoute(month)) },
            onOpenPremium = { navController.navigate(PREMIUM_ROUTE) },
        )
    }
    composable(
        TRENDS_ROUTE,
        arguments =
            listOf(
                navArgument(TRENDS_ON_ARG) {
                    type = NavType.StringType
                    defaultValue = TRENDS_ON_EVERYTHING
                },
                navArgument(TRENDS_ID_ARG) {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
    ) {
        TrendsScreen(
            onOpenTransactions = { of, period ->
                navController.navigate(reportTransactionsRoute(of, period))
            },
        )
    }
    composable(
        MERCHANTS_ROUTE,
        arguments = listOf(navArgument(MERCHANTS_MONTH_ARG) { type = NavType.StringType }),
    ) {
        MerchantsScreen(
            onOpenTransactions = { of, span ->
                navController.navigate(reportTransactionsRoute(of, span))
            },
        )
    }
}
