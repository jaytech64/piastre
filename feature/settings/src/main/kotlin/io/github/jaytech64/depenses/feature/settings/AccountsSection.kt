// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    val reorder = remember(viewModel) { AccountReorder(onMove = viewModel::move) }
    LaunchedEffect(accounts) { reorder.settled() }
    val shown = reorder.shown(accounts)
    shown.forEachIndexed { index, account ->
        key(account.id) {
            AccountRow(
                account,
                place = AccountPlace(index = index, last = shown.lastIndex),
                reorder = reorder,
                onDragStart = { reorder.start(accounts, account.id) },
                onMove = { to -> viewModel.move(account, to) },
                onClick = { editing = AccountEdit.Existing(account) },
            )
        }
    }

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

/** Where a row sits in the list, which decides whether it can move up or down. */
private data class AccountPlace(
    val index: Int,
    val last: Int,
)

/**
 * A named account leads with its name; one still waiting for a name leads with how
 * Wallet shows it, and says it can be named. Either ends with what it holds now,
 * which carries the income colour when it is above zero.
 *
 * The handle in front moves the account: hold it, then drag. Only the handle
 * drags, so scrolling Settings never moves an account by accident. TalkBack cannot
 * drag, so the row also offers moving up and down as actions of its own.
 */
@Composable
private fun AccountRow(
    account: Account,
    place: AccountPlace,
    reorder: AccountReorder,
    onDragStart: () -> Unit,
    onMove: (Int) -> Unit,
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

    val moveUp = stringResource(R.string.settings_accounts_move_up)
    val moveDown = stringResource(R.string.settings_accounts_move_down)
    val dragged = reorder.draggedId == account.id

    Row(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { reorder.measured(account.id, it.height) }
            .zIndex(if (dragged) 1f else 0f)
            .graphicsLayer {
                translationY = if (dragged) reorder.offset else 0f
                shadowElevation = if (dragged) DRAGGED_ELEVATION.toPx() else 0f
                shape = RoundedCornerShape(DRAGGED_CORNER)
                clip = dragged
            }.background(if (dragged) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                customActions =
                    listOfNotNull(
                        CustomAccessibilityAction(moveUp) { true.also { onMove(place.index - 1) } }
                            .takeIf { place.index > 0 },
                        CustomAccessibilityAction(moveDown) { true.also { onMove(place.index + 1) } }
                            .takeIf { place.index < place.last },
                    )
            }.padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DragHandle(reorder, onDragStart)
        AccountLabels(account, kind = kind, digits = digits, transactions = transactions, addedByHand = addedByHand)
        Spacer(Modifier.width(16.dp))
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
private fun DragHandle(
    reorder: AccountReorder,
    onDragStart: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // The gesture outlives recomposition, so it reads the latest list through this
    // rather than the one it was first composed with.
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    Box(
        Modifier
            .size(HANDLE_WIDTH)
            .pointerInput(reorder) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnDragStart()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        reorder.drag(amount.y)
                    },
                    onDragEnd = reorder::end,
                    onDragCancel = reorder::end,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_drag_handle),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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

private val HANDLE_WIDTH = 48.dp
private val DRAGGED_ELEVATION = 8.dp
private val DRAGGED_CORNER = 12.dp
