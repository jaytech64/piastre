// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.AccountRepository
import io.github.jaytech64.depenses.core.data.CategoryGroupRepository
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.RecurringRepository
import io.github.jaytech64.depenses.core.data.TransactionEditor
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.Category
import io.github.jaytech64.depenses.core.model.CategoryGroup
import io.github.jaytech64.depenses.core.model.CategoryUsage
import io.github.jaytech64.depenses.core.model.Merchant
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.containsTyped
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlin.math.abs

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

    /** Every category in [group], which [categoryIds] lists as they are now. */
    data class InGroup(
        val group: CategoryGroup,
        val categoryIds: Set<Long>,
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
    /** The first day shown, itself included. */
    val from: LocalDate? = null,
    /** The last day shown, itself included. */
    val to: LocalDate? = null,
    /**
     * The smallest amount shown, itself included. Compared with the number each
     * row shows, in its own currency: converting would need a rate, and a rate
     * needs the network.
     */
    val atLeast: Money? = null,
    /** The largest amount shown, itself included; compared as [atLeast] is. */
    val atMost: Money? = null,
    /** Words a row's note contains, whatever their case and accents. */
    val note: String? = null,
) {
    val hasDates: Boolean get() = from != null || to != null

    /** How many slots other than the dates are set; the dates are the period, which is said on its own. */
    val countBesideDates: Int get() = listOfNotNull(account, category, merchant, atLeast ?: atMost, note).size

    val isActive: Boolean get() = hasDates || countBesideDates > 0

    fun admits(transaction: Transaction): Boolean =
        transaction.isOn(account) &&
            transaction.isIn(category) &&
            (merchant == null || transaction.merchantId == merchant.id) &&
            (from == null || transaction.localDate >= from) &&
            (to == null || transaction.localDate <= to) &&
            (atLeast == null || transaction.amount.minor >= atLeast.minor) &&
            (atMost == null || transaction.amount.minor <= atMost.minor) &&
            (note == null || transaction.note?.containsTyped(note) == true)

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
            is CategoryChoice.InGroup -> categoryId in category.categoryIds
        }
}

/** A category the selection can be given, with how many selected rows already carry it. */
data class SelectionCategory(
    /** Null is *No category*. */
    val category: Category?,
    val selectedCount: Int,
)

data class LedgerUiState(
    /** What has happened, day by day: nothing dated after today. */
    val days: List<LedgerDay> = emptyList(),
    /** What is dated after today, the soonest first; it counts in nothing above it (ADR-0015). */
    val upcoming: List<UpcomingLine> = emptyList(),
    /** What [upcoming] comes to, counted as a day is. */
    val upcomingSummary: BalanceSummary = BalanceSummary(),
    val upcomingOpen: Boolean = false,
    /** Null until the clock has been read once; never a sentinel date. */
    val today: LocalDate? = null,
    /** This month's rows, or the filter's days once it has some. */
    val headline: BalanceSummary = BalanceSummary(),
    val needsReview: Int = 0,
    /** Every account, including ones nothing has ever been spent from. */
    val accounts: List<Account> = emptyList(),
    /** Only what some confirmed row carries, so no choice leads to an empty screen. */
    val categories: List<FilterOption<CategoryChoice>> = emptyList(),
    /** Likewise only groups whose categories some confirmed row carries, each counting all of them. */
    val categoryGroups: List<FilterOption<CategoryChoice.InGroup>> = emptyList(),
    /** Likewise only merchants some confirmed row names. */
    val merchants: List<FilterOption<Merchant>> = emptyList(),
    val filter: LedgerFilter = LedgerFilter(),
    /** The amounts as typed, which the filter's own can no longer say once they are read. */
    val typedAtLeast: String = "",
    val typedAtMost: String = "",
    val typedNote: String = "",
    /** *At least* is above *At most*, so neither narrows anything until one is changed. */
    val amountsCrossed: Boolean = false,
    /** The rows picked by a long press, and only those still on screen. */
    val selected: Set<Long> = emptySet(),
    /** What the selected rows add up to, counted as the day and month totals are; null while nothing is selected. */
    val selectionSummary: BalanceSummary? = null,
    /** What the selection can be given, *No category* first; empty while nothing is selected. */
    val selectionCategories: List<SelectionCategory> = emptyList(),
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = days.isEmpty() && upcoming.isEmpty() && !loading

    val isSelecting: Boolean get() = selected.isNotEmpty()

    /** How many transactions the filters leave on the ledger. */
    val shownCount: Int get() = days.sumOf { it.transactions.size } + upcoming.size
}

@HiltViewModel
class LedgerViewModel
    @Inject
    constructor(
        repository: TransactionRepository,
        accounts: AccountRepository,
        categories: CategoryRepository,
        categoryGroups: CategoryGroupRepository,
        merchants: MerchantRepository,
        editor: TransactionEditor,
        recurring: RecurringRepository,
        ticker: LocalDateTicker,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        /** What the ledger is narrowed to, as the filter controls last set it. */
        val filters = LedgerFilters(savedState)

        /** The rows picked by a long press, and what can be done to them. */
        val selection =
            LedgerSelection(savedState, editor, categories, categoryGroups, viewModelScope) {
                uiState.value.days.flatMapTo(mutableSetOf()) { day -> day.transactions.map { it.id } }
            }

        /** The accounts with what each holds today, so their balances move at midnight too. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val known: Flow<Known> =
            ticker.dates().flatMapLatest { today ->
                combine(accounts.all(today), categories.all(), categoryGroups.all(), merchants.all(), ::Known)
            }

        /** Whether what is dated after today is shown open or as its one line. */
        val upcoming = UpcomingGroup(savedState)

        private val filtering: Flow<Triple<FilterKeys, Set<Long>, Boolean>> =
            combine(filters.keys, selection.rowIds, upcoming.open, ::Triple)

        /**
         * The current day is a source of truth in its own right, not a value read
         * once at construction. Without it the heading still says "today" over
         * yesterday's purchases the morning after the screen was first opened.
         */
        val uiState: StateFlow<LedgerUiState> =
            combine(
                combine(repository.confirmed(), recurring.nextRows(), ::Pair),
                repository.needsReviewCount(),
                ticker.dates(),
                known,
                filtering,
            ) { (transactions, nextRows), needsReview, today, known, (keys, rowIds, upcomingOpen) ->
                val filter = known.resolve(keys)
                val (typedAhead, happened) = transactions.filter(filter::admits).partition { it.localDate > today }
                val ahead =
                    (
                        typedAhead.map(::UpcomingLine) +
                            nextRows
                                .filter { it.localDate > today && filter.admits(it) }
                                .map { UpcomingLine(it, it.recurringId) }
                    ).sortedBy { it.transaction.occurredAt }
                val selected = happened.filter { it.id in rowIds }
                val days = happened.groupByLocalDay(filter)
                LedgerUiState(
                    days = days,
                    upcoming = ahead,
                    upcomingSummary = ahead.map { it.transaction }.summaryOf(filter),
                    upcomingOpen = upcomingOpen,
                    today = today,
                    headline = days.headlineRows(filter, today).summaryOf(filter),
                    needsReview = needsReview,
                    accounts = known.accounts,
                    categories = transactions.categoryOptions(today, known.categories),
                    categoryGroups = transactions.groupOptions(known),
                    merchants = transactions.merchantOptions(today, known.merchants),
                    filter = filter,
                    typedAtLeast = keys.amounts.atLeast,
                    typedAtMost = keys.amounts.atMost,
                    typedNote = keys.note,
                    amountsCrossed = keys.amounts.crossed,
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

        /**
         * Rows with no category first, since finding them is most of why the choice
         * is there; then the categories, in the order [optionsBy] gives, each as it
         * is known now so the screen can put it under its group.
         */
        private fun List<Transaction>.categoryOptions(
            today: LocalDate,
            known: List<CategoryUsage>,
        ): List<FilterOption<CategoryChoice>> {
            val uncategorised = count { it.categoryId == null }
            val byId = known.associate { it.category.id to it.category }
            val named =
                optionsBy(today, name = { it.category.name }) { row ->
                    row.categoryId?.let { id ->
                        CategoryChoice.Named(byId[id] ?: Category(id, row.categoryName.orEmpty()))
                    }
                }
            return listOfNotNull(
                FilterOption<CategoryChoice>(CategoryChoice.Uncategorised, uncategorised).takeIf { uncategorised > 0 },
            ) + named
        }

        /** Each group some row's category is in, with how many rows its categories carry together. */
        private fun List<Transaction>.groupOptions(known: Known): List<FilterOption<CategoryChoice.InGroup>> =
            known.groups.mapNotNull { group ->
                val choice = known.inGroup(group)
                FilterOption(choice, count { it.categoryId in choice.categoryIds }).takeIf { it.count > 0 }
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
         *
         * What recurs is the day's first transaction: it fell due as the day began,
         * before anything was spent, so in a list read newest first it sits at the
         * bottom of its day. A recurring row has a day but no hour, so its place
         * among the times would otherwise be wherever noon happens to fall.
         */
        private fun List<Transaction>.groupByLocalDay(filter: LedgerFilter): List<LedgerDay> =
            groupBy { it.localDate }
                .map { (date, forDay) ->
                    LedgerDay(
                        date = date,
                        total = forDay.summaryOf(filter).balance,
                        transactions = forDay.sortedBy { it.source == TransactionSource.RECURRING },
                    )
                }.sortedByDescending { it.date }

        /**
         * The rows the headline adds up. Under a filter with dates, every row
         * shown: the dates are the period, so the headline and the list below it
         * are one set of rows (ADR-0014). Otherwise this month's — every day of
         * the calendar month, including one dated ahead of today, since a purchase
         * the list shows is a purchase the headline above it has counted.
         */
        private fun List<LedgerDay>.headlineRows(
            filter: LedgerFilter,
            today: LocalDate,
        ): List<Transaction> {
            val month = YearMonth.from(today)
            return filter { filter.hasDates || YearMonth.from(it.date) == month }.flatMap { it.transactions }
        }

        /** What exists to be filtered to. */
        private data class Known(
            val accounts: List<Account>,
            val categories: List<CategoryUsage>,
            val groups: List<CategoryGroup>,
            val merchants: List<Merchant>,
        ) {
            fun inGroup(group: CategoryGroup) =
                CategoryChoice.InGroup(
                    group,
                    categories.filter { it.category.groupId == group.id }.mapTo(mutableSetOf()) { it.category.id },
                )

            /**
             * The filter resolved against what exists. Something removed in Settings
             * leaves a filter nothing answers for, and the screen goes back to
             * showing everything rather than an empty list under the name of
             * something gone.
             */
            fun resolve(selection: FilterKeys) =
                LedgerFilter(
                    account = accounts.firstOrNull { it.id == selection.where.accountId },
                    category =
                        when {
                            selection.where.uncategorisedOnly -> {
                                CategoryChoice.Uncategorised
                            }

                            selection.where.groupId != null -> {
                                groups.firstOrNull { it.id == selection.where.groupId }?.let(::inGroup)
                            }

                            else -> {
                                categories
                                    .firstOrNull { it.category.id == selection.where.categoryId }
                                    ?.let { CategoryChoice.Named(it.category) }
                            }
                        },
                    merchant = merchants.firstOrNull { it.id == selection.where.merchantId },
                    from = selection.fromEpochDay?.let(LocalDate::ofEpochDay),
                    to = selection.toEpochDay?.let(LocalDate::ofEpochDay),
                    atLeast = selection.amounts.low.takeUnless { selection.amounts.crossed },
                    atMost = selection.amounts.high.takeUnless { selection.amounts.crossed },
                    note = selection.note.trim().ifEmpty { null },
                )
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
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
 * this month. A group is its categories together, so the same transfer is
 * money that left under the group too, and a group adds up to its categories.
 * *No category* is not a place money goes, so an uncategorised
 * transfer — a card payment, most often — keeps the whole ledger's zero rather
 * than charging you a second time for the purchases it settles.
 */
private fun Transaction.balanceMovedOn(filter: LedgerFilter): Money {
    val account = filter.account
    return when {
        kind != TransactionKind.TRANSFER -> signedAmount
        account != null -> if (accountId == account.id) -amount else amount
        filter.category is CategoryChoice.Named -> -amount
        filter.category is CategoryChoice.InGroup -> -amount
        else -> signedAmount
    }
}
