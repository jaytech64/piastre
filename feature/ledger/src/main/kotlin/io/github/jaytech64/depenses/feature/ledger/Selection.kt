// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.designsystem.rememberLongestWord

/**
 * How a row stands while the ledger is selecting: not at all, picked, or
 * selectable but not picked.
 */
internal enum class RowSelection { NONE, SELECTED, UNSELECTED }

/** Which sheet or dialog is over the selection, if any. */
internal enum class SelectionAction { NONE, CATEGORISE, ADD_CATEGORY, DELETE }

/**
 * The balance card's place while rows are selected: how many, what they add up
 * to, and what can be done to them.
 *
 * It takes the card's shape — the net, then the figures it is made of — so the
 * list does not slide up under the finger that started the selection.
 */
@Composable
internal fun SelectionBar(
    count: Int,
    summary: BalanceSummary,
    onEnd: () -> Unit,
    onAct: (SelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    Card(
        modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(top = 4.dp, bottom = 16.dp)) {
            SelectionControls(count, onEnd, onAct)
            Column(
                Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(Formatters.signedMoney(summary.balance, locale), style = MaterialTheme.typography.displaySmall)
                HorizontalDivider(
                    Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                SummaryFigures(summary, locale)
            }
        }
    }
}

/**
 * Close, the count, and the actions. The count sits between the buttons while
 * its longest word fits there, and takes a line of its own above them when it
 * does not: at 200 % in French the space between them cut « sélectionnées » in
 * two.
 */
@Composable
private fun SelectionControls(
    count: Int,
    onEnd: () -> Unit,
    onAct: (SelectionAction) -> Unit,
) {
    val label = pluralStringResource(R.plurals.ledger_selected, count, count)
    val style = MaterialTheme.typography.titleMedium
    val longestWord = rememberLongestWord(label, style)
    BoxWithConstraints(Modifier.padding(horizontal = 4.dp)) {
        val besideButtons = maxWidth - BUTTON_SIZE * BUTTON_COUNT - LABEL_PADDING * 2
        val countLabel = @Composable { modifier: Modifier ->
            Text(label, modifier.padding(horizontal = LABEL_PADDING), style = style)
        }
        if (with(LocalDensity.current) { besideButtons.roundToPx() } >= longestWord) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EndButton(onEnd)
                countLabel(Modifier.weight(1f))
                ActionButtons(onAct)
            }
        } else {
            Column {
                countLabel(Modifier.padding(top = LABEL_PADDING))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EndButton(onEnd)
                    Spacer(Modifier.weight(1f))
                    ActionButtons(onAct)
                }
            }
        }
    }
}

@Composable
private fun EndButton(onEnd: () -> Unit) {
    IconButton(onClick = onEnd) {
        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ledger_selection_end))
    }
}

@Composable
private fun ActionButtons(onAct: (SelectionAction) -> Unit) {
    IconButton(onClick = { onAct(SelectionAction.CATEGORISE) }) {
        Icon(
            painterResource(R.drawable.ic_label),
            contentDescription = stringResource(R.string.ledger_selection_categorise),
        )
    }
    IconButton(onClick = { onAct(SelectionAction.DELETE) }) {
        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.edit_delete))
    }
}

/**
 * Selects every row on screen. It heads the rows, under what is still to come,
 * the size of a row's mark and starting where one does, so it reads as the
 * mark of all of them; the padding is what its touch target takes beyond the
 * circle.
 *
 * Not [enabled] once every row is selected, when it has nothing left to add.
 */
@Composable
internal fun SelectAllButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier =
            Modifier
                .padding(start = ROW_PADDING - (BUTTON_SIZE - ROW_MARK) / 2)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .semantics { role = Role.Button },
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Icon(
            painterResource(R.drawable.ic_done_all),
            contentDescription = stringResource(R.string.ledger_selection_all),
            Modifier
                .size(ROW_MARK)
                .padding(8.dp),
        )
    }
}

/**
 * The categories the selection can be given. A category every selected row
 * already carries is checked, and one only some of them carry says how many, so
 * a pick shows what it replaces before it is made — there is no undo after.
 * A split among them was divided by hand, so no pick reaches it, and the sheet
 * says how many will stay as they are.
 *
 * Picking applies at once, as the filter sheet does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionCategorySheet(
    count: Int,
    splitCount: Int,
    categories: List<SelectionCategory>,
    onPick: (Long?) -> Unit,
    onAddCategory: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (count == 1) {
                    stringResource(R.string.ledger_selection_category_title_single)
                } else {
                    pluralStringResource(R.plurals.ledger_selection_category_title_several, count, count)
                },
                style = MaterialTheme.typography.titleLarge,
            )
            if (splitCount > 0) {
                Text(
                    pluralStringResource(R.plurals.ledger_selection_splits_stay, splitCount, splitCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn {
                items(categories, key = { it.category?.id ?: NO_CATEGORY_KEY }) { option ->
                    ChoiceLine(
                        name = option.category?.name ?: stringResource(R.string.ledger_filter_no_category),
                        selected = option.selectedCount == count,
                        aside = option.selectedCount.takeIf { it in 1 until count }?.toString(),
                    ) { onPick(option.category?.id) }
                }
                item(key = "add") {
                    HorizontalDivider()
                    ChoiceLine(
                        name = stringResource(R.string.form_add_category),
                        selected = false,
                        aside = null,
                        color = MaterialTheme.colorScheme.primary,
                        onClick = onAddCategory,
                    )
                }
            }
        }
    }
}

/** Deleting asks first, as the form does: nothing on this screen brings a row back. */
@Composable
internal fun ConfirmSelectionDeleteDialog(
    count: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (count == 1) {
                    stringResource(R.string.ledger_selection_delete_title_single)
                } else {
                    pluralStringResource(R.plurals.ledger_selection_delete_title_several, count, count)
                },
            )
        },
        text = {
            Text(
                stringResource(
                    if (count ==
                        1
                    ) {
                        R.string.ledger_selection_delete_body_single
                    } else {
                        R.string.ledger_selection_delete_body_several
                    },
                ),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.edit_delete)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.manual_cancel)) } },
    )
}

/** Material's minimum touch target, which an [IconButton] takes. */
private val BUTTON_SIZE = 48.dp

/** Close, categorise and delete. */
private const val BUTTON_COUNT = 3

private val LABEL_PADDING = 8.dp

/** Where a transaction row starts, and the circle it starts with. */
private val ROW_PADDING = 16.dp
private val ROW_MARK = 36.dp

/** Material's opacity for a disabled control. */
private const val DISABLED_ALPHA = 0.38f

/** A key no category id can take: ids start at 1. */
private const val NO_CATEGORY_KEY = -1L
