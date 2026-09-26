// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.Category
import io.github.jaytech64.depenses.core.model.Merchant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The category part of the expense form: the list it is picked from, the choice,
 * and the offer beside it. It reads and writes the form's own state, so a save
 * sees one form; it is a class of its own only so the form's view model stays
 * about the transaction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryPicker internal constructor(
    private val form: MutableStateFlow<ExpenseFormUiState>,
    private val merchants: StateFlow<List<Merchant>>,
    private val accounts: StateFlow<List<Account>>,
    private val categories: CategoryRepository,
    editingId: Long?,
    today: () -> LocalDate,
    private val scope: CoroutineScope,
    sharing: SharingStarted,
) {
    /** Every category, the ones you use most for the kind being entered first. */
    val known: StateFlow<List<Category>> =
        form
            .map { it.kind }
            .distinctUntilChanged()
            .flatMapLatest { kind -> categories.byUse(kind, today()) }
            .stateIn(scope, sharing, emptyList())

    private val place =
        combine(form, merchants, accounts) { state, merchants, accounts -> state.place(merchants, accounts) }
            .distinctUntilChanged()

    /** Kept current while the form is open, so the number beside the first box is what saving would change. */
    private val othersCount =
        combine(place.map { it?.anchor }, form.map { it.kind }) { anchor, kind -> anchor to kind }
            .distinctUntilChanged()
            .flatMapLatest { (anchor, kind) ->
                anchor?.let { categories.uncategorisedAt(it, kind, editingId) } ?: flowOf(0)
            }

    /** The two checkboxes under the category field, or null when there is nothing to offer. */
    val offer: StateFlow<CategoryOffer?> =
        combine(form, place, othersCount, known) { state, place, count, known -> state.offer(place, count, known) }
            .stateIn(scope, sharing, null)

    /** Null is None, which is a choice too: naming a merchant no longer fills it in. */
    fun onPick(id: Long?) =
        form.update { it.copy(categoryId = id, categoryChosen = true, reachOthers = false, fromNowOnChoice = null) }

    fun onOthersChange(ticked: Boolean) = form.update { it.copy(reachOthers = ticked) }

    fun onFromNowOnChange(ticked: Boolean) = form.update { it.copy(fromNowOnChoice = ticked) }

    /** Adds the category missing from the list and picks it, or picks the one you already have under that name. */
    fun add(name: String) {
        scope.launch { categories.named(name)?.let(::onPick) }
    }

    /**
     * [moved] — the form after a change to what it is about: the merchant, the
     * kind, or where a transfer goes — following the new place's category while
     * you have not chosen one yourself. The offer's boxes start over, since they
     * were about the place before.
     */
    internal fun following(moved: ExpenseFormUiState): ExpenseFormUiState {
        val fresh = moved.copy(reachOthers = false, fromNowOnChoice = null)
        if (fresh.categoryChosen) return fresh
        return fresh.copy(categoryId = fresh.place(merchants.value, accounts.value)?.defaultCategoryId)
    }
}
