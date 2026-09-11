// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.jaytech64.depenses.R
import io.github.jaytech64.depenses.feature.ledger.EXPENSE_ID_ARG
import io.github.jaytech64.depenses.feature.ledger.ExpenseFormScreen
import io.github.jaytech64.depenses.feature.ledger.LedgerScreen
import io.github.jaytech64.depenses.feature.reports.ReportsScreen
import io.github.jaytech64.depenses.feature.review.ReviewScreen
import io.github.jaytech64.depenses.feature.settings.CaptureHealthBanner
import io.github.jaytech64.depenses.feature.settings.OnboardingScreen
import io.github.jaytech64.depenses.feature.settings.OnboardingViewModel
import io.github.jaytech64.depenses.feature.settings.SettingsScreen

private enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    LEDGER("ledger", R.string.nav_ledger, Icons.Default.ShoppingCart),
    REPORTS("reports", R.string.nav_reports, Icons.AutoMirrored.Filled.List),
    REVIEW("review", R.string.nav_review, Icons.Default.Warning),
    SETTINGS("settings", R.string.nav_settings, Icons.Default.Settings),
}

/**
 * The first run explains itself before anything else; every run after it goes
 * straight to the ledger.
 */
@Composable
fun DepensesApp(viewModel: OnboardingViewModel = hiltViewModel()) {
    when (viewModel.completed.collectAsStateWithLifecycle().value) {
        // Still being read off disk. A frame of nothing beats a frame of the
        // welcome screen shown to someone who onboarded months ago.
        null -> Unit

        false -> OnboardingScreen(onDone = viewModel::complete)

        true -> MainShell()
    }
}

@Composable
private fun MainShell() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    fun open(destination: Destination) {
        navController.navigate(destination.route) {
            popUpTo(Destination.LEDGER.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = { open(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Above the NavHost rather than on the ledger: a capture that has
            // stopped makes every number in the app quietly wrong, not the ones
            // on one screen.
            CaptureHealthBanner()

            NavHost(
                navController = navController,
                startDestination = Destination.LEDGER.route,
            ) {
                composable(Destination.LEDGER.route) {
                    LedgerScreen(
                        onReviewClick = { open(Destination.REVIEW) },
                        onAddClick = { navController.navigate(ADD_EXPENSE_ROUTE) },
                        onTransactionClick = { id -> navController.navigate(editExpenseRoute(id)) },
                    )
                }
                composable(ADD_EXPENSE_ROUTE) {
                    ExpenseFormScreen(onDone = { navController.popBackStack() })
                }
                composable(
                    EDIT_EXPENSE_ROUTE,
                    arguments = listOf(navArgument(EXPENSE_ID_ARG) { type = NavType.LongType }),
                ) {
                    ExpenseFormScreen(onDone = { navController.popBackStack() })
                }
                composable(Destination.REPORTS.route) { ReportsScreen() }
                composable(Destination.REVIEW.route) { ReviewScreen() }
                composable(Destination.SETTINGS.route) { SettingsScreen() }
            }
        }
    }
}

/** Not tabs: both are reached from the ledger and return to it. */
private const val ADD_EXPENSE_ROUTE = "ledger/add"
private const val EDIT_EXPENSE_ROUTE = "ledger/edit/{$EXPENSE_ID_ARG}"

private fun editExpenseRoute(id: Long) = "ledger/edit/$id"
