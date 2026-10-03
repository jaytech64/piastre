// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The templates, over the form. Picking one fills the form with it and closes
 * the sheet; with none yet, it says how one is made.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateSheet(
    choice: TemplateChoice,
    onClose: () -> Unit,
) {
    val templates by choice.all.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.templates_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
            if (templates.isEmpty()) {
                Text(
                    stringResource(R.string.templates_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyColumn {
                items(templates, key = { it.id }) { template ->
                    TemplateRow(
                        template,
                        onClick = {
                            choice.apply(template)
                            onClose()
                        },
                    )
                }
            }
        }
    }
}
