// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.jaytech64.piastre.R
import io.github.jaytech64.piastre.feature.ledger.EXPENSE_ID_ARG
import io.github.jaytech64.piastre.feature.ledger.ExpenseFormScreen
import io.github.jaytech64.piastre.feature.ledger.LedgerScreen
import io.github.jaytech64.piastre.feature.ledger.NEW_TEMPLATE_ID
import io.github.jaytech64.piastre.feature.ledger.RECURRING_ID_ARG
import io.github.jaytech64.piastre.feature.ledger.RecurringListScreen
import io.github.jaytech64.piastre.feature.ledger.TEMPLATE_ID_ARG
import io.github.jaytech64.piastre.feature.ledger.TemplateListScreen
import io.github.jaytech64.piastre.feature.premium.PremiumScreen
import io.github.jaytech64.piastre.feature.reports.BUDGET_MONTH_ARG
import io.github.jaytech64.piastre.feature.reports.BudgetsScreen
import io.github.jaytech64.piastre.feature.reports.ReportsScreen
import io.github.jaytech64.piastre.feature.review.ReviewScreen
import io.github.jaytech64.piastre.feature.settings.CATEGORY_GROUP_ID_ARG
import io.github.jaytech64.piastre.feature.settings.CaptureHealthBanner
import io.github.jaytech64.piastre.feature.settings.CategoriesScreen
import io.github.jaytech64.piastre.feature.settings.CategoryGroupScreen
import io.github.jaytech64.piastre.feature.settings.OnboardingScreen
import io.github.jaytech64.piastre.feature.settings.OnboardingViewModel
import io.github.jaytech64.piastre.feature.settings.SettingsScreen

/**
 * Review is not drawn with a warning sign: the capture banner is, and once the
 * bar drops its labels an icon is all a tab has to say what it is.
 */
private enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: @Composable () -> Painter,
) {
    LEDGER("ledger", R.string.nav_ledger, { rememberVectorPainter(Icons.Default.ShoppingCart) }),
    REPORTS("reports", R.string.nav_reports, { painterResource(R.drawable.ic_nav_reports) }),
    REVIEW("review", R.string.nav_review, { painterResource(R.drawable.ic_nav_review) }),
    SETTINGS("settings", R.string.nav_settings, { rememberVectorPainter(Icons.Default.Settings) }),
    ;

    /** The pages that are not this tab and still belong under it, because back from them returns to it. */
    val pagesOverIt: Set<String>
        get() = if (this == REPORTS) setOf(REPORT_TRANSACTIONS_ROUTE) else emptySet()
}

/**
 * The first run explains itself before anything else; every run after it goes
 * straight to the ledger.
 */
@Composable
fun PiastreApp(viewModel: OnboardingViewModel = hiltViewModel()) {
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
        bottomBar = { DestinationBar(currentRoute, ::open) },
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
                // The tab, and the ledger a report opens over itself: one screen,
                // told apart only by the arguments the second is opened with.
                listOf(
                    Destination.LEDGER.route to emptyList(),
                    REPORT_TRANSACTIONS_ROUTE to reportTransactionsArguments,
                ).forEach { (route, arguments) ->
                    composable(route, arguments) {
                        LedgerScreen(
                            onReviewClick = { open(Destination.REVIEW) },
                            onAddClick = { navController.navigate(ADD_EXPENSE_ROUTE) },
                            onTransactionClick = { id -> navController.navigate(editExpenseRoute(id)) },
                            onRecurringClick = { id -> navController.navigate(editRecurringRoute(id)) },
                        )
                    }
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
                composable(Destination.REPORTS.route) {
                    ReportsScreen(
                        onOpenTransactions = { of, period ->
                            navController.navigate(reportTransactionsRoute(of, period))
                        },
                        onOpenBudgets = { month -> navController.navigate("$BUDGETS_ROUTE_BASE/$month") },
                        onOpenPremium = { navController.navigate(PREMIUM_ROUTE) },
                    )
                }
                composable(Destination.REVIEW.route) { ReviewScreen() }
                settingsPages(navController)
            }
        }
    }
}

/** Settings, and the pages reached from it that return to it. */
private fun NavGraphBuilder.settingsPages(navController: NavHostController) {
    composable(Destination.SETTINGS.route) {
        SettingsScreen(
            onOpenCategories = { navController.navigate(CATEGORIES_ROUTE) },
            onOpenRecurring = { navController.navigate(RECURRING_LIST_ROUTE) },
            onOpenTemplates = { navController.navigate(TEMPLATE_LIST_ROUTE) },
            onOpenPremium = { navController.navigate(PREMIUM_ROUTE) },
        )
    }
    composable(PREMIUM_ROUTE) { PremiumScreen() }
    composable(
        "$BUDGETS_ROUTE_BASE/{$BUDGET_MONTH_ARG}",
        arguments = listOf(navArgument(BUDGET_MONTH_ARG) { type = NavType.StringType }),
    ) { BudgetsScreen() }
    composable(CATEGORIES_ROUTE) {
        CategoriesScreen(onOpen = { id -> navController.navigate(categoryGroupRoute(id)) })
    }
    composable(
        CATEGORY_GROUP_ROUTE,
        arguments = listOf(navArgument(CATEGORY_GROUP_ID_ARG) { type = NavType.LongType }),
    ) {
        CategoryGroupScreen(onClose = { navController.popBackStack() })
    }
    composable(UNGROUPED_CATEGORIES_ROUTE) {
        CategoryGroupScreen(onClose = { navController.popBackStack() })
    }
    composable(RECURRING_LIST_ROUTE) {
        RecurringListScreen(onOpen = { id -> navController.navigate(editRecurringRoute(id)) })
    }
    composable(
        EDIT_RECURRING_ROUTE,
        arguments = listOf(navArgument(RECURRING_ID_ARG) { type = NavType.LongType }),
    ) {
        ExpenseFormScreen(onDone = { navController.popBackStack() })
    }
    templatePages(navController)
}

private fun NavGraphBuilder.templatePages(navController: NavHostController) {
    composable(TEMPLATE_LIST_ROUTE) {
        TemplateListScreen(
            onOpen = { id -> navController.navigate(editTemplateRoute(id)) },
            onNew = { navController.navigate(NEW_TEMPLATE_ROUTE) },
        )
    }
    composable(
        NEW_TEMPLATE_ROUTE,
        arguments =
            listOf(
                navArgument(TEMPLATE_ID_ARG) {
                    type = NavType.LongType
                    defaultValue = NEW_TEMPLATE_ID
                },
            ),
    ) {
        ExpenseFormScreen(onDone = { navController.popBackStack() })
    }
    composable(
        EDIT_TEMPLATE_ROUTE,
        arguments = listOf(navArgument(TEMPLATE_ID_ARG) { type = NavType.LongType }),
    ) {
        ExpenseFormScreen(onDone = { navController.popBackStack() })
    }
}

/**
 * Labels under the icons while every one of them fits on a line, and icons alone
 * as soon as one does not, rather than a label broken mid-word at a large font
 * size.
 */
@Composable
private fun DestinationBar(
    currentRoute: String?,
    onOpen: (Destination) -> Unit,
) {
    BoxWithConstraints {
        val labels = Destination.entries.map { stringResource(it.labelRes) }
        val labelled = rememberLabelsFit(labels, maxWidth)

        NavigationBar {
            Destination.entries.forEachIndexed { index, destination ->
                NavigationBarItem(
                    selected = currentRoute == destination.route || currentRoute in destination.pagesOverIt,
                    onClick = { onOpen(destination) },
                    icon = { DestinationIcon(destination, labels[index], labelled) },
                    label = if (labelled) ({ Text(labels[index], maxLines = 1) }) else null,
                )
            }
        }
    }
}

/**
 * Unlabelled, the icon carries the name itself: TalkBack reads it, and a long
 * press shows it, the way an icon button does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationIcon(
    destination: Destination,
    label: String,
    labelled: Boolean,
) {
    if (labelled) {
        Icon(destination.icon(), contentDescription = null)
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Icon(destination.icon(), contentDescription = label)
    }
}

/** Not tabs: both are reached from the ledger and return to it. */
private const val ADD_EXPENSE_ROUTE = "ledger/add"
private const val EDIT_EXPENSE_ROUTE = "ledger/edit/{$EXPENSE_ID_ARG}"

private fun editExpenseRoute(id: Long) = "ledger/edit/$id"

/** Reached from Settings and returning to it; the categories in no group have a page of their own. */
private const val PREMIUM_ROUTE = "settings/premium"
private const val BUDGETS_ROUTE_BASE = "reports/budgets"
private const val CATEGORIES_ROUTE = "settings/categories"
private const val CATEGORY_GROUP_ROUTE = "settings/categories/group/{$CATEGORY_GROUP_ID_ARG}"
private const val UNGROUPED_CATEGORIES_ROUTE = "settings/categories/ungrouped"

private fun categoryGroupRoute(id: Long?) = id?.let { "settings/categories/group/$it" } ?: UNGROUPED_CATEGORIES_ROUTE

/** Reached from Settings and returning to it; a recurring transaction opens in the same form. */
private const val RECURRING_LIST_ROUTE = "settings/recurring"
private const val EDIT_RECURRING_ROUTE = "settings/recurring/{$RECURRING_ID_ARG}"

private fun editRecurringRoute(id: Long) = "settings/recurring/$id"

/** Reached from Settings and returning to it; a template, new or not, opens in the transaction form. */
private const val TEMPLATE_LIST_ROUTE = "settings/templates"
private const val NEW_TEMPLATE_ROUTE = "settings/templates/new"
private const val EDIT_TEMPLATE_ROUTE = "settings/templates/edit/{$TEMPLATE_ID_ARG}"

private fun editTemplateRoute(id: Long) = "settings/templates/edit/$id"
