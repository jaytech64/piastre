// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.premium

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The moment the notification permission is asked: once a trial has started,
 * when its reason is plain. A refusal is answered with where to change it, and
 * never asked again in-app.
 */
@Composable
internal fun TrialReminderCard() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.notificationsGranted()) }
    var refused by remember { mutableStateOf(false) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
            granted = allowed
            refused = !allowed
        }
    if (granted) return
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.premium_remind_title), style = MaterialTheme.typography.titleMedium)
            if (refused) {
                Quiet(stringResource(R.string.premium_remind_refused))
                TextButton(onClick = { context.startActivity(context.notificationSettings()) }) {
                    Text(stringResource(R.string.premium_remind_settings))
                }
            } else {
                Quiet(stringResource(R.string.premium_remind_body))
                Button(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                    Text(stringResource(R.string.premium_remind_allow))
                }
            }
        }
    }
}
