// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import io.github.jaytech64.piastre.core.data.CategoryAnchor
import io.github.jaytech64.piastre.core.data.CategoryReach
import io.github.jaytech64.piastre.core.model.Account
import io.github.jaytech64.piastre.core.model.Category
import io.github.jaytech64.piastre.core.model.Merchant
import io.github.jaytech64.piastre.core.model.MerchantNormaliser
import io.github.jaytech64.piastre.core.model.TransactionKind

/** Who the offer beside the category names: a merchant, or the account a transfer goes into. */
sealed interface PlaceName {
    data class AtMerchant(
        val name: String,
    ) : PlaceName

    data class IntoAccount(
        val account: Account,
    ) : PlaceName
}

/**
 * Where a category chosen on the form can reach: the merchant the merchant field
 * names, or on a transfer the account it goes into.
 *
 * [anchor] is null for a merchant typed for the first time. It has no other
 * purchases to reach, but it can still give its category from now on — the
 * merchant is created when the form is saved.
 */
internal data class CategoryPlace(
    val anchor: CategoryAnchor?,
    val name: PlaceName,
    /** The category this place gives everything recorded there, or null. */
    val defaultCategoryId: Long?,
)

/**
 * The two checkboxes under the category field, as they stand.
 *
 * [othersCount] is how many of the place's other transactions of this kind have
 * no category, and the first box is only drawn when there are some. [replacing]
 * names the category the place gives today when it is not the one chosen, so
 * ticking the second box visibly replaces something.
 */
data class CategoryOffer(
    val kind: TransactionKind,
    val place: PlaceName,
    val othersCount: Int,
    val others: Boolean,
    val fromNowOn: Boolean,
    val replacing: String?,
) {
    /** What saving does beyond the transaction itself. */
    val reach: CategoryReach get() = CategoryReach(others = others && othersCount > 0, fromNowOn = fromNowOn)
}

/**
 * The place the form is about, found among the merchants and accounts already
 * known. The merchant is matched on the name the normaliser gives it — the same
 * identity [io.github.jaytech64.piastre.core.data.MerchantRepository.assign]
 * saves under — so the offer and the save agree on which merchant it is.
 */
internal fun ExpenseFormUiState.place(
    merchants: List<Merchant>,
    accounts: List<Account>,
): CategoryPlace? =
    when (kind) {
        TransactionKind.TRANSFER -> {
            accounts.firstOrNull { it.id == toAccountId }?.let {
                CategoryPlace(CategoryAnchor.IntoAccount(it.id), PlaceName.IntoAccount(it), it.categoryId)
            }
        }

        TransactionKind.EXPENSE, TransactionKind.INCOME -> {
            MerchantNormaliser.normalise(merchantText)?.let { typed ->
                val known = merchants.firstOrNull { it.normalisedName == typed.normalisedName }
                CategoryPlace(
                    anchor = known?.let { CategoryAnchor.AtMerchant(it.id) },
                    name = PlaceName.AtMerchant(known?.displayName ?: typed.displayName),
                    defaultCategoryId = known?.categoryId,
                )
            }
        }
    }

/** The offer for [place], or null when there is nothing to offer: no category chosen, or nowhere to spread it. */
internal fun ExpenseFormUiState.offer(
    place: CategoryPlace?,
    othersCount: Int,
    categories: List<Category>,
): CategoryOffer? {
    val chosen = categoryId ?: return null
    place ?: return null
    return CategoryOffer(
        kind = kind,
        place = place.name,
        othersCount = othersCount,
        others = reachOthers,
        fromNowOn = fromNowOnChoice ?: (place.defaultCategoryId == chosen),
        replacing =
            place.defaultCategoryId
                ?.takeIf { it != chosen }
                ?.let { id -> categories.firstOrNull { it.id == id }?.name },
    )
}
