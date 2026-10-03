// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.depenses.core.designsystem.accountLabel
import io.github.jaytech64.depenses.core.model.Category
import io.github.jaytech64.depenses.core.model.TransactionKind
import java.text.Collator

/** The category field, and the offer under it when there is something to offer. */
@Composable
internal fun CategorySection(
    selectedId: Long?,
    picker: CategoryPicker,
    offers: Boolean,
    onAddCategory: () -> Unit,
) {
    val categories by picker.known.collectAsStateWithLifecycle()
    val recent by picker.recent.collectAsStateWithLifecycle()
    val groups by picker.groups.collectAsStateWithLifecycle()
    val offer by picker.offer.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val sections =
        remember(categories, groups, locale) { categorySections(categories, groups, Collator.getInstance(locale)) }
    CategoryField(selectedId, categories, recent, sections, onPick = picker::onPick, onAddCategory = onAddCategory)
    offer?.takeIf { offers }?.let {
        CategoryOfferBoxes(it, onOthersChange = picker::onOthersChange, onFromNowOnChange = picker::onFromNowOnChange)
    }
}

/**
 * The category, picked from yours — the few used most lately for this kind
 * first, then every one under its group — or added from the last item without
 * leaving the form. A category used lately is in the list twice, at the top and
 * under its group, so the list reads the same whatever you picked last.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryField(
    selectedId: Long?,
    categories: List<Category>,
    recent: List<Category>,
    sections: List<CategorySection>,
    onPick: (Long?) -> Unit,
    onAddCategory: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    fun pick(id: Long?) {
        onPick(id)
        expanded = false
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value =
                categories.firstOrNull { it.id == selectedId }?.name
                    ?: stringResource(R.string.form_category_none),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.form_category)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.form_category_none)) }, onClick = { pick(null) })
            if (recent.isNotEmpty()) {
                MenuHeading(stringResource(R.string.form_category_recent))
                recent.forEach { category ->
                    DropdownMenuItem(text = { Text(category.name) }, onClick = { pick(category.id) })
                }
            }
            sections.forEach { section ->
                // With no group at all, a heading over the one list would say nothing.
                if (section.group != null || sections.size > 1) {
                    MenuHeading(section.group?.name ?: stringResource(R.string.form_category_ungrouped))
                }
                section.categories.forEach { category ->
                    DropdownMenuItem(text = { Text(category.name) }, onClick = { pick(category.id) })
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.form_add_category), color = MaterialTheme.colorScheme.primary) },
                onClick = {
                    expanded = false
                    onAddCategory()
                },
            )
        }
    }
}

/** A heading among a menu's items, which arranges them and is not itself picked. */
@Composable
internal fun MenuHeading(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
    )
}

/**
 * The two boxes under the category field: the place's other transactions with
 * no category, drawn only when there are some, and everything recorded there
 * from now on. Nothing happens until Save.
 */
@Composable
private fun CategoryOfferBoxes(
    offer: CategoryOffer,
    onOthersChange: (Boolean) -> Unit,
    onFromNowOnChange: (Boolean) -> Unit,
) {
    val place =
        when (val name = offer.place) {
            is PlaceName.AtMerchant -> name.name
            is PlaceName.IntoAccount -> accountLabel(name.account)
        }
    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (offer.othersCount > 0) {
            OfferBox(
                label = pluralStringResource(offer.kind.othersLabel(), offer.othersCount, offer.othersCount, place),
                checked = offer.others,
                onChange = onOthersChange,
            )
        }
        val fromNowOn = stringResource(offer.kind.fromNowOnLabel(), place)
        OfferBox(
            label =
                offer.replacing?.let { stringResource(R.string.category_offer_instead_of, fromNowOn, it) }
                    ?: fromNowOn,
            checked = offer.fromNowOn,
            onChange = onFromNowOnChange,
        )
    }
}

/** The whole row toggles, so the label is as much a target as the box. */
@Composable
internal fun OfferBox(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Checkbox),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun TransactionKind.othersLabel(): Int =
    when (this) {
        TransactionKind.EXPENSE -> R.plurals.category_offer_others_expense
        TransactionKind.INCOME -> R.plurals.category_offer_others_income
        TransactionKind.TRANSFER -> R.plurals.category_offer_others_transfer
    }

private fun TransactionKind.fromNowOnLabel(): Int =
    when (this) {
        TransactionKind.EXPENSE -> R.string.category_offer_from_now_on_expense
        TransactionKind.INCOME -> R.string.category_offer_from_now_on_income
        TransactionKind.TRANSFER -> R.string.category_offer_from_now_on_transfer
    }
