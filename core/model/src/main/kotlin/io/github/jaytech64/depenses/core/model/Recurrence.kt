// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** How often a recurring transaction comes back. */
enum class Rhythm { WEEKLY, EVERY_TWO_WEEKS, MONTHLY, YEARLY }

/**
 * The days a recurring transaction falls on: [firstDate], then every [rhythm]
 * after it, until [lastDate] when there is one.
 *
 * Every occurrence is counted from [firstDate] rather than from the one before
 * it, so nothing drifts. A bill on the 31st falls on the 30th in a thirty-day
 * month and is back on the 31st the month after, and one on 29 February falls on
 * the 28th in the years that have no 29th. Stepping from the previous occurrence
 * instead would leave it on the 30th, or the 28th, for ever.
 *
 * Days are [LocalDate]s, never instants, so a time zone or a change of clocks
 * cannot move a due day.
 */
data class Recurrence(
    val rhythm: Rhythm,
    val firstDate: LocalDate,
    val lastDate: LocalDate? = null,
) {
    init {
        require(lastDate == null || lastDate >= firstDate) { "a recurrence cannot end before it starts" }
    }

    /** The first day this falls on strictly after [date], or null once it has ended. */
    fun nextAfter(date: LocalDate): LocalDate? {
        if (date < firstDate) return firstDate
        var index = estimatedIndexOf(date)
        while (occurrence(index) <= date) index++
        return occurrence(index).takeUnless { lastDate != null && it > lastDate }
    }

    /** Every day this falls on after [after] and up to [through], in order. */
    fun datesBetween(
        after: LocalDate,
        through: LocalDate,
    ): List<LocalDate> =
        generateSequence(nextAfter(after)) { nextAfter(it) }
            .takeWhile { it <= through }
            .toList()

    private fun occurrence(index: Long): LocalDate =
        when (rhythm) {
            Rhythm.WEEKLY -> firstDate.plusWeeks(index)
            Rhythm.EVERY_TWO_WEEKS -> firstDate.plusWeeks(index * 2)
            Rhythm.MONTHLY -> firstDate.plusMonths(index)
            Rhythm.YEARLY -> firstDate.plusYears(index)
        }

    /**
     * An index at or just below the occurrence on or before [date], so that
     * finding the next one after a date years away is a few steps, not one step
     * per occurrence in between. Month and year lengths vary, so the estimate is
     * stepped back one to stay below rather than risk skipping an occurrence.
     */
    private fun estimatedIndexOf(date: LocalDate): Long {
        val days = date.toEpochDay() - firstDate.toEpochDay()
        val estimate =
            when (rhythm) {
                Rhythm.WEEKLY -> days / DAYS_IN_WEEK
                Rhythm.EVERY_TWO_WEEKS -> days / (DAYS_IN_WEEK * 2)
                Rhythm.MONTHLY -> ChronoUnit.MONTHS.between(firstDate, date)
                Rhythm.YEARLY -> ChronoUnit.YEARS.between(firstDate, date)
            }
        return (estimate - 1).coerceAtLeast(0)
    }

    private companion object {
        const val DAYS_IN_WEEK = 7L
    }
}
