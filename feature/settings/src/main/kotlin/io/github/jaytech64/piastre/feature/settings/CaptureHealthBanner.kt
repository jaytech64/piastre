// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.piastre.core.model.CaptureHealth
import java.time.Duration
import java.time.Instant

/**
 * The alarm for the one failure no other screen can show.
 *
 * It sits above every destination rather than on the ledger alone, because the
 * thing it reports — nothing is being captured — makes *all* the numbers in the
 * app quietly wrong, not just the ones on one screen. It renders nothing while
 * capture is working, which is nearly always.
 *
 * It can be collapsed to a strip but never dismissed (ADR-0004): the strip keeps
 * the alarm and the fix, and gives up only the explanation. The collapse lives in
 * composition, not on disk, so every launch raises the alarm in full again, and
 * it is keyed to the kind of failure, so a verdict that changes — silence turning
 * into revoked access — is news and opens the banner back up.
 */
@Composable
fun CaptureHealthBanner(
    modifier: Modifier = Modifier,
    viewModel: CaptureHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    val health = state.health
    if (health == null || !health.needsAttention) return

    var collapsed by rememberSaveable(health::class.simpleName) { mutableStateOf(false) }
    val context = LocalContext.current
    val reactivate = { context.startActivity(viewModel.notificationAccessIntent()) }

    Card(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
    ) {
        if (collapsed) {
            CollapsedBanner(onReactivate = reactivate, onExpand = { collapsed = false })
        } else {
            ExpandedBanner(
                explanation = health.explanation(state.checkedAt),
                onReactivate = reactivate,
                onCollapse = { collapsed = true },
            )
        }
    }
}

@Composable
private fun ExpandedBanner(
    explanation: String,
    onReactivate: () -> Unit,
    onCollapse: () -> Unit,
) {
    Column(
        Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Text(
                stringResource(R.string.health_interrupted),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCollapse) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.health_collapse))
            }
        }
        Column(Modifier.padding(end = 12.dp)) {
            Text(explanation, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onReactivate) {
                Text(stringResource(R.string.health_reactivate))
            }
        }
    }
}

/**
 * The alarm and the fix on one line, tapping anywhere else on it opens it back
 * up. When the title needs the whole width — French at a large font size — the
 * fix moves to a line of its own rather than squeezing the title until a word
 * breaks in half.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollapsedBanner(
    onReactivate: () -> Unit,
    onExpand: () -> Unit,
) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.health_expand), onClick = onExpand)
            .padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Text(stringResource(R.string.health_interrupted), style = MaterialTheme.typography.titleSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReactivate) {
                Text(stringResource(R.string.health_reactivate))
            }
            IconButton(onClick = onExpand) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.health_expand))
            }
        }
    }
}

/** Why the banner is up, in the words that tell you what to do about it. */
@Composable
internal fun CaptureHealth.explanation(checkedAt: Instant): String =
    when (this) {
        is CaptureHealth.AccessRevoked -> {
            stringResource(R.string.health_revoked_body)
        }

        is CaptureHealth.Paused -> {
            stringResource(R.string.health_paused_body)
        }

        is CaptureHealth.Silent -> {
            stringResource(R.string.health_silent_body, elapsedSince(lastEventAt, checkedAt))
        }

        is CaptureHealth.Listening -> {
            lastSeen(checkedAt)
        }
    }

/**
 * When the app last heard anything, stated whatever the verdict is.
 *
 * The settings screen shows this under its headline rather than repeating
 * [explanation]: the banner is already on that screen saying why, and one
 * paragraph printed twice reads as a bug rather than as emphasis.
 */
@Composable
internal fun CaptureHealth.lastSeen(checkedAt: Instant): String =
    lastEventAt
        ?.let { stringResource(R.string.health_last_event, elapsedSince(it, checkedAt)) }
        ?: stringResource(R.string.health_never)

/**
 * "3 days ago", in the locale actually on screen.
 *
 * `DateUtils.getRelativeTimeSpanString` does this in one line but reads the
 * process default locale, which is the wrong one the moment a per-app language
 * is set — the same reason every other formatter in this app is handed its
 * locale rather than finding one.
 */
@Composable
private fun elapsedSince(
    moment: Instant,
    now: Instant,
): String {
    val minutes = Duration.between(moment, now).toMinutes().coerceAtLeast(0)
    return when {
        minutes == 0L -> {
            stringResource(R.string.health_ago_now)
        }

        minutes < MINUTES_PER_HOUR -> {
            pluralStringResource(R.plurals.health_ago_minutes, minutes.toInt(), minutes)
        }

        minutes < MINUTES_PER_DAY -> {
            (minutes / MINUTES_PER_HOUR).toInt().let { pluralStringResource(R.plurals.health_ago_hours, it, it) }
        }

        else -> {
            (minutes / MINUTES_PER_DAY).toInt().let { pluralStringResource(R.plurals.health_ago_days, it, it) }
        }
    }
}

private const val MINUTES_PER_HOUR = 60L
private const val MINUTES_PER_DAY = 60L * 24
