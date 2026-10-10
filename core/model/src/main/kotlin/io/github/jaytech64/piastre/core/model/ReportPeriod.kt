// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** The three spans a report is read over. */
enum class PeriodUnit { DAY, WEEK, MONTH }

/**
 * A span of days a report answers for, anchored to the day it starts on.
 *
 * [start] is always snapped to the unit — a Monday for a week, the first of the
 * month for a month — so two periods of the same unit are equal exactly when they
 * cover the same days, and stepping never drifts off the boundary.
 */
data class ReportPeriod(
    val unit: PeriodUnit,
    val start: LocalDate,
) {
    val endInclusive: LocalDate
        get() =
            when (unit) {
                PeriodUnit.DAY -> start
                PeriodUnit.WEEK -> start.plusDays(DAYS_IN_WEEK - 1)
                PeriodUnit.MONTH -> start.withDayOfMonth(start.lengthOfMonth())
            }

    val month: YearMonth get() = YearMonth.from(start)

    fun previous(): ReportPeriod = shiftedBy(-1)

    fun next(): ReportPeriod = shiftedBy(1)

    operator fun contains(date: LocalDate): Boolean = date >= start && date <= endInclusive

    /**
     * Months are stepped as months rather than as a day count, so a period never
     * lands on the 31st of a month that has thirty days.
     */
    private fun shiftedBy(units: Long): ReportPeriod =
        copy(
            start =
                when (unit) {
                    PeriodUnit.DAY -> start.plusDays(units)
                    PeriodUnit.WEEK -> start.plusWeeks(units)
                    PeriodUnit.MONTH -> start.plusMonths(units)
                },
        )

    companion object {
        /**
         * The week starts on Monday, overriding what CLDR gives `fr_CA` and `en_CA`.
         *
         * Both locales report Sunday, and neither matches how a week is printed or
         * spoken in Quebec. This is the only place the choice is made, so a report
         * and the calendar grid beside it cannot disagree about where a week begins.
         */
        val WEEK_STARTS_ON: DayOfWeek = DayOfWeek.MONDAY

        /** The period of [unit] that [date] falls in, snapped to the unit's boundary. */
        fun containing(
            unit: PeriodUnit,
            date: LocalDate,
        ): ReportPeriod =
            ReportPeriod(
                unit = unit,
                start =
                    when (unit) {
                        PeriodUnit.DAY -> date
                        PeriodUnit.WEEK -> date.minusDays(daysSinceWeekStart(date))
                        PeriodUnit.MONTH -> date.withDayOfMonth(1)
                    },
            )

        private fun daysSinceWeekStart(date: LocalDate): Long =
            Math
                .floorMod(
                    date.dayOfWeek.value - WEEK_STARTS_ON.value,
                    DAYS_IN_WEEK.toInt(),
                ).toLong()

        private const val DAYS_IN_WEEK = 7L
    }
}
