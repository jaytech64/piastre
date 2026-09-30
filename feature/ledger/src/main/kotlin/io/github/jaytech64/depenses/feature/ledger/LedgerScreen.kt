// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.CategoryDialog
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.designsystem.accountLabel
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.Money
import java.time.LocalDate
import java.util.Locale

@Composable
fun LedgerScreen(
    onReviewClick: () -> Unit,
    onAddClick: () -> Unit,
    onTransactionClick: (Long) -> Unit,
    viewModel: LedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today = state.today
    var choosingFilters by rememberSaveable { mutableStateOf(false) }
    var acting by rememberSaveable { mutableStateOf(SelectionAction.NONE) }
    val filterActions =
        remember(viewModel) {
            FilterActions(
                onAccount = viewModel::onAccountSelected,
                onCategory = viewModel::onCategorySelected,
                onMerchant = viewModel::onMerchantSelected,
                onFrom = viewModel::onFromSelected,
                onTo = viewModel::onToSelected,
                onClearAll = viewModel::onFiltersCleared,
            )
        }

    BackHandler(enabled = state.isSelecting, onBack = viewModel.selection::end)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LedgerTop(
                state = state,
                onEndSelection = viewModel.selection::end,
                onAct = { acting = it },
                onFiltersClick = { choosingFilters = true },
                onReviewClick = onReviewClick,
            )
            if (state.filter.isActive && today != null) {
                FilterChips(state.filter, today, filterActions, enabled = !state.isSelecting)
            }

            when {
                state.isEmpty -> {
                    EmptyLedger(state.filter)
                }

                today != null -> {
                    DayList(
                        days = state.days,
                        today = today,
                        selected = state.selected,
                        onClick = { id ->
                            if (state.isSelecting) viewModel.selection.toggle(id) else onTransactionClick(id)
                        },
                        onLongClick = viewModel.selection::toggle,
                    )
                }
            }
        }

        // Hidden while selecting: an entry added now would land outside a selection
        // the bar is about to act on.
        if (!state.isSelecting) {
            AddButton(onAddClick, Modifier.align(Alignment.BottomEnd))
        }
    }

    if (choosingFilters) {
        FilterPage(state, filterActions, onDismiss = { choosingFilters = false })
    }
    SelectionDialog(if (state.isSelecting) acting else SelectionAction.NONE, state, viewModel) { acting = it }
}

@Composable
private fun AddButton(
    onClick: () -> Unit,
    modifier: Modifier,
) {
    FloatingActionButton(onClick = onClick, modifier = modifier.padding(16.dp)) {
        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.manual_add))
    }
}

/**
 * The one sheet or dialog [acting] names over the selection. Each closes itself,
 * or hands over to the next, through [onAct].
 */
@Composable
private fun SelectionDialog(
    acting: SelectionAction,
    state: LedgerUiState,
    viewModel: LedgerViewModel,
    onAct: (SelectionAction) -> Unit,
) {
    val count = state.selected.size
    val onClose = { onAct(SelectionAction.NONE) }
    when (acting) {
        SelectionAction.CATEGORISE -> {
            SelectionCategorySheet(
                count = count,
                categories = state.selectionCategories,
                onPick = {
                    viewModel.selection.categorise(it)
                    onClose()
                },
                onAddCategory = { onAct(SelectionAction.ADD_CATEGORY) },
                onDismiss = onClose,
            )
        }

        SelectionAction.ADD_CATEGORY -> {
            CategoryDialog(
                name = null,
                onSave = {
                    viewModel.selection.categoriseAsNew(it)
                    onClose()
                },
                onDismiss = onClose,
            )
        }

        SelectionAction.DELETE -> {
            ConfirmSelectionDeleteDialog(
                count = count,
                onConfirm = {
                    viewModel.selection.delete()
                    onClose()
                },
                onCancel = onClose,
            )
        }

        SelectionAction.NONE -> {
            Unit
        }
    }
}

/**
 * The balance card, or the selection bar in its place.
 *
 * The bar is at least as tall as the card it replaces, so the row a long press
 * landed on stays under the finger instead of sliding up. The height is not
 * saved: a card measured before a rotation or a font change is the wrong size
 * after it.
 */
@Composable
private fun LedgerTop(
    state: LedgerUiState,
    onEndSelection: () -> Unit,
    onAct: (SelectionAction) -> Unit,
    onFiltersClick: () -> Unit,
    onReviewClick: () -> Unit,
) {
    var headerHeight by remember { mutableIntStateOf(0) }
    val selectionSummary = state.selectionSummary
    if (selectionSummary != null) {
        SelectionBar(
            count = state.selected.size,
            summary = selectionSummary,
            onEnd = onEndSelection,
            onAct = onAct,
            modifier = Modifier.heightIn(min = with(LocalDensity.current) { headerHeight.toDp() }),
        )
    } else {
        MonthHeader(
            state,
            onFiltersClick = onFiltersClick,
            onReviewClick = onReviewClick,
            modifier = Modifier.onSizeChanged { headerHeight = it.height },
        )
    }
}

/**
 * The balance, what it is a balance of, what it is made of, and the purchases no
 * total has counted yet.
 */
@Composable
private fun MonthHeader(
    state: LedgerUiState,
    onFiltersClick: () -> Unit,
    onReviewClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()

    Card(
        modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BalanceRow(state, locale, onFiltersClick)

            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SummaryFigures(state.headline, locale)

            // Shown only when there is something to say. Totals exclude unreviewed
            // purchases, so without this a low number would be a silent lie — and
            // saying it without offering the way to fix it would only be half honest.
            //
            // It stays the whole ledger's count while a filter is on: a purchase
            // still waiting for review often has no account, merchant or category
            // resolved yet, so narrowing this would hide the work rather than scope it.
            if (state.needsReview > 0) {
                AssistChip(
                    onClick = onReviewClick,
                    label = {
                        Text(
                            pluralStringResource(
                                R.plurals.ledger_needs_review,
                                state.needsReview,
                                state.needsReview,
                            ),
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.tertiary),
                )
            }
        }
    }
}

/** What the balance is of, the balance, and the button that changes what it is of. */
@Composable
private fun BalanceRow(
    state: LedgerUiState,
    locale: Locale,
    onFiltersClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                balanceLabel(state.filter, state.today),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Formatters.signedMoney(state.headline.balance, locale),
                style = MaterialTheme.typography.displaySmall,
            )
        }
        IconButton(onClick = onFiltersClick) {
            Icon(
                painterResource(R.drawable.ic_filter),
                contentDescription = stringResource(R.string.ledger_filter),
                tint =
                    if (state.filter.isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

/**
 * The period the balance is of — this month, or the filter's dates (ADR-0014) —
 * then how many other filters narrow it. The chips
 * right below name each one, so naming them here too only made the label
 * run to several lines.
 */
@Composable
private fun balanceLabel(
    filter: LedgerFilter,
    today: LocalDate?,
): String {
    if (!filter.isActive || today == null) return stringResource(R.string.ledger_balance_month)
    val others = filter.countBesideDates
    return listOfNotNull(
        datesLabel(filter, today) ?: stringResource(R.string.ledger_period_this_month),
        pluralStringResource(R.plurals.ledger_filter_count, others, others).takeIf { others > 0 },
    ).joinToString(" · ")
}

@Composable
private fun DayList(
    days: List<LedgerDay>,
    today: LocalDate,
    selected: Set<Long>,
    onClick: (Long) -> Unit,
    onLongClick: (Long) -> Unit,
) {
    val locale = currentLocale()
    val labels =
        Formatters.RelativeDayLabels(
            today = stringResource(R.string.ledger_today),
            yesterday = stringResource(R.string.ledger_yesterday),
        )

    LazyColumn(contentPadding = PaddingValues(bottom = ADD_BUTTON_CLEARANCE)) {
        days.forEach { day ->
            item(key = day.date.toEpochDay()) {
                DayHeader(
                    label = Formatters.dayHeader(day.date, today, labels, locale),
                    total = day.total,
                )
            }
            items(day.transactions, key = { it.id }) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    locale = locale,
                    selection =
                        when {
                            selected.isEmpty() -> RowSelection.NONE
                            transaction.id in selected -> RowSelection.SELECTED
                            else -> RowSelection.UNSELECTED
                        },
                    onClick = { onClick(transaction.id) },
                    onLongClick = { onLongClick(transaction.id) },
                )
            }
        }
    }
}

@Composable
private fun DayHeader(
    label: String,
    total: Money,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            Formatters.signedMoney(total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * An empty ledger and an empty filter are different facts. Telling someone who
 * narrowed the screen that a Wallet payment will appear on its own answers a
 * question they did not ask.
 */
@Composable
private fun EmptyLedger(filter: LedgerFilter) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (filter.isActive) {
            Text(stringResource(R.string.ledger_empty_filtered), style = MaterialTheme.typography.titleMedium)
            return@Column
        }
        Text(stringResource(R.string.ledger_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.ledger_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Compose's configuration rather than [Locale.getDefault]: the two differ the
 * moment the user sets a per-app language, and the formatter must follow what is
 * actually on screen.
 */
@Composable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/**
 * Room below the last row for the floating add button: its 56 dp height, its
 * 16 dp margin, and 16 dp of air. Without it the newest-first list ends with the
 * oldest purchase's amount hidden under the button, unreachable by scrolling.
 */
private val ADD_BUTTON_CLEARANCE = 88.dp
