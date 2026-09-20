// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.AccountDialog
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.designsystem.accountLabel
import io.github.jaytech64.depenses.core.designsystem.accountTypeLabel
import io.github.jaytech64.depenses.core.designsystem.walletLabel
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.AccountOrigin

/**
 * Every account money sits in, named or still waiting for a name. The name given
 * here is what the ledger shows beside each transaction on that account.
 */
@Composable
internal fun AccountsSection(viewModel: AccountsViewModel = hiltViewModel()) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val removalRefused by viewModel.removalRefused.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AccountEdit?>(null) }

    SectionHeader(stringResource(R.string.settings_accounts_section))

    if (accounts.isEmpty()) {
        Text(
            stringResource(R.string.settings_accounts_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    accounts.forEach { account -> AccountRow(account, onClick = { editing = AccountEdit.Existing(account) }) }

    TextButton(onClick = { editing = AccountEdit.New }, modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(stringResource(R.string.settings_accounts_add))
    }

    if (removalRefused) {
        Text(
            stringResource(R.string.settings_accounts_still_used),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    editing?.let { edit ->
        val account = (edit as? AccountEdit.Existing)?.account
        AccountDialog(
            account = account,
            onSave = { name, type, last4, openingBalance ->
                viewModel.save(account, name, type, last4, openingBalance)
                editing = null
            },
            onDismiss = { editing = null },
            onRemove =
                account?.takeIf { it.transactionCount == 0 }?.let { removable ->
                    {
                        viewModel.remove(removable)
                        editing = null
                    }
                },
        )
    }
}

private sealed interface AccountEdit {
    data object New : AccountEdit

    data class Existing(
        val account: Account,
    ) : AccountEdit
}

/**
 * A named account leads with its name; one still waiting for a name leads with how
 * Wallet shows it, and says it can be named. Either ends with what it holds now,
 * which carries the income colour when it is above zero.
 */
@Composable
private fun AccountRow(
    account: Account,
    onClick: () -> Unit,
) {
    val digits = walletLabel(account)
    val transactions =
        pluralStringResource(
            R.plurals.settings_accounts_transactions,
            account.transactionCount,
            account.transactionCount,
        )
    val kind = stringResource(accountTypeLabel(account.type))
    val addedByHand = stringResource(R.string.settings_accounts_added).takeIf { account.origin == AccountOrigin.USER }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountLabels(account, kind = kind, digits = digits, transactions = transactions, addedByHand = addedByHand)
        Text(
            Formatters.money(account.balance),
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (account.balance.minor > 0) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

@Composable
private fun RowScope.AccountLabels(
    account: Account,
    kind: String,
    digits: String,
    transactions: String,
    addedByHand: String?,
) {
    val name = account.name
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (name != null) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(kind, digits.ifEmpty { null }, transactions, addedByHand).joinToString(SEPARATOR),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // accountLabel rather than the digits: the cash account has none, and
            // leading with an empty string leaves a row wearing only a balance.
            Text(
                accountLabel(account),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOf(stringResource(R.string.settings_accounts_unnamed), transactions).joinToString(SEPARATOR),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private const val SEPARATOR = " · "
