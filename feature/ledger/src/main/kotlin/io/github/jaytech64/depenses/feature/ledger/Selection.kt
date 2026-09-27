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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.rememberLongestWord

/**
 * How a row stands while the ledger is selecting: not at all, picked, or
 * selectable but not picked.
 */
internal enum class RowSelection { NONE, SELECTED, UNSELECTED }

/** Which sheet or dialog is over the selection, if any. */
internal enum class SelectionAction { NONE, CATEGORISE, ADD_CATEGORY, DELETE }

/**
 * The balance card's place while rows are selected: how many, and what can be
 * done to them.
 *
 * The count sits between the buttons while its longest word fits there, and
 * takes a line of its own above them when it does not: at 200 % in French the
 * space between them cut « sélectionnées » in two.
 */
@Composable
internal fun SelectionBar(
    count: Int,
    onEnd: () -> Unit,
    onAct: (SelectionAction) -> Unit,
) {
    val label = pluralStringResource(R.plurals.ledger_selected, count, count)
    val style = MaterialTheme.typography.titleLarge
    val longestWord = rememberLongestWord(label, style)
    Card(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        BoxWithConstraints(Modifier.padding(4.dp)) {
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
 * The categories the selection can be given. A category every selected row
 * already carries is checked, and one only some of them carry says how many, so
 * a pick shows what it replaces before it is made — there is no undo after.
 *
 * Picking applies at once, as the filter sheet does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionCategorySheet(
    count: Int,
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

/** A key no category id can take: ids start at 1. */
private const val NO_CATEGORY_KEY = -1L
