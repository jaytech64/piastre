// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.piastre.core.data.BudgetRepository
import io.github.jaytech64.piastre.core.data.CategoryGroupRepository
import io.github.jaytech64.piastre.core.data.CategoryRepository
import io.github.jaytech64.piastre.core.data.LocalDateTicker
import io.github.jaytech64.piastre.core.data.PremiumRepository
import io.github.jaytech64.piastre.core.data.ReportRepository
import io.github.jaytech64.piastre.core.data.SpentOn
import io.github.jaytech64.piastre.core.data.TransactionRepository
import io.github.jaytech64.piastre.core.model.BudgetChange
import io.github.jaytech64.piastre.core.model.BudgetTarget
import io.github.jaytech64.piastre.core.model.Category
import io.github.jaytech64.piastre.core.model.CategoryGroup
import io.github.jaytech64.piastre.core.model.Days
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.budgetsIn
import io.github.jaytech64.piastre.core.model.daysThrough
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

/** The two halves of the page. */
enum class TrendsTab { YEAR, COMPARE }

data class TrendsUiState(
    /** Null until the clock has been read once, and the page draws nothing until then. */
    val today: LocalDate? = null,
    val tab: TrendsTab = TrendsTab.YEAR,
    /** What the year's bars are of. */
    val on: SpentOn = SpentOn.Everything,
    val groups: List<CategoryGroup> = emptyList(),
    val categories: List<Category> = emptyList(),
    val year: TrendYear? = null,
    /** The month whose figures are written out under the chart; the latest one when none was tapped. */
    val selected: YearMonth? = null,
    val compare: TrendsCompare? = null,
    /** The group of the comparison whose categories are shown. */
    val openGroupId: Long? = null,
    /** The whole ledger's queue, which every figure here leaves out. */
    val needsReviewCount: Int = 0,
    val premiumOn: Boolean = false,
) {
    val selectedMonth: TrendMonth?
        get() = year?.months?.let { months -> months.firstOrNull { it.month == selected } ?: months.last() }
}

/**
 * Twelve months of spending on one thing, beside the year before, and two
 * spans of days side by side.
 *
 * Every figure is read from [ReportRepository] and nothing is worked out a
 * second time: a month is what the ranking of that month counts for the same
 * thing, and a span is what the ranking of those days counts. Nothing here is
 * stored, so the page is always the ledger as it is now.
 *
 * Everything you choose lives in saved state, as the report's period does, so
 * that a trip to the transactions and back keeps it.
 */
@HiltViewModel
class TrendsViewModel
    @Inject
    constructor(
        private val reports: ReportRepository,
        private val budgets: BudgetRepository,
        categories: CategoryRepository,
        groups: CategoryGroupRepository,
        premium: PremiumRepository,
        transactions: TransactionRepository,
        ticker: LocalDateTicker,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val on: Flow<SpentOn> =
            combine(
                savedState.getStateFlow(TRENDS_ON_ARG, TRENDS_ON_EVERYTHING),
                savedState.getStateFlow(TRENDS_ID_ARG, 0L),
            ) { kind, id -> spentOn(kind, id) }

        private val spans: Flow<SavedSpans> =
            combine(
                savedState.getStateFlow<Long?>(FIRST_FROM, null),
                savedState.getStateFlow<Long?>(FIRST_TO, null),
                savedState.getStateFlow<Long?>(SECOND_FROM, null),
                savedState.getStateFlow<Long?>(SECOND_TO, null),
            ) { firstFrom, firstTo, secondFrom, secondTo -> SavedSpans(firstFrom, firstTo, secondFrom, secondTo) }

        private val choices: Flow<Pair<List<CategoryGroup>, List<Category>>> =
            combine(groups.all(), categories.all()) { groupList, categoryList ->
                groupList to categoryList.map { it.category }
            }

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<TrendsUiState> =
            combine(
                ticker.dates(),
                on,
                choices,
                savedState.getStateFlow(YEARS_BACK, 0),
                spans,
            ) { today, chosen, (groupList, categoryList), back, saved ->
                Inputs(today, chosen.orEverythingUnless(groupList, categoryList), groupList, categoryList, back, saved)
            }.flatMapLatest { inputs ->
                combine(
                    year(inputs),
                    compare(inputs),
                    combine(
                        savedState.getStateFlow(TAB, TrendsTab.YEAR),
                        savedState.getStateFlow<String?>(SELECTED, null),
                        savedState.getStateFlow<Long?>(OPEN_GROUP, null),
                        ::Triple,
                    ),
                    transactions.needsReviewCount(),
                    premium.premium,
                ) { year, compare, (tab, selected, openGroup), review, owned ->
                    TrendsUiState(
                        today = inputs.today,
                        tab = tab,
                        on = inputs.on,
                        groups = inputs.groups,
                        categories = inputs.categories,
                        year = year,
                        selected = selected?.let(YearMonth::parse),
                        compare = compare,
                        openGroupId = openGroup,
                        needsReviewCount = review,
                        premiumOn = owned.isOn,
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TrendsUiState())

        /** Twenty-four months are read, so each of the twelve shown stands beside its year before. */
        private fun year(inputs: Inputs): Flow<TrendYear> {
            val end = YearMonth.from(inputs.today).minusYears(inputs.yearsBack.toLong())
            val shown = Days(end.minusMonths(MONTHS_SHOWN - 1).atDay(1), end.atEndOfMonth())
            return combine(
                reports.monthlySpending(end.minusMonths(MONTHS_READ - 1), end, inputs.on, inputs.today),
                reports.firstDay(),
                budgets.changes(),
                reports.otherCurrencyCount(shown, inputs.today),
            ) { spent, firstDay, changes, otherCurrency ->
                trendYear(
                    end = end,
                    today = inputs.today,
                    began = firstDay?.let(YearMonth::from),
                    spent = spent,
                    budgetIn = budgetIn(inputs.on, changes),
                    otherCurrencyCount = otherCurrency,
                )
            }
        }

        private fun compare(inputs: Inputs): Flow<TrendsCompare> {
            val (first, second) = inputs.spans.orDefaults(inputs.today)
            val firstDays = first.daysThrough(inputs.today).coerceAtLeast(1)
            val secondDays = second.daysThrough(inputs.today).coerceAtLeast(1)
            return combine(
                reports.categorySpending(first, inputs.today),
                reports.categorySpending(second, inputs.today),
                reports.otherCurrencyCount(first, inputs.today),
                reports.otherCurrencyCount(second, inputs.today),
            ) { firstSpent, secondSpent, firstOther, secondOther ->
                val (everything, rows) = compared(firstSpent, secondSpent, firstDays, secondDays)
                TrendsCompare(first, second, firstDays, secondDays, everything, rows, firstOther + secondOther)
            }
        }

        fun onTabSelected(tab: TrendsTab) {
            savedState[TAB] = tab
        }

        /** A new subject keeps the window and the month written out, so the same month can be read for each. */
        fun onSpentOnSelected(chosen: SpentOn) {
            val (kind, id) = chosen.asRoute()
            savedState[TRENDS_ON_ARG] = kind
            savedState[TRENDS_ID_ARG] = id
        }

        /** A step keeps the subject, and writes out the latest month of the year stepped to. */
        fun onStepBack() {
            if (uiState.value.year?.canStepBack == true) savedState.stepYears(1)
        }

        fun onStepForward() {
            if (uiState.value.year?.canStepForward == true) savedState.stepYears(-1)
        }

        fun onMonthSelected(month: YearMonth) {
            savedState[SELECTED] = month.toString()
        }

        fun onSpanPicked(
            end: SpanEnd,
            span: Days,
        ) {
            val compare = uiState.value.compare ?: return
            when (end) {
                SpanEnd.FIRST -> savedState.keepSpans(first = span, second = compare.second)
                SpanEnd.SECOND -> savedState.keepSpans(first = compare.first, second = span)
            }
        }

        /** This month so far, against the whole of the month before or of the same month a year earlier. */
        fun onShortcut(shortcut: CompareShortcut) {
            val today = uiState.value.today ?: return
            val month = YearMonth.from(today)
            val against =
                when (shortcut) {
                    CompareShortcut.LAST_MONTH -> month.minusMonths(1)
                    CompareShortcut.A_YEAR_EARLIER -> month.minusYears(1)
                }
            savedState.keepSpans(first = wholeMonth(against), second = thisMonthSoFar(today))
        }

        /** One group is open at a time, and tapping the open one closes it. */
        fun onGroupTapped(groupId: Long) {
            savedState[OPEN_GROUP] = groupId.takeIf { it != savedState.get<Long?>(OPEN_GROUP) }
        }

        private data class Inputs(
            val today: LocalDate,
            val on: SpentOn,
            val groups: List<CategoryGroup>,
            val categories: List<Category>,
            val yearsBack: Int,
            val spans: SavedSpans,
        )

        /** The comparison's spans as epoch days, each null until it is chosen. */
        private data class SavedSpans(
            val firstFrom: Long? = null,
            val firstTo: Long? = null,
            val secondFrom: Long? = null,
            val secondTo: Long? = null,
        ) {
            /** Last month against this one so far, until a span is chosen. */
            fun orDefaults(today: LocalDate): Pair<Days, Days> {
                val first =
                    if (firstFrom != null && firstTo != null) {
                        Days(LocalDate.ofEpochDay(firstFrom), LocalDate.ofEpochDay(firstTo))
                    } else {
                        wholeMonth(YearMonth.from(today).minusMonths(1))
                    }
                val second =
                    if (secondFrom != null && secondTo != null) {
                        Days(LocalDate.ofEpochDay(secondFrom), LocalDate.ofEpochDay(secondTo))
                    } else {
                        thisMonthSoFar(today)
                    }
                return first to second
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val MONTHS_SHOWN = 12L
            const val MONTHS_READ = 24L
        }
    }

/** Which of the comparison's two spans a picked span replaces. */
enum class SpanEnd { FIRST, SECOND }

/** What the comparison's shortcuts set this month so far beside. */
enum class CompareShortcut { LAST_MONTH, A_YEAR_EARLIER }

private fun SavedStateHandle.stepYears(by: Int) {
    this[YEARS_BACK] = (get<Int>(YEARS_BACK) ?: 0) + by
    this[SELECTED] = null
}

private fun SavedStateHandle.keepSpans(
    first: Days,
    second: Days,
) {
    this[FIRST_FROM] = first.start.toEpochDay()
    this[FIRST_TO] = first.endInclusive.toEpochDay()
    this[SECOND_FROM] = second.start.toEpochDay()
    this[SECOND_TO] = second.endInclusive.toEpochDay()
}

/** What a subject's budget was in each month; nothing but a category or a group has one. */
private fun budgetIn(
    on: SpentOn,
    changes: List<BudgetChange>,
): (YearMonth) -> Money? {
    val target =
        when (on) {
            is SpentOn.OneCategory -> BudgetTarget.Category(on.categoryId)
            is SpentOn.OneGroup -> BudgetTarget.Group(on.groupId)
            SpentOn.Everything, SpentOn.NoCategory -> return { null }
        }
    return { month -> budgetsIn(month, changes)[target] }
}

private const val TAB = "trendsTab"
private const val YEARS_BACK = "trendsYearsBack"
private const val SELECTED = "trendsSelectedMonth"
private const val OPEN_GROUP = "trendsOpenGroupId"
private const val FIRST_FROM = "trendsFirstFrom"
private const val FIRST_TO = "trendsFirstTo"
private const val SECOND_FROM = "trendsSecondFrom"
private const val SECOND_TO = "trendsSecondTo"

private fun wholeMonth(month: YearMonth) = Days(month.atDay(1), month.atEndOfMonth())

private fun thisMonthSoFar(today: LocalDate) = Days(today.withDayOfMonth(1), today)

/** A category or a group deleted since it was chosen falls back to all spending. */
private fun SpentOn.orEverythingUnless(
    groups: List<CategoryGroup>,
    categories: List<Category>,
): SpentOn =
    when (this) {
        is SpentOn.OneCategory -> if (categories.any { it.id == categoryId }) this else SpentOn.Everything
        is SpentOn.OneGroup -> if (groups.any { it.id == groupId }) this else SpentOn.Everything
        SpentOn.Everything, SpentOn.NoCategory -> this
    }

private fun spentOn(
    kind: String,
    id: Long,
): SpentOn =
    when (kind) {
        TRENDS_ON_CATEGORY -> SpentOn.OneCategory(id)
        TRENDS_ON_GROUP -> SpentOn.OneGroup(id)
        TRENDS_ON_NO_CATEGORY -> SpentOn.NoCategory
        else -> SpentOn.Everything
    }

private fun SpentOn.asRoute(): Pair<String, Long> =
    when (this) {
        SpentOn.Everything -> TRENDS_ON_EVERYTHING to 0L
        is SpentOn.OneCategory -> TRENDS_ON_CATEGORY to categoryId
        is SpentOn.OneGroup -> TRENDS_ON_GROUP to groupId
        SpentOn.NoCategory -> TRENDS_ON_NO_CATEGORY to 0L
    }

/** The route that opens the page on [groupId]'s spending, or on all of it. */
fun trendsRoute(groupId: Long? = null): String {
    val (kind, id) = (groupId?.let { SpentOn.OneGroup(it) } ?: SpentOn.Everything).asRoute()
    return "$TRENDS_ROUTE_BASE?$TRENDS_ON_ARG=$kind&$TRENDS_ID_ARG=$id"
}

private const val TRENDS_ROUTE_BASE = "reports/trends"

/** What the page opens on: one of the `TRENDS_ON_` kinds. */
const val TRENDS_ON_ARG = "on"

/** The category's or the group's id, for the kinds that have one. */
const val TRENDS_ID_ARG = "id"

const val TRENDS_ON_EVERYTHING = "everything"

/** The page's route, with what it opens on as optional arguments. */
const val TRENDS_ROUTE = "reports/trends?$TRENDS_ON_ARG={$TRENDS_ON_ARG}&$TRENDS_ID_ARG={$TRENDS_ID_ARG}"
private const val TRENDS_ON_CATEGORY = "category"
private const val TRENDS_ON_GROUP = "group"
private const val TRENDS_ON_NO_CATEGORY = "none"
