// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.BudgetDao
import io.github.jaytech64.piastre.core.database.BudgetEntity
import io.github.jaytech64.piastre.core.model.BudgetChange
import io.github.jaytech64.piastre.core.model.BudgetTarget
import io.github.jaytech64.piastre.core.model.Category
import io.github.jaytech64.piastre.core.model.CategoryGroup
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.PeriodUnit
import io.github.jaytech64.piastre.core.model.ReportPeriod
import io.github.jaytech64.piastre.core.model.budgetsIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** One budget in one month: what it is on, the amount it is judged against, and what went out against it. */
data class BudgetLine(
    val target: BudgetTarget,
    val name: String,
    /** The group a category budget is in now, or the group itself for a group budget. */
    val group: CategoryGroup?,
    val amount: Money,
    val spent: Money,
) {
    val left: Money get() = amount + -spent
}

/** Every budget in force in [month], and how many rows in another currency they could not count. */
data class BudgetMonth(
    val month: YearMonth,
    val lines: List<BudgetLine>,
    val otherCurrencyCount: Int,
)

/**
 * Budgets, and what was spent against them.
 *
 * Spending is exactly what the report's category ranking counts, read from the
 * same query, so the two can never disagree: money out and never income
 * (ADR-0007), a split under each part's category (ADR-0019), nothing waiting for
 * review (ADR-0002), nothing dated after today (ADR-0015), Canadian dollars only
 * with the rest counted aside (ADR-0012). A group's spending is its categories'
 * as they are grouped now, so moving a category moves its history (ADR-0016).
 */
@Singleton
class BudgetRepository
    @Inject
    constructor(
        private val budgets: BudgetDao,
        private val reports: ReportRepository,
        private val categories: CategoryRepository,
        private val groups: CategoryGroupRepository,
    ) {
        fun month(
            month: YearMonth,
            today: LocalDate,
        ): Flow<BudgetMonth> {
            val period = ReportPeriod(PeriodUnit.MONTH, month.atDay(1))
            return combine(
                budgets.all(),
                reports.categorySpending(period, today),
                categories.all(),
                groups.all(),
                reports.otherCurrencyCount(period, today),
            ) { rows, spending, categoryList, groupList, otherCurrencies ->
                val byCategory = categoryList.associate { it.category.id to it.category }
                val byGroup = groupList.associateBy { it.id }
                val lines =
                    budgetsIn(month, rows.map { it.toChange() }).mapNotNull { (target, amount) ->
                        when (target) {
                            is BudgetTarget.Category -> {
                                byCategory[target.id]?.let { category ->
                                    BudgetLine(
                                        target,
                                        category.name,
                                        category.groupId?.let(byGroup::get),
                                        amount,
                                        spending.spentOn(category, amount.currency),
                                    )
                                }
                            }

                            is BudgetTarget.Group -> {
                                byGroup[target.id]?.let { group ->
                                    BudgetLine(
                                        target,
                                        group.name,
                                        group,
                                        amount,
                                        spending.spentIn(group, amount.currency),
                                    )
                                }
                            }
                        }
                    }
                BudgetMonth(month, lines, otherCurrencies)
            }
        }

        /** Every change ever made, for the export. */
        fun changes(): Flow<List<BudgetChange>> = budgets.all().map { rows -> rows.map { it.toChange() } }

        /** Sets [target]'s budget to [amount] from [from] on; a second change in the same month replaces the first. */
        suspend fun set(
            target: BudgetTarget,
            amount: Money,
            from: YearMonth,
        ) = budgets.set(BudgetChange(target, from, amount).toEntity())

        /** Stops [target]'s budget from [from] on; the months before keep it. */
        suspend fun stop(
            target: BudgetTarget,
            from: YearMonth,
            currency: String = Money.CAD,
        ) = budgets.set(BudgetEntity(0, target.kind, target.id, from.index, null, currency))

        private fun List<CategorySpending>.spentOn(
            category: Category,
            currency: String,
        ) = Money(filter { it.category?.id == category.id }.sumOf { it.total.minor }, currency)

        private fun List<CategorySpending>.spentIn(
            group: CategoryGroup,
            currency: String,
        ) = Money(filter { it.group?.id == group.id }.sumOf { it.total.minor }, currency)
    }

internal fun BudgetEntity.toChange() =
    BudgetChange(
        target = if (targetKind == GROUP) BudgetTarget.Group(targetId) else BudgetTarget.Category(targetId),
        from = YearMonth.of(fromMonth / MONTHS_PER_YEAR, fromMonth % MONTHS_PER_YEAR + 1),
        amount = amountMinor?.let { Money(it, currency) },
    )

internal fun BudgetChange.toEntity() =
    BudgetEntity(0, target.kind, target.id, from.index, amount?.minor, amount?.currency ?: Money.CAD)

internal val BudgetTarget.kind: String get() = if (this is BudgetTarget.Group) GROUP else CATEGORY

internal val BudgetTarget.id: Long
    get() =
        when (this) {
            is BudgetTarget.Category -> id
            is BudgetTarget.Group -> id
        }

/** How `budget.fromMonth` counts months: from January of year zero. */
internal val YearMonth.index: Int get() = year * MONTHS_PER_YEAR + monthValue - 1

private const val CATEGORY = BUDGET_ON_CATEGORY
private const val GROUP = BUDGET_ON_GROUP
private const val MONTHS_PER_YEAR = 12
