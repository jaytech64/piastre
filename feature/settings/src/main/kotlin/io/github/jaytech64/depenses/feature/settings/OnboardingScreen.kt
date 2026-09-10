// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.model.CaptureHealth

/**
 * The first screen, and the only place the app asks for anything.
 *
 * Notification access reads as an alarming permission — and it is one — so this
 * says what is read, what is kept and where it stays before sending anyone to
 * the system screen that grants it. Under Law 25 that explanation is the point
 * of the screen, not decoration on a button.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: CaptureHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    // Granting happens in another app, so the only signal that it worked is the
    // health state changing while this screen is in the background.
    val granted = state.health?.let { it !is CaptureHealth.AccessRevoked } == true
    LaunchedEffect(granted) {
        if (granted) onDone()
    }

    Column(
        Modifier
            .fillMaxSize()
            // The only screen outside the Scaffold, so it insets itself: the app
            // draws edge to edge and the title otherwise sits under the clock.
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The promises scroll and the buttons do not: at the largest system font
        // the text runs off a phone screen, and an "allow" button you have to go
        // looking for is how people end up granting without reading.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
            )

            Promise(
                title = stringResource(R.string.onboarding_capture_title),
                body = stringResource(R.string.onboarding_capture_body),
            )
            Promise(
                title = stringResource(R.string.onboarding_private_title),
                body = stringResource(R.string.onboarding_private_body),
            )
            Promise(
                title = stringResource(R.string.onboarding_control_title),
                body = stringResource(R.string.onboarding_control_body),
            )
        }

        Button(
            onClick = { context.startActivity(viewModel.notificationAccessIntent()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_grant))
        }
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.onboarding_later))
        }
    }
}

@Composable
private fun Promise(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
