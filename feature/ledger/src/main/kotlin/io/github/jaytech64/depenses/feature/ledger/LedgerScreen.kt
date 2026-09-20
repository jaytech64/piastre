// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.designsystem.accountMenuLabel
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.AccountType
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

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            MonthHeader(
                state = state,
                onAccountSelected = viewModel::onAccountSelected,
                onReviewClick = onReviewClick,
            )

            when {
                state.isEmpty -> EmptyLedger(state.selectedAccount)
                today != null -> DayList(state.days, today, onTransactionClick)
            }
        }

        FloatingActionButton(
            onClick = onAddClick,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.manual_add))
        }
    }
}

/**
 * The balance, what it is a balance of, and the two things that qualify it: the
 * account the screen is narrowed to, and the purchases no total has counted yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthHeader(
    state: LedgerUiState,
    onAccountSelected: (Long?) -> Unit,
    onReviewClick: () -> Unit,
) {
    val selected = state.selectedAccount

    Card(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        selected?.let { stringResource(R.string.ledger_balance_month_account, it.label()) }
                            ?: stringResource(R.string.ledger_balance_month),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        Formatters.signedMoney(state.balanceThisMonth),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                AccountFilterMenu(state.accounts, selected, onAccountSelected)
            }

            // A long account name and a review count do not fit one line on a narrow
            // phone, and neither chip may be the one that gets cut.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selected != null) {
                    InputChip(
                        selected = true,
                        onClick = { onAccountSelected(null) },
                        label = { Text(selected.label(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.ledger_filter_clear),
                                Modifier.size(CHIP_ICON),
                            )
                        },
                    )
                }

                // Shown only when there is something to say. Totals exclude unreviewed
                // purchases, so without this a low number would be a silent lie — and
                // saying it without offering the way to fix it would only be half honest.
                //
                // It stays the whole ledger's count while a filter is on: a purchase
                // still waiting for review often has no account resolved yet, so
                // narrowing this would hide the work rather than scope it.
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
}

/**
 * Every account, each with what it currently holds, so the choice is made against
 * the balances rather than against a list of names.
 *
 * Those balances are the account's whole history, the same figure Settings shows,
 * where the headline the menu sits above is this month alone. The label under a
 * filter says "· this month" for exactly that reason.
 */
@Composable
private fun AccountFilterMenu(
    accounts: List<Account>,
    selected: Account?,
    onAccountSelected: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val locale = currentLocale()

    fun pick(id: Long?) {
        onAccountSelected(id)
        expanded = false
    }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painterResource(R.drawable.ic_filter_account),
                contentDescription = stringResource(R.string.ledger_filter),
                tint =
                    if (selected == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ledger_filter_all)) },
                leadingIcon = { SelectedMark(selected == null) },
                onClick = { pick(null) },
            )
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text(account.label(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { SelectedMark(selected?.id == account.id) },
                    trailingIcon = {
                        Text(
                            Formatters.money(account.balance, locale),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { pick(account.id) },
                )
            }
        }
    }
}

/**
 * The name to put on an account, never an empty one.
 *
 * [accountMenuLabel] builds its label out of the name and the digits, and the
 * cash account every database is created with has neither — so it would
 * otherwise arrive as a blank row carrying only a balance. The wording lives
 * here because `:core:designsystem` has no strings of its own by design.
 */
@Composable
private fun Account.label(): String =
    accountMenuLabel(this).ifBlank {
        stringResource(if (type == AccountType.CASH) R.string.ledger_cash else R.string.ledger_account)
    }

/** Always occupies the slot, so the names line up whichever row is checked. */
@Composable
private fun SelectedMark(selected: Boolean) {
    if (selected) {
        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    } else {
        Spacer(Modifier.size(CHIP_ICON))
    }
}

@Composable
private fun DayList(
    days: List<LedgerDay>,
    today: LocalDate,
    onTransactionClick: (Long) -> Unit,
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
                TransactionRow(transaction, locale, onClick = { onTransactionClick(transaction.id) })
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
 * narrowed the screen to one account that a Wallet payment will appear on its own
 * answers a question they did not ask.
 */
@Composable
private fun EmptyLedger(selected: Account?) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (selected != null) {
            Text(stringResource(R.string.ledger_empty_account), style = MaterialTheme.typography.titleMedium)
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
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/**
 * Room below the last row for the floating add button: its 56 dp height, its
 * 16 dp margin, and 16 dp of air. Without it the newest-first list ends with the
 * oldest purchase's amount hidden under the button, unreachable by scrolling.
 */
private val ADD_BUTTON_CLEARANCE = 88.dp

/** Material's own dense icon size, which the chip and the menu mark both sit at. */
private val CHIP_ICON = 18.dp
