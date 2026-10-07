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
import io.github.jaytech64.depenses.core.model.TransactionPart
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
 * On the whole ledger a transfer is not a figure of its own: one that crossed
 * into a bank account is among the expenses and one that came back out of it
 * is among the income (ADR-0017), the same rows a report counts, so the whole
 * ledger's expenses and a report's for the month are one number. Under an
 * account filter a transfer moves that account whichever accounts it joins
 * (ADR-0006), so it is a figure of its own there rather than being folded into
 * either of the other two. Either way the figures add up to [balance].
 */
data class BalanceSummary(
    val income: Money = Money(0, Money.CAD),
    val expenses: Money = Money(0, Money.CAD),
    /** Zero unless an account is filtered on, and then what [movedOn] says each transfer moved. */
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

    fun admits(transaction: Transaction): Boolean = shown(transaction) != null

    /**
     * The row as this filter shows it, or null when it leaves it out.
     *
     * Narrowed to a category, a split counts for the parts that match and no
     * more: its amount here is theirs, so the rows add up to what a report says
     * went to that category (ADR-0018). Narrowed to an account one of its parts
     * goes into, it counts for that share, which is what the account gained
     * (ADR-0019). The amounts are compared with that number, the one the row
     * shows.
     */
    fun shown(transaction: Transaction): Transaction? {
        val placed =
            transaction.isOn(account) &&
                transaction.isIn(category) &&
                (!transaction.isSplit || partsCounted(transaction).isNotEmpty()) &&
                (merchant == null || transaction.merchantId == merchant.id) &&
                (from == null || transaction.localDate >= from) &&
                (to == null || transaction.localDate <= to) &&
                (note == null || transaction.note?.containsTyped(note) == true)
        if (!placed) return null
        val shown = transaction.narrowed()
        val sized =
            (atLeast == null || shown.amount.minor >= atLeast.minor) &&
                (atMost == null || shown.amount.minor <= atMost.minor)
        return shown.takeIf { sized }
    }

    /** The split with the amount of the parts this filter counts; anything else as it is. */
    private fun Transaction.narrowed(): Transaction =
        if (isSplit) copy(amount = Money(partsCounted(this).sumOf { it.amount.minor }, amount.currency)) else this

    /**
     * The parts of a split this filter counts, which are the ones its amount
     * here is made of: those under [category], on [account]. Every part is on
     * the account the split itself is on, and a part that goes into another
     * account is on that one too.
     */
    fun partsCounted(split: Transaction): List<TransactionPart> =
        split.parts.filter { part ->
            (category == null || category.holds(part.categoryId)) &&
                (account == null || split.isAnEndOf(account) || part.toAccountId == account.id)
        }

    /**
     * Both ends of a transfer match an account: money that reached an account is
     * money on that account, and a screen that showed only the leaving end would
     * lose half of every transfer. A split is on the account any of its parts
     * goes into, for the same reason.
     */
    private fun Transaction.isOn(account: Account?): Boolean =
        account == null || isAnEndOf(account) || parts.any { it.toAccountId == account.id }

    private fun Transaction.isAnEndOf(account: Account): Boolean = accountId == account.id || toAccountId == account.id

    /** A split is in every category its parts carry. */
    private fun Transaction.isIn(category: CategoryChoice?): Boolean =
        category == null || categoryIds.any(category::holds)
}

/** Whether a row or a part carrying [categoryId], or none, is what this choice narrows to. */
private fun CategoryChoice.holds(categoryId: Long?): Boolean =
    when (this) {
        CategoryChoice.Uncategorised -> categoryId == null
        is CategoryChoice.Named -> categoryId == category.id
        is CategoryChoice.InGroup -> categoryId in categoryIds
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
    /**
     * The whole amount of each split shown for less than it, because the ledger
     * is narrowed to a category only some of its parts carry.
     */
    val wholes: Map<Long, Money> = emptyMap(),
    /** The rows picked by a long press, and only those still on screen. */
    val selected: Set<Long> = emptySet(),
    /** How many of them are splits, which categorising the selection leaves as they are. */
    val selectedSplitCount: Int = 0,
    /** What the selected rows add up to, counted as the day and month totals are; null while nothing is selected. */
    val selectionSummary: BalanceSummary? = null,
    /** What the selection can be given, *No category* first; empty while nothing is selected. */
    val selectionCategories: List<SelectionCategory> = emptyList(),
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = days.isEmpty() && upcoming.isEmpty() && !loading

    val isSelecting: Boolean get() = selected.isNotEmpty()

    /** Every row that can be selected is: all but what is still to come. */
    val allSelected: Boolean get() = selected.size == days.sumOf { it.transactions.size }

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
                val (typedAhead, happened) = transactions.mapNotNull(filter::shown).partition { it.localDate > today }
                val amounts = transactions.associate { it.id to it.amount }
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
                    wholes =
                        happened
                            .filter { it.isSplit && it.amount != amounts[it.id] }
                            .associate { it.id to amounts.getValue(it.id) },
                    selected = selected.mapTo(mutableSetOf()) { it.id },
                    selectedSplitCount = selected.count { it.isSplit },
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
            val uncategorised = count { null in it.categoryIds }
            val byId = known.associate { it.category.id to it.category }
            val named =
                optionsBy(today, name = { it.category.name }) { row ->
                    row.categoryIds.filterNotNull().map { id ->
                        CategoryChoice.Named(byId[id] ?: Category(id, row.categoryNamed(id).orEmpty()))
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
                FilterOption(choice, count { row -> row.categoryIds.any { it in choice.categoryIds } })
                    .takeIf { it.count > 0 }
            }

        /** Named as the merchant is called now, which a row read before a rename may not be. */
        private fun List<Transaction>.merchantOptions(
            today: LocalDate,
            known: List<Merchant>,
        ): List<FilterOption<Merchant>> {
            val byId = known.associateBy { it.id }
            return optionsBy(today, name = { it.displayName }) { row -> listOfNotNull(row.merchantId?.let(byId::get)) }
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
 * Each value [keys] gives a row, with how many rows gave it: the ones used most
 * in the last [RECENT_DAYS] first, then the one used last, then by name — the
 * order the form's merchant and category suggestions already follow, so a place
 * you go every week is near the top wherever it is offered. A split gives each
 * of its categories, and counts once under each.
 */
private fun <T : Any> List<Transaction>.optionsBy(
    today: LocalDate,
    name: (T) -> String,
    keys: (Transaction) -> List<T>,
): List<FilterOption<T>> {
    val since = today.minusDays(RECENT_DAYS)
    return flatMap { row -> keys(row).map { it to row } }
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
    val inHomeCurrency = inHomeCurrency()
    val counted = inHomeCurrency.flatMap { it.rowsCounted(filter) }
    val account = filter.account
    val (ofTheAccount, ofTheLedger) = counted.partition { account != null && it.kind == TransactionKind.TRANSFER }
    val moved = ofTheLedger.map { it.signedAmount }

    return BalanceSummary(
        income = moved.filter { it.minor > 0 }.fold(HOME_ZERO, Money::plus),
        expenses = -moved.filter { it.minor < 0 }.fold(HOME_ZERO, Money::plus),
        transfers = ofTheAccount.map { it.movedOn(checkNotNull(account)) }.fold(HOME_ZERO, Money::plus),
        otherCurrencyCount = size - inHomeCurrency.size,
    )
}

/**
 * The transaction as the rows a total reads: itself, or for a split each part
 * [filter] counts, as the row it would be entered apart. A split's worth is its
 * parts' (ADR-0019), and narrowed to a category or an account only the parts
 * there count. A part that goes into another account is then a transfer like
 * any other, which [movedOn] signs by the end the filtered account is on.
 */
private fun Transaction.rowsCounted(filter: LedgerFilter): List<Transaction> =
    if (isSplit) filter.partsCounted(this).map(::apart) else listOf(this)

/**
 * The rows a total may add up. Converting needs a rate and a rate needs the
 * network, so a total counts Canadian dollars and says how many rows it left
 * out, the rule Reports already follows (ADR-0012). Every total on this screen
 * goes through here, since [Money.plus] throws on a second currency.
 */
private fun List<Transaction>.inHomeCurrency(): List<Transaction> = filter { it.amount.currency == Money.CAD }

/**
 * Every category the [selected] rows could be given, *No category* first, each
 * with how many of them carry it already, so a pick shows what it replaces. A
 * split among them is counted under none: no pick reaches it.
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
    val used =
        filter { it.kind in kinds }
            .flatMap { row -> row.categoryIds.filterNotNull().map { it to row } }
            .groupBy({ it.first }, { it.second })
    val carried = selected.filterNot { it.isSplit }.groupingBy { it.categoryId }.eachCount()
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
 * What a transfer moves on [account], which is not the whole ledger: money that
 * left for another account of yours has still left this one, so the transfer is
 * signed by which end the account is on (ADR-0006). That is the arithmetic the
 * account's own balance is already built from in `AccountDao.allWithCounts`,
 * and that balance is what the filter menu offered to pick from; the two would
 * otherwise disagree about the same transfer, one tap apart.
 *
 * With no account filtered on, a category or a group changes nothing: a
 * transfer is worth [Transaction.signedAmount] wherever it is read (ADR-0017).
 */
private fun Transaction.movedOn(account: Account): Money = if (accountId == account.id) -amount else amount

/** The name the row has for a category it carries, on itself or on one of its parts. */
private fun Transaction.categoryNamed(id: Long): String? =
    if (isSplit) parts.firstOrNull { it.categoryId == id }?.categoryName else categoryName
