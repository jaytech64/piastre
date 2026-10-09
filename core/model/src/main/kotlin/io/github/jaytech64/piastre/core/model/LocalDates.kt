// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.model

import java.time.Instant
import java.time.ZoneId

/**
 * The day an expense belongs to, as an epoch day in the zone it was spent in.
 *
 * Reports store this denormalised so that "spent today" is an integer comparison
 * on an indexed column rather than a timezone conversion per row — and so that
 * travelling to another zone cannot silently re-bucket last month's coffee.
 */
fun localDateEpochDay(
    occurredAtUtcMillis: Long,
    zoneId: ZoneId,
): Int =
    Instant
        .ofEpochMilli(occurredAtUtcMillis)
        .atZone(zoneId)
        .toLocalDate()
        .toEpochDay()
        .toInt()
