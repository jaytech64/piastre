// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import kotlin.math.abs

/**
 * The navigation arguments of a ledger opened on something a report showed:
 * which of [OPENED_ON_CATEGORY], [OPENED_ON_GROUP] or [OPENED_ON_NO_CATEGORY]
 * it is narrowed to, the id of that category or group, and its first and last
 * day. A ledger opened without them starts unfiltered, as the tab does.
 */
const val OPENED_ON_ARG = "openedOn"
const val OPENED_ID_ARG = "openedId"
const val OPENED_FROM_ARG = "openedFromEpochDay"
const val OPENED_TO_ARG = "openedToEpochDay"
const val OPENED_ON_CATEGORY = "category"
const val OPENED_ON_GROUP = "group"
const val OPENED_ON_NO_CATEGORY = "none"

/**
 * What the filter controls last set, as saved-state keys. A class of its own,
 * as [LedgerSelection] is, so the ledger's view model stays about what the
 * ledger shows; the view model resolves these keys against what exists.
 */
class LedgerFilters internal constructor(
    private val savedState: SavedStateHandle,
) {
    /**
     * The filter lives in saved state rather than in a field, because the bottom
     * bar navigates with `popUpTo(saveState = true)`: that clears the view model
     * scoped to the ledger entry, so a field would drop the filter on the way to
     * Reports and back. The saved-state bundle survives that and a rotation,
     * while a cold launch starts without one — which is the lifetime the filter
     * was asked for.
     */
    internal val keys: Flow<FilterKeys> =
        combine(
            combine(
                savedState.getStateFlow<Long?>(SELECTED_ACCOUNT_ID, null),
                savedState.getStateFlow<Long?>(SELECTED_CATEGORY_ID, null),
                savedState.getStateFlow<Long?>(SELECTED_CATEGORY_GROUP_ID, null),
                savedState.getStateFlow(UNCATEGORISED_ONLY, false),
                savedState.getStateFlow<Long?>(SELECTED_MERCHANT_ID, null),
                ::WhereKeys,
            ),
            savedState.getStateFlow<Long?>(FROM_EPOCH_DAY, null),
            savedState.getStateFlow<Long?>(TO_EPOCH_DAY, null),
            combine(
                savedState.getStateFlow(AT_LEAST_TYPED, ""),
                savedState.getStateFlow(AT_MOST_TYPED, ""),
                ::AmountKeys,
            ),
            savedState.getStateFlow(NOTE_TYPED, ""),
            ::FilterKeys,
        )

    init {
        applyOpening()
    }

    /**
     * What the ledger was opened on becomes its filter once, and from there it
     * is a filter like any other: clearing it must not bring it back when the
     * screen is rebuilt from the same saved state.
     */
    private fun applyOpening() {
        val openedOn = savedState.get<String>(OPENED_ON_ARG)
        if (openedOn == null || savedState.get<Boolean>(OPENING_APPLIED) == true) return

        val id = savedState.get<Long>(OPENED_ID_ARG)
        savedState[UNCATEGORISED_ONLY] = openedOn == OPENED_ON_NO_CATEGORY
        savedState[SELECTED_CATEGORY_ID] = id.takeIf { openedOn == OPENED_ON_CATEGORY }
        savedState[SELECTED_CATEGORY_GROUP_ID] = id.takeIf { openedOn == OPENED_ON_GROUP }
        savedState[FROM_EPOCH_DAY] = savedState.get<Long>(OPENED_FROM_ARG)
        savedState[TO_EPOCH_DAY] = savedState.get<Long>(OPENED_TO_ARG)
        savedState[OPENING_APPLIED] = true
    }

    /** Null shows every account again. */
    fun onAccountSelected(id: Long?) {
        savedState[SELECTED_ACCOUNT_ID] = id
    }

    /** Null shows every category again. */
    fun onCategorySelected(choice: CategoryChoice?) {
        savedState[UNCATEGORISED_ONLY] = choice == CategoryChoice.Uncategorised
        savedState[SELECTED_CATEGORY_ID] = (choice as? CategoryChoice.Named)?.category?.id
        savedState[SELECTED_CATEGORY_GROUP_ID] = (choice as? CategoryChoice.InGroup)?.group?.id
    }

    /** Null shows every merchant again. */
    fun onMerchantSelected(id: Long?) {
        savedState[SELECTED_MERCHANT_ID] = id
    }

    /** Null shows every day up to [LedgerFilter.to] again. */
    fun onFromSelected(date: LocalDate?) {
        savedState[FROM_EPOCH_DAY] = date?.toEpochDay()
    }

    /** Null shows every day from [LedgerFilter.from] on again. */
    fun onToSelected(date: LocalDate?) {
        savedState[TO_EPOCH_DAY] = date?.toEpochDay()
    }

    /** What was typed in *At least*; blank shows every amount up to [LedgerFilter.atMost] again. */
    fun onAtLeastTyped(text: String) {
        savedState[AT_LEAST_TYPED] = text
    }

    /** What was typed in *At most*; blank shows every amount from [LedgerFilter.atLeast] on again. */
    fun onAtMostTyped(text: String) {
        savedState[AT_MOST_TYPED] = text
    }

    /** What was typed in *Note contains*; blank shows every note again, and rows with none. */
    fun onNoteTyped(text: String) {
        savedState[NOTE_TYPED] = text
    }

    /** Every filter off at once. */
    fun onFiltersCleared() {
        onAccountSelected(null)
        onCategorySelected(null)
        onMerchantSelected(null)
        onFromSelected(null)
        onToSelected(null)
        onAtLeastTyped("")
        onAtMostTyped("")
        onNoteTyped("")
    }

    private companion object {
        const val SELECTED_ACCOUNT_ID = "selectedAccountId"
        const val SELECTED_CATEGORY_ID = "selectedCategoryId"
        const val SELECTED_CATEGORY_GROUP_ID = "selectedCategoryGroupId"
        const val UNCATEGORISED_ONLY = "uncategorisedOnly"
        const val SELECTED_MERCHANT_ID = "selectedMerchantId"
        const val FROM_EPOCH_DAY = "fromEpochDay"
        const val TO_EPOCH_DAY = "toEpochDay"
        const val AT_LEAST_TYPED = "atLeastTyped"
        const val AT_MOST_TYPED = "atMostTyped"
        const val NOTE_TYPED = "noteTyped"
        const val OPENING_APPLIED = "openingApplied"
    }
}

/** The filter's saved-state keys, read together. */
internal data class FilterKeys(
    val where: WhereKeys,
    val fromEpochDay: Long?,
    val toEpochDay: Long?,
    val amounts: AmountKeys,
    val note: String,
)

/**
 * The amounts as typed. They are kept as text rather than as amounts, so
 * the fields hold what was typed — `12,` on the way to `12,50` — across
 * the page closing and the screen being rebuilt.
 */
internal data class AmountKeys(
    val atLeast: String,
    val atMost: String,
) {
    val low: Money? = atLeast.asAmount()
    val high: Money? = atMost.asAmount()
    val crossed: Boolean = low != null && high != null && low.minor > high.minor

    /** Read as [Formatters.parseMoney] reads the form's amount, and as a size whichever sign was typed. */
    private fun String.asAmount(): Money? = Formatters.parseMoney(this)?.let { Money(abs(it.minor), it.currency) }
}

/** The keys of the filters that narrow by where the money went. */
internal data class WhereKeys(
    val accountId: Long?,
    val categoryId: Long?,
    val groupId: Long?,
    val uncategorisedOnly: Boolean,
    val merchantId: Long?,
)
