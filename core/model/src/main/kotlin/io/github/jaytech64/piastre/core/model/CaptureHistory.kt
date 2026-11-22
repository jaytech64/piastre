// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.model

import java.time.Instant
import java.time.ZoneId

/**
 * Every time Wallet reported one purchase, oldest first, to be read in [zoneId],
 * the zone the purchase is dated in.
 *
 * [posts] is empty once the raw log has been cleared: the purchase stays, and
 * what it was rebuilt from does not.
 */
data class CaptureHistory(
    val posts: List<Instant>,
    val zoneId: ZoneId,
)
