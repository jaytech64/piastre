// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.ReviewItem
import io.github.jaytech64.depenses.core.model.ReviewReason
import java.util.Locale

@Composable
fun ReviewScreen(viewModel: ReviewViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ReviewItem?>(null) }

    if (state.isEmpty) {
        EmptyReview()
    } else {
        ReviewList(
            items = state.items,
            onConfirm = viewModel::confirm,
            onEdit = { editing = it },
            onDismiss = viewModel::dismiss,
        )
    }

    editing?.let { item ->
        CorrectionDialog(
            item = item,
            onCancel = { editing = null },
            onSave = { amount, merchant ->
                viewModel.correct(item.transaction.id, amount, merchant)
                editing = null
            },
        )
    }
}

@Composable
private fun ReviewList(
    items: List<ReviewItem>,
    onConfirm: (Long) -> Unit,
    onEdit: (ReviewItem) -> Unit,
    onDismiss: (Long) -> Unit,
) {
    val locale = currentLocale()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.transaction.id }) { item ->
            ReviewCard(
                item = item,
                locale = locale,
                onConfirm = { onConfirm(item.transaction.id) },
                onEdit = { onEdit(item) },
                onDismiss = { onDismiss(item.transaction.id) },
            )
        }
    }
}

@Composable
private fun ReviewCard(
    item: ReviewItem,
    locale: Locale,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                item.provenance(locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            RawNotificationText(item)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.transaction.displayMerchant ?: stringResource(R.string.review_unknown_merchant),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Formatters.money(item.transaction.amount, locale),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Text(
                stringResource(item.reason.explanation()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onConfirm) { Text(stringResource(R.string.review_confirm)) }
                TextButton(onClick = onEdit) { Text(stringResource(R.string.review_edit)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_dismiss)) }
            }
        }
    }
}

/**
 * The notification exactly as it was posted, quoted rather than reformatted.
 *
 * This is the only thing on the account the parser did not produce, so it is the
 * only thing that can answer whether the parser was right. Showing it verbatim
 * is also what lets a correction become a parser fixture afterwards.
 */
@Composable
private fun RawNotificationText(item: ReviewItem) {
    val raw = listOfNotNull(item.rawTitle, item.rawText).filter { it.isNotBlank() }

    if (raw.isEmpty()) {
        Text(
            stringResource(R.string.review_raw_missing),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            raw.joinToString(RAW_SEPARATOR) { "« $it »" },
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun CorrectionDialog(
    item: ReviewItem,
    onCancel: () -> Unit,
    onSave: (Money, String?) -> Unit,
) {
    val locale = currentLocale()
    var amountText by remember(item.transaction.id) {
        mutableStateOf(Formatters.money(item.transaction.amount, locale))
    }
    var merchant by remember(item.transaction.id) {
        mutableStateOf(item.transaction.displayMerchant.orEmpty())
    }
    val amount = Formatters.parseMoney(amountText, item.transaction.amount.currency)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.review_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text(stringResource(R.string.review_amount)) },
                    isError = amount == null,
                    supportingText = {
                        if (amount == null) Text(stringResource(R.string.review_amount_invalid))
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text(stringResource(R.string.review_merchant)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { amount?.let { onSave(it, merchant.trim().ifEmpty { null }) } },
                enabled = amount != null,
            ) {
                Text(stringResource(R.string.review_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.review_cancel)) }
        },
    )
}

@Composable
private fun EmptyReview() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.review_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.review_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Which app posted the notification, and when — the account's provenance line. */
private fun ReviewItem.provenance(locale: Locale): String {
    val app = sourceName.uppercase(locale)
    val posted =
        postedAt?.let {
            Formatters.date(it.atZone(transaction.zoneId).toLocalDate(), locale) +
                SEPARATOR +
                Formatters.time(it, transaction.zoneId, locale)
        }
    return listOfNotNull(app, posted).joinToString(SEPARATOR)
}

/**
 * Null covers rows parsed before the reason was recorded, which is honest about
 * not knowing rather than inventing a reason the parser never gave.
 */
private fun ReviewReason?.explanation(): Int =
    when (this) {
        ReviewReason.UNRECOGNISED_FORMAT -> R.string.review_reason_unrecognised_format
        ReviewReason.MISSING_MERCHANT -> R.string.review_reason_missing_merchant
        ReviewReason.MISSING_SOURCE_KEY -> R.string.review_reason_missing_source_key
        null -> R.string.review_reason_unknown
    }

@Composable
private fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

private const val SEPARATOR = " · "
private const val RAW_SEPARATOR = "  "
