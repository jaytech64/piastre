// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.piastre.core.designsystem.ChoiceRow
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.DaySpan
import java.time.Year
import java.util.Locale

/**
 * Every merchant money went out at over a month or a year, ranked, each with
 * its visits and its average purchase. A merchant opens its transactions over
 * the same days through [onOpenTransactions], as a report's ranking does.
 *
 * Without Premium the page says so and shows nothing; Reports leads to the
 * Premium page instead of here.
 */
@Composable
fun MerchantsScreen(
    onOpenTransactions: (SpendingOf, DaySpan) -> Unit,
    viewModel: MerchantsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = currentLocale()
    if (state.today == null) return

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.premiumOn) {
            Quiet(stringResource(R.string.merchants_premium_off))
            return@Column
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            ChoiceRow(
                options = MerchantSpanUnit.entries,
                selected = state.span.unit,
                onSelect = viewModel::onUnitSelected,
                label = { stringResource(it.labelRes) },
            )
        }
        SpanStepper(state, locale, viewModel::onStepBack, viewModel::onStepForward)

        val ranking = state.ranking
        if (ranking.lines.isEmpty()) {
            Quiet(stringResource(R.string.merchants_nothing))
        } else {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.merchants_rank_by), style = MaterialTheme.typography.labelMedium)
                ChoiceRow(
                    options = MerchantRank.entries,
                    selected = state.rank,
                    onSelect = viewModel::onRankSelected,
                    label = { stringResource(it.labelRes) },
                )
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                ranking.lines.forEach { line ->
                    MerchantRow(line, locale) {
                        onOpenTransactions(SpendingOf.OneMerchant(line.merchantId), state.span)
                    }
                }
            }
        }
        if (ranking.withoutMerchant.minor != 0L) {
            val amount = Formatters.money(ranking.withoutMerchant, locale)
            Quiet(stringResource(R.string.merchants_without_merchant, amount))
        }
        Footnotes(state.otherCurrencyCount, state.needsReviewCount)
    }
}

@Composable
private fun SpanStepper(
    state: MerchantsUiState,
    locale: Locale,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
) {
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onStepBack) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.reports_previous),
            )
        }
        Text(
            when (state.span.unit) {
                MerchantSpanUnit.MONTH -> Formatters.monthYear(state.span.month, locale)
                MerchantSpanUnit.YEAR -> Formatters.year(Year.from(state.span.start), locale)
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onStepForward, enabled = state.canStepForward) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.reports_next),
            )
        }
    }
}

/**
 * A merchant, what went out there, a bar for its share of the span's spending,
 * and its visits and average under it. The bar is of the span, not of the
 * leader, for the reason a report's ranking gives.
 */
@Composable
private fun MerchantRow(
    line: MerchantLine,
    locale: Locale,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .clickable(
                onClickLabel = stringResource(R.string.merchants_see_transactions, line.name),
                role = Role.Button,
                onClick = onClick,
            ).heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                line.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                Formatters.money(line.total, locale),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { line.share },
            modifier = Modifier.fillMaxWidth(),
            drawStopIndicator = {},
        )
        Text(
            stringResource(
                R.string.merchants_visits_and_average,
                pluralStringResource(R.plurals.merchants_visits, line.visits, line.visits),
                stringResource(R.string.merchants_per_visit, Formatters.money(line.average, locale)),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Quiet(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

private val MerchantSpanUnit.labelRes: Int
    get() =
        when (this) {
            MerchantSpanUnit.MONTH -> R.string.merchants_span_month
            MerchantSpanUnit.YEAR -> R.string.merchants_span_year
        }

private val MerchantRank.labelRes: Int
    get() =
        when (this) {
            MerchantRank.TOTAL -> R.string.merchants_rank_total
            MerchantRank.VISITS -> R.string.merchants_rank_visits
            MerchantRank.AVERAGE -> R.string.merchants_rank_average
        }
