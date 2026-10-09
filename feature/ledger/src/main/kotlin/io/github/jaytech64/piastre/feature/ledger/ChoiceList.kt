// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jaytech64.piastre.core.model.Merchant
import io.github.jaytech64.piastre.core.model.containsTyped

@Composable
internal fun MerchantList(
    options: List<FilterOption<Merchant>>,
    selected: Merchant?,
    onPick: (Long?) -> Unit,
    onBack: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val found = remember(options, typed) { options.filter { it.value.displayName.containsTyped(typed) } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ledger_filter_back))
        }
        Text(stringResource(R.string.ledger_filter_merchant), style = MaterialTheme.typography.titleLarge)
    }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        placeholder = { Text(stringResource(R.string.ledger_filter_merchant_search)) },
        modifier = Modifier.fillMaxWidth(),
    )
    LazyColumn {
        item(key = "all") {
            ChoiceLine(stringResource(R.string.ledger_filter_all_merchants), selected == null, aside = null) {
                onPick(null)
            }
        }
        items(found, key = { it.value.id }) { option ->
            ChoiceLine(option.value.displayName, selected?.id == option.value.id, aside = option.count.toString()) {
                onPick(option.value.id)
            }
        }
    }
}

/** One line of a list you pick from: the mark, the name, and a figure beside it. */
@Composable
internal fun ChoiceLine(
    name: String,
    selected: Boolean,
    aside: String?,
    color: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SelectedMark(selected)
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = color)
        aside?.let { Aside(it) }
    }
}

@Composable
internal fun Aside(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Always occupies the slot, so the names line up whichever row is checked. */
@Composable
internal fun SelectedMark(selected: Boolean) {
    if (selected) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            Modifier.size(MARK_SIZE),
            tint = MaterialTheme.colorScheme.primary,
        )
    } else {
        Spacer(Modifier.size(MARK_SIZE))
    }
}

/** Material's standard icon size, so a checked line and an unchecked one start their names at the same place. */
private val MARK_SIZE = 24.dp
