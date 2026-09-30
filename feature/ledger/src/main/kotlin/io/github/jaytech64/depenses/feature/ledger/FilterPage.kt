// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Every filter on a page of its own, each applied the moment it is picked, the
 * way the account menu always was — there is no *Apply* to forget. The button
 * at the foot says how many transactions the filters leave, since the page
 * hides the list they narrow.
 *
 * A page rather than a sheet because a typed field in a modal sheet fights the
 * keyboard for the same half of the screen. The merchant list takes the whole
 * page in turn, because there can be hundreds of merchants and finding one
 * means typing.
 */
@Composable
internal fun FilterPage(
    state: LedgerUiState,
    actions: FilterActions,
    onDismiss: () -> Unit,
) {
    var choosingMerchant by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf<DateEnd?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SystemBarsReadOnSurface()
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (choosingMerchant) {
                    BackHandler { choosingMerchant = false }
                    Column(
                        Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        MerchantList(
                            options = state.merchants,
                            selected = state.filter.merchant,
                            onPick = {
                                actions.onMerchant(it)
                                choosingMerchant = false
                            },
                            onBack = { choosingMerchant = false },
                        )
                    }
                } else {
                    FilterTop(state.filter.isActive, actions.onClearAll, onClose = onDismiss)
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        FilterFields(
                            state,
                            actions,
                            onChooseMerchant = { choosingMerchant = true },
                            onPickDate = { pickingDate = it },
                        )
                    }
                    ShowButton(state.shownCount, onClick = onDismiss)
                }
                pickingDate?.let { end ->
                    DateEndPicker(end, state, actions, onClose = { pickingDate = null })
                }
            }
        }
    }
}

/**
 * A dialog has a window of its own, which does not inherit the activity's
 * choice of dark or light bar icons; without this they are drawn white on the
 * page's light surface.
 */
@Composable
private fun SystemBarsReadOnSurface() {
    val view = LocalView.current
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > HALF
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightSurface
                isAppearanceLightNavigationBars = lightSurface
            }
        }
    }
}

private const val HALF = 0.5f

@Composable
private fun FilterTop(
    isActive: Boolean,
    onClearAll: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ledger_filters_close))
        }
        Text(
            stringResource(R.string.ledger_filters_title),
            Modifier.weight(1f).padding(start = 8.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        if (isActive) {
            TextButton(onClick = onClearAll) { Text(stringResource(R.string.ledger_filters_clear_all)) }
        }
    }
}

@Composable
private fun FilterFields(
    state: LedgerUiState,
    actions: FilterActions,
    onChooseMerchant: () -> Unit,
    onPickDate: (DateEnd) -> Unit,
) {
    SectionTitle(R.string.ledger_filters_where)
    AccountFilterField(state.accounts, state.filter.account, actions.onAccount)
    CategoryFilterField(state.categories, state.filter.category, actions.onCategory)
    MerchantFilterField(state.filter.merchant, onChooseMerchant)

    SectionTitle(R.string.ledger_filters_when)
    DateFields(state.filter, onPickDate)

    SectionTitle(R.string.ledger_filters_how_much)
    AmountFields(state, actions)

    SectionTitle(R.string.ledger_filters_note)
    NoteField(state.typedNote, actions.onNote)
}

/**
 * Matched as the merchant search is, ignoring case and accents, so `cafe`
 * finds *Café*. Held here as well as in the view model, for the reason
 * [AmountFields] gives.
 */
@Composable
private fun NoteField(
    typed: String,
    onType: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(typed) }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onType(it)
        },
        label = { Text(stringResource(R.string.ledger_filter_note)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Closes the page, and says what it will leave on the ledger. Outlined when that
 * is nothing, so an empty result is noticed before it is shown.
 */
@Composable
private fun ShowButton(
    count: Int,
    onClick: () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().padding(16.dp)
    if (count == 0) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
        ) { Text(stringResource(R.string.ledger_filters_show_none)) }
    } else {
        Button(onClick = onClick, modifier = modifier) {
            Text(pluralStringResource(R.plurals.ledger_filters_show, count, count))
        }
    }
}

/** Which end of the filter's dates is being picked. */
internal enum class DateEnd { FROM, TO }
