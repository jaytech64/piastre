// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Whole days from [start] to [endInclusive], both included: what a report can
 * be asked about.
 *
 * A [ReportPeriod] is one, snapped to a day, a week or a month. [Days] is any
 * other, such as the two spans a comparison sets side by side.
 */
interface DaySpan {
    val start: LocalDate
    val endInclusive: LocalDate
}

/** Any run of days, which need not start or end on a unit's boundary. */
data class Days(
    override val start: LocalDate,
    override val endInclusive: LocalDate,
) : DaySpan {
    init {
        require(start <= endInclusive) { "A span of days cannot end before it starts: $start to $endInclusive" }
    }
}

/**
 * How many of the span's days have come by [today], today included, and so
 * how many days a figure counted over it has had to add up: none for a span
 * that has not started, and all of them for one that is over.
 */
fun DaySpan.daysThrough(today: LocalDate): Long =
    (ChronoUnit.DAYS.between(start, minOf(endInclusive, today)) + 1).coerceAtLeast(0)
