// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.CaptureHistory
import java.time.Instant
import java.time.LocalDate

/** Every time Wallet reported this purchase, the first one named as such. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureHistorySheet(
    history: CaptureHistory,
    onClose: () -> Unit,
) {
    val today = remember(history.zoneId) { LocalDate.now(history.zoneId) }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.capture_history_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
            if (history.posts.isEmpty()) {
                Text(
                    stringResource(R.string.capture_history_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyColumn {
                itemsIndexed(history.posts) { index, post ->
                    CapturePostRow(isFirst = index == 0, post, history, today)
                }
            }
        }
    }
}

/** Wraps rather than squeezes, so a French label at 200 % never breaks mid-word against its time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapturePostRow(
    isFirst: Boolean,
    post: Instant,
    history: CaptureHistory,
    today: LocalDate,
) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(if (isFirst) R.string.capture_history_first else R.string.capture_history_update),
            style = MaterialTheme.typography.bodyLarge,
            color = if (isFirst) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        val locale = currentLocale()
        Text(
            stringResource(
                R.string.capture_history_moment,
                Formatters.shortDate(post.atZone(history.zoneId).toLocalDate(), today, locale),
                Formatters.time(post, history.zoneId, locale),
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
