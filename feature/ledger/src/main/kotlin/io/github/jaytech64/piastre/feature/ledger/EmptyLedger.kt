// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jaytech64.piastre.core.model.CaptureHealth

/**
 * What an empty ledger can truthfully say about the next payment, or null until
 * capture has answered. A stalled listener keeps the usual words because the
 * banner above every screen already says it has stopped (ADR-0004).
 */
@StringRes
internal fun emptyLedgerBody(health: CaptureHealth?): Int? =
    when (health) {
        null -> null
        is CaptureHealth.Paused -> R.string.ledger_empty_body_paused
        is CaptureHealth.AccessNeverGranted, is CaptureHealth.AccessRevoked -> R.string.ledger_empty_body_no_access
        is CaptureHealth.Listening, is CaptureHealth.Silent -> R.string.ledger_empty_body
    }

/**
 * An empty ledger and an empty filter are different facts. Telling someone who
 * narrowed the screen that a Wallet payment will appear on its own answers a
 * question they did not ask.
 */
@Composable
internal fun EmptyLedger(
    filter: LedgerFilter,
    captureHealth: CaptureHealth?,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 32.dp, top = 32.dp, end = 32.dp, bottom = ADD_BUTTON_CLEARANCE),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (filter.isActive) {
            Text(
                stringResource(R.string.ledger_empty_filtered),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        Text(
            stringResource(R.string.ledger_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        emptyLedgerBody(captureHealth)?.let { body ->
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
