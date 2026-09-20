// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jaytech64.depenses.core.designsystem.Formatters
import java.util.Locale

/**
 * Where the period's money went, heaviest first.
 *
 * A purchase whose merchant never resolved is in the total above and absent
 * here, because a ranking cannot rank something with no name — so the bars are
 * shares of the period rather than of each other, and what is missing reads as
 * the gap they leave rather than as a contradiction.
 */
@Composable
internal fun MerchantRanking(
    merchants: List<MerchantShare>,
    locale: Locale,
) {
    if (merchants.isEmpty()) return

    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.reports_top_merchants),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        merchants.forEach { merchant ->
            MerchantRow(merchant, locale)
        }
    }
}

@Composable
private fun MerchantRow(
    merchant: MerchantShare,
    locale: Locale,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                merchant.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                Formatters.money(merchant.total, locale),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(
            progress = { merchant.share },
            modifier = Modifier.fillMaxWidth(),
            drawStopIndicator = {},
        )
    }
}
