// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.model

import java.time.LocalDate
import java.time.YearMonth

/** What a budget is on. A transaction never carries a group, so a group's spending is its categories'. */
sealed interface BudgetTarget {
    data class Category(
        val id: Long,
    ) : BudgetTarget

    data class Group(
        val id: Long,
    ) : BudgetTarget
}

/**
 * A budget set, changed or stopped on [target] from [from] on, until a later
 * change. [amount] is null for a stop. A month is judged against the change in
 * force on it, so changing an amount never rewrites a month already judged.
 */
data class BudgetChange(
    val target: BudgetTarget,
    val from: YearMonth,
    val amount: Money?,
)

/**
 * The amount each target is judged against in [month]: its latest change on or
 * before it, unless that change stopped it.
 */
fun budgetsIn(
    month: YearMonth,
    changes: List<BudgetChange>,
): Map<BudgetTarget, Money> =
    changes
        .filter { it.from <= month }
        .groupBy { it.target }
        .mapNotNull { (target, own) -> own.maxBy { it.from }.amount?.let { target to it } }
        .toMap()

/**
 * What can still be spent each day for the rest of [month], today included, or
 * null when nothing is left or [today] is not in it: a past month has no days
 * left, and a coming one has not started.
 */
fun leftPerDay(
    left: Money,
    month: YearMonth,
    today: LocalDate,
): Money? {
    if (left.minor <= 0 || YearMonth.from(today) != month) return null
    val daysLeft = month.lengthOfMonth() - today.dayOfMonth + 1
    return left.copy(minor = left.minor / daysLeft)
}
