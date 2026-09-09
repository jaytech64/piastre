// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.model.CaptureHealth
import java.time.Instant

@Composable
fun SettingsScreen(
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

@Composable
internal fun SettingRow(
    title: String,
    body: String,
    titleColor: Color = Color.Unspecified,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}
