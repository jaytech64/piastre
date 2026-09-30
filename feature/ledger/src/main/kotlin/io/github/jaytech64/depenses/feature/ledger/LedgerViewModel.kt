// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.AccountRepository
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TransactionEditor
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.Category
import io.github.jaytech64.depenses.core.model.CategoryUsage
import io.github.jaytech64.depenses.core.model.Merchant
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** One local day, with its balance: what came in minus what went out. */
data class LedgerDay(
    val date: LocalDate,
    val total: Money,
    val transactions: List<Transaction>,
)

/**
 * What the balance of a set of rows is made of: a month, a day, or a selection.
 *
 * Income and expenses are counted by kind, the same rows a report counts, so
 * the whole ledger's expenses and a report's *dépensé* for the month are one
 * number. A transfer is neither: it is worth zero to the whole ledger and moves
 * a filtered account's balance (ADR-0006), so it is a figure of its own rather
 * than being folded into either of the other two, and the three always add up
 * to [balance].
 */
data class BalanceSummary(
    val income: Money = Money(0, Money.CAD),
    val expenses: Money = Money(0, Money.CAD),
    /** Zero on the whole ledger; under a filter, what [balanceMovedOn] says it moved. */
    val transfers: Money = Money(0, Money.CAD),
    /** The rows in a currency no figure here counts (ADR-0012). */
    val otherCurrencyCount: Int = 0,
) {
    /** Negative where you only spent, which is most months. */
    val balance: Money get() = income + -expenses + transfers
}

/** A category the ledger can be narrowed to. */
sealed interface CategoryChoice {
    /** The rows nobody has given a category yet. */
    data object Uncategorised : CategoryChoice

    data class Named(
        val category: Category,
    ) : CategoryChoice
}

/** Something the filter sheet offers, with how many confirmed rows choosing it would show. */
data class FilterOption<out T>(
    val value: T,
    val count: Int,
)

/**
 * What the ledger is narrowed to. An empty slot does not narrow it, and a row
 * has to match every slot that is set.
 */
data class LedgerFilter(
    val account: Account? = null,
    val category: CategoryChoice? = null,
    /** Matched by id, so every spelling Wallet has used for one merchant matches it. */
    val merchant: Merchant? = null,
) {
    val isActive: Boolean get() = account != null || category != null || merchant != null

    fun admits(transaction: Transaction): Boolean =
        transaction.isOn(account) &&
            transaction.isIn(category) &&
            (merchant == null || transaction.merchantId == merchant.id)

    /**
     * Both ends of a transfer match an account: money that reached an account is
     * money on that account, and a screen that showed only the leaving end would
     * lose half of every transfer.
     */
    private fun Transaction.isOn(account: Account?): Boolean =
        account == null || accountId == account.id || toAccountId == account.id

    private fun Transaction.isIn(category: CategoryChoice?): Boolean =
        when (category) {
            null -> true
            CategoryChoice.Uncategorised -> categoryId == null
            is CategoryChoice.Named -> categoryId == category.category.id
        }
}

/** A category the selection can be given, with how many selected rows already carry it. */
data class SelectionCategory(
    /** Null is *No category*. */
    val category: Category?,
    val selectedCount: Int,
)

data class LedgerUiState(
    val days: List<LedgerDay> = emptyList(),
    /** Null until the clock has been read once; never a sentinel date. */
    val today: LocalDate? = null,
    val thisMonth: BalanceSummary = BalanceSummary(),
    val needsReview: Int = 0,
    /** Every account, including ones nothing has ever been spent from. */
    val accounts: List<Account> = emptyList(),
    /** Only what some confirmed row carries, so no choice leads to an empty screen. */
    val categories: List<FilterOption<CategoryChoice>> = emptyList(),
    /** Likewise only merchants some confirmed row names. */
    val merchants: List<FilterOption<Merchant>> = emptyList(),
    val filter: LedgerFilter = LedgerFilter(),
    /** The rows picked by a long press, and only those still on screen. */
    val selected: Set<Long> = emptySet(),
    /** What the selected rows add up to, counted as the day and month totals are; null while nothing is selected. */
    val selectionSummary: BalanceSummary? = null,
    /** What the selection can be given, *No category* first; empty while nothing is selected. */
    val selectionCategories: List<SelectionCategory> = emptyList(),
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = days.isEmpty() && !loading

    val isSelecting: Boolean get() = selected.isNotEmpty()

    /** How many transactions the filters leave on the ledger. */
    val shownCount: Int get() = days.sumOf { it.transactions.size }
}

@HiltViewModel
class LedgerViewModel
    @Inject
    constructor(
        repository: TransactionRepository,
        accounts: AccountRepository,
        categories: CategoryRepository,
        merchants: MerchantRepository,
        editor: TransactionEditor,
        ticker: LocalDateTicker,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        /**
         * The filter lives in saved state rather than in a field, because the bottom
         * bar navigates with `popUpTo(saveState = true)`: that clears the view model
         * scoped to the ledger entry, so a field would drop the filter on the way to
         * Reports and back. The saved-state bundle survives that and a rotation,
         * while a cold launch starts without one — which is the lifetime the filter
         * was asked for.
         */
        private val filterKeys: Flow<FilterKeys> =
            combine(
                savedState.getStateFlow<Long?>(SELECTED_ACCOUNT_ID, null),
                savedState.getStateFlow<Long?>(SELECTED_CATEGORY_ID, null),
                savedState.getStateFlow(UNCATEGORISED_ONLY, false),
                savedState.getStateFlow<Long?>(SELECTED_MERCHANT_ID, null),
                ::FilterKeys,
            )

        /** The rows picked by a long press, and what can be done to them. */
        val selection =
            LedgerSelection(savedState, editor, categories, viewModelScope) {
                uiState.value.days.flatMapTo(mutableSetOf()) { day -> day.transactions.map { it.id } }
            }

        private val known: Flow<Known> = combine(accounts.all(), categories.all(), merchants.all(), ::Known)

        private val filtering: Flow<Pair<FilterKeys, Set<Long>>> = combine(filterKeys, selection.rowIds, ::Pair)

        /**
         * The current day is a source of truth in its own right, not a value read
         * once at construction. Without it the heading still says "today" over
         * yesterday's purchases the morning after the screen was first opened.
         */
        val uiState: StateFlow<LedgerUiState> =
            combine(
                repository.confirmed(),
                repository.needsReviewCount(),
                ticker.dates(),
                known,
                filtering,
            ) { transactions, needsReview, today, known, (keys, rowIds) ->
                val filter = known.resolve(keys)
                val shown = transactions.filter(filter::admits)
                val selected = shown.filter { it.id in rowIds }
                val days = shown.groupByLocalDay(filter)
                LedgerUiState(
                    days = days,
                    today = today,
                    thisMonth = days.monthOf(YearMonth.from(today)).summaryOf(filter),
                    needsReview = needsReview,
                    accounts = known.accounts,
                    categories = transactions.categoryOptions(today),
                    merchants = transactions.merchantOptions(today, known.merchants),
                    filter = filter,
                    selected = selected.mapTo(mutableSetOf()) { it.id },
                    selectionSummary = selected.takeIf { it.isNotEmpty() }?.summaryOf(filter),
                    selectionCategories = transactions.categoriesFor(selected, known.categories, today),
                    loading = false,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = LedgerUiState(),
            )

        /** Null shows every account again. */
        fun onAccountSelected(id: Long?) {
            savedState[SELECTED_ACCOUNT_ID] = id
        }

        /** Null shows every category again. */
        fun onCategorySelected(choice: CategoryChoice?) {
            savedState[UNCATEGORISED_ONLY] = choice == CategoryChoice.Uncategorised
            savedState[SELECTED_CATEGORY_ID] = (choice as? CategoryChoice.Named)?.category?.id
        }

        /** Null shows every merchant again. */
        fun onMerchantSelected(id: Long?) {
            savedState[SELECTED_MERCHANT_ID] = id
        }

        /** Every filter off at once. */
        fun onFiltersCleared() {
            onAccountSelected(null)
            onCategorySelected(null)
            onMerchantSelected(null)
        }

        /**
         * Rows with no category first, since finding them is most of why the choice
         * is there; then the categories, in the order [optionsBy] gives.
         */
        private fun List<Transaction>.categoryOptions(today: LocalDate): List<FilterOption<CategoryChoice>> {
            val uncategorised = count { it.categoryId == null }
            val named =
                optionsBy(today, name = { it.category.name }) { row ->
                    row.categoryId?.let { CategoryChoice.Named(Category(it, row.categoryName.orEmpty())) }
                }
            return listOfNotNull(
                FilterOption<CategoryChoice>(CategoryChoice.Uncategorised, uncategorised).takeIf { uncategorised > 0 },
            ) + named
        }

        /** Named as the merchant is called now, which a row read before a rename may not be. */
        private fun List<Transaction>.merchantOptions(
            today: LocalDate,
            known: List<Merchant>,
        ): List<FilterOption<Merchant>> {
            val byId = known.associateBy { it.id }
            return optionsBy(today, name = { it.displayName }) { row -> row.merchantId?.let(byId::get) }
        }

        /**
         * Grouped in Kotlin rather than SQL because the rows are already sorted and
         * already in memory — this is the one screen that wants every transaction,
         * so a second query would re-read what the first one just returned. The
         * filter rides along for the same reason.
         */
        private fun List<Transaction>.groupByLocalDay(filter: LedgerFilter): List<LedgerDay> =
            groupBy { it.localDate }
                .map { (date, forDay) ->
                    LedgerDay(
                        date = date,
                        total = forDay.summaryOf(filter).balance,
                        transactions = forDay,
                    )
                }.sortedByDescending { it.date }

        /**
         * Every day of the calendar month counts, including one dated ahead of
         * today: a purchase the list shows is a purchase the headline above it has
         * counted.
         */
        private fun List<LedgerDay>.monthOf(month: YearMonth): List<Transaction> =
            filter { YearMonth.from(it.date) == month }.flatMap { it.transactions }

        /** The filter's saved-state keys, read together. */
        private data class FilterKeys(
            val accountId: Long?,
            val categoryId: Long?,
            val uncategorisedOnly: Boolean,
            val merchantId: Long?,
        )

        /** What exists to be filtered to. */
        private data class Known(
            val accounts: List<Account>,
            val categories: List<CategoryUsage>,
            val merchants: List<Merchant>,
        ) {
            /**
             * The filter resolved against what exists. Something removed in Settings
             * leaves a filter nothing answers for, and the screen goes back to
             * showing everything rather than an empty list under the name of
             * something gone.
             */
            fun resolve(selection: FilterKeys) =
                LedgerFilter(
                    account = accounts.firstOrNull { it.id == selection.accountId },
                    category =
                        if (selection.uncategorisedOnly) {
                            CategoryChoice.Uncategorised
                        } else {
                            categories
                                .firstOrNull { it.category.id == selection.categoryId }
                                ?.let { CategoryChoice.Named(it.category) }
                        },
                    merchant = merchants.firstOrNull { it.id == selection.merchantId },
                )
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val SELECTED_ACCOUNT_ID = "selectedAccountId"
            const val SELECTED_CATEGORY_ID = "selectedCategoryId"
            const val UNCATEGORISED_ONLY = "uncategorisedOnly"
            const val SELECTED_MERCHANT_ID = "selectedMerchantId"
        }
    }

/**
 * Each value [key] gives a row, with how many rows gave it: the ones used most
 * in the last [RECENT_DAYS] first, then the one used last, then by name — the
 * order the form's merchant and category suggestions already follow, so a place
 * you go every week is near the top wherever it is offered.
 */
private fun <T : Any> List<Transaction>.optionsBy(
    today: LocalDate,
    name: (T) -> String,
    key: (Transaction) -> T?,
): List<FilterOption<T>> {
    val since = today.minusDays(RECENT_DAYS)
    return mapNotNull { row -> key(row)?.let { it to row } }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<T, List<Transaction>>> { (_, rows) -> rows.count { it.localDate >= since } }
                .thenByDescending { (_, rows) -> rows.maxOf { it.occurredAt } }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { name(it.key) },
        ).map { (value, rows) -> FilterOption(value, rows.size) }
}

private const val RECENT_DAYS = 90L

private val HOME_ZERO = Money(0, Money.CAD)

/**
 * What these rows add up to, read from where [filter] stands. The day totals,
 * the month's headline and the selection all come from here, so a whole day
 * selected says what its header says, to the cent.
 */
private fun List<Transaction>.summaryOf(filter: LedgerFilter): BalanceSummary {
    val counted = inHomeCurrency()

    fun sumOf(
        kind: TransactionKind,
        amount: (Transaction) -> Money,
    ): Money = counted.filter { it.kind == kind }.map(amount).fold(HOME_ZERO, Money::plus)

    return BalanceSummary(
        income = sumOf(TransactionKind.INCOME) { it.amount },
        expenses = sumOf(TransactionKind.EXPENSE) { it.amount },
        transfers = sumOf(TransactionKind.TRANSFER) { it.balanceMovedOn(filter) },
        otherCurrencyCount = size - counted.size,
    )
}

/**
 * The rows a total may add up. Converting needs a rate and a rate needs the
 * network, so a total counts Canadian dollars and says how many rows it left
 * out, the rule Reports already follows (ADR-0012). Every total on this screen
 * goes through here, since [Money.plus] throws on a second currency.
 */
private fun List<Transaction>.inHomeCurrency(): List<Transaction> = filter { it.amount.currency == Money.CAD }

/**
 * Every category the [selected] rows could be given, *No category* first, each
 * with how many of them carry it already, so a pick shows what it replaces.
 *
 * The rest follow the form's order for the kinds selected — used most in the
 * last [RECENT_DAYS] first, then used last, then by name — which puts
 * *Internet* near the top of a selection of bills. A category nothing carries
 * yet is still offered, last, since it may be the very one the selection is for.
 */
private fun List<Transaction>.categoriesFor(
    selected: List<Transaction>,
    known: List<CategoryUsage>,
    today: LocalDate,
): List<SelectionCategory> {
    if (selected.isEmpty()) return emptyList()
    val kinds = selected.mapTo(mutableSetOf()) { it.kind }
    val since = today.minusDays(RECENT_DAYS)
    val used = filter { it.kind in kinds && it.categoryId != null }.groupBy { it.categoryId }
    val carried = selected.groupingBy { it.categoryId }.eachCount()
    val named =
        known
            .map { it.category }
            .sortedWith(
                compareByDescending<Category> { category ->
                    used[category.id].orEmpty().count { it.localDate >= since }
                }.thenByDescending { category -> used[category.id]?.maxOf { it.occurredAt } }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            ).map { SelectionCategory(it, carried[it.id] ?: 0) }
    return listOf(SelectionCategory(null, carried[null] ?: 0)) + named
}

/**
 * What the transaction moves, read from where [filter] stands.
 *
 * Unfiltered it is [Transaction.signedAmount], where a transfer is worth zero:
 * it moves your own money between your own accounts, so the ledger as a whole
 * is unchanged.
 *
 * One account is not the whole ledger, though — money that left for another
 * account of yours has still left this one — so under an account filter a
 * transfer is signed by which end the account is on (ADR-0006). That is the
 * arithmetic the account's own balance is already built from in
 * `AccountDao.allWithCounts`, and that balance is what the filter menu offered
 * to pick from; the two would otherwise disagree about the same transfer, one
 * tap apart.
 *
 * Under a category, with no account, a transfer is money that left for what the
 * category names (ADR-0011): the mortgage payment is what *Hypothèque* cost you
 * this month. *No category* is not a place money goes, so an uncategorised
 * transfer — a card payment, most often — keeps the whole ledger's zero rather
 * than charging you a second time for the purchases it settles.
 */
private fun Transaction.balanceMovedOn(filter: LedgerFilter): Money {
    val account = filter.account
    return when {
        kind != TransactionKind.TRANSFER -> signedAmount
        account != null -> if (accountId == account.id) -amount else amount
        filter.category is CategoryChoice.Named -> -amount
        else -> signedAmount
    }
}
