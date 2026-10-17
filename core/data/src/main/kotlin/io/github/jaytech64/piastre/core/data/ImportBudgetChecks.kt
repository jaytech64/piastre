// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.BudgetEntity
import java.time.YearMonth
import java.time.format.DateTimeParseException

internal const val BUDGET_ON_CATEGORY = "CATEGORY"
internal const val BUDGET_ON_GROUP = "GROUP"

/**
 * Every budget in the file is on a category or group the file lists, from a
 * month it can read, and none appears twice.
 */
internal fun ExportDocument.checkBudgets() {
    val categoryIds = categories.map { it.id }.toSet()
    val groupIds = categoryGroups.map { it.id }.toSet()
    if (budgets.map { Triple(it.targetKind, it.targetId, it.from) }.toSet().size != budgets.size) {
        inconsistent("a budget is set twice for the same month")
    }
    budgets.forEach { budget ->
        val problem =
            when {
                budget.targetKind == BUDGET_ON_CATEGORY && budget.targetId !in categoryIds -> {
                    "a category not in the file"
                }

                budget.targetKind == BUDGET_ON_GROUP && budget.targetId !in groupIds -> {
                    "a group not in the file"
                }

                budget.targetKind !in setOf(BUDGET_ON_CATEGORY, BUDGET_ON_GROUP) -> {
                    "target '${budget.targetKind}'"
                }

                budget.month() == null -> {
                    "month '${budget.from}'"
                }

                budget.currency.length != CURRENCY_CODE_LENGTH -> {
                    "currency '${budget.currency}'"
                }

                else -> {
                    return@forEach
                }
            }
        inconsistent("a budget has $problem")
    }
}

private fun ExportedBudget.month(): YearMonth? =
    try {
        YearMonth.parse(from)
    } catch (unreadable: DateTimeParseException) {
        null
    }

internal fun ExportedBudget.toEntity(targetId: Long) =
    BudgetEntity(
        targetKind = targetKind,
        targetId = targetId,
        fromMonth = requireNotNull(month()) { "checked by checkBudgets" }.index,
        amountMinor = amountMinor,
        currency = currency,
    )
