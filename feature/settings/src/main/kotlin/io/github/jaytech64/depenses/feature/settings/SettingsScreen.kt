// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.rememberLongestWord
import io.github.jaytech64.depenses.core.model.CaptureHealth
import java.time.Instant

@Composable
fun SettingsScreen(
    onOpenRecurring: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    healthViewModel: CaptureHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val health by healthViewModel.uiState.collectAsStateWithLifecycle()
    val lastAction by viewModel.lastAction.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
        )

        SectionHeader(stringResource(R.string.settings_capture_section))

        SettingRow(
            title = stringResource(R.string.settings_capture_toggle),
            body = stringResource(R.string.settings_capture_toggle_body),
        ) {
            Switch(
                checked = state.captureEnabled,
                onCheckedChange = viewModel::setCaptureEnabled,
            )
        }

        health.health?.let { CaptureHealthRow(it, health.checkedAt) }

        SettingRow(
            title = stringResource(R.string.settings_raw_log),
            body = pluralStringResource(R.plurals.settings_raw_log_count, state.rawEventCount, state.rawEventCount),
        )

        AccountsSection()

        CategoriesSection()

        SettingRow(
            title = stringResource(R.string.settings_recurring),
            body =
                pluralStringResource(
                    R.plurals.settings_recurring_active,
                    state.recurringCount,
                    state.recurringCount,
                ),
            onClick = onOpenRecurring,
        )

        DataSection(lastAction = lastAction, viewModel = viewModel)

        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        Text(
            stringResource(R.string.settings_no_network),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        )
    }
}

/**
 * The health headline and the moment behind it, stated even when all is well.
 *
 * The banner only appears when something is broken, which leaves no way to tell
 * a working capture from one whose alarm has itself failed. This line is that
 * way — it is the screen you come to precisely when you are not sure.
 */
@Composable
private fun CaptureHealthRow(
    health: CaptureHealth,
    checkedAt: Instant,
) {
    val headline =
        when (health) {
            is CaptureHealth.Listening -> stringResource(R.string.health_listening)
            is CaptureHealth.Paused -> stringResource(R.string.health_paused)
            is CaptureHealth.AccessRevoked, is CaptureHealth.Silent -> stringResource(R.string.health_interrupted)
        }
    val color =
        if (health.needsAttention) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(headline, style = MaterialTheme.typography.bodyLarge, color = color)
        Text(
            health.lastSeen(checkedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SectionHeader(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
    )
}

/**
 * A setting's title and explanation, with its control beside them — or under
 * them, when beside them would leave a word no room to stay whole. At a large
 * font size « Choisir un fichier… » otherwise squeezes « Importer » into
 * « Import/er » and its explanation into one word per line.
 */
@Composable
internal fun SettingRow(
    title: String,
    body: String,
    titleColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val longestWord =
        maxOf(
            rememberLongestWord(title, MaterialTheme.typography.bodyLarge),
            rememberLongestWord(body, MaterialTheme.typography.bodySmall),
        )
    val texts = @Composable { SettingTexts(title, body, titleColor) }
    SubcomposeLayout(
        Modifier
            .fillMaxWidth()
            .then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val control = trailing?.let { subcompose(Slot.CONTROL, it).map { m -> m.measure(loose) } }.orEmpty()
        val controlWidth = control.maxOfOrNull { it.width } ?: 0
        val gap = CONTROL_GAP.roundToPx()
        val beside = control.isEmpty() || constraints.maxWidth - controlWidth - gap >= longestWord
        val textWidth =
            if (beside &&
                control.isNotEmpty()
            ) {
                constraints.maxWidth - controlWidth - gap
            } else {
                constraints.maxWidth
            }
        val text = subcompose(Slot.TEXT, texts).map { it.measure(loose.copy(maxWidth = textWidth)) }
        val textHeight = text.sumOf { it.height }
        val controlHeight = control.sumOf { it.height }
        val height = if (beside) maxOf(textHeight, controlHeight) else textHeight + gap + controlHeight
        layout(constraints.maxWidth, height) {
            if (beside) {
                text.forEach { it.placeRelative(0, (height - textHeight) / 2) }
                control.forEach { it.placeRelative(constraints.maxWidth - it.width, (height - it.height) / 2) }
            } else {
                text.forEach { it.placeRelative(0, 0) }
                control.forEach { it.placeRelative(0, textHeight + gap) }
            }
        }
    }
}

@Composable
private fun SettingTexts(
    title: String,
    body: String,
    titleColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private enum class Slot { TEXT, CONTROL }

private val CONTROL_GAP = 16.dp
