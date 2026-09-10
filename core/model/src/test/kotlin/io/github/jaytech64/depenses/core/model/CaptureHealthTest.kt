// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureHealthTest {
    @Test
    fun `a listener that was heard from recently is healthy`() {
        val heard = NOW.minusSeconds(60)

        assertEquals(CaptureHealth.Listening(heard), health(lastEventAt = heard))
    }

    @Test
    fun `revoked access is reported even though the last event is still recent`() {
        assertEquals(
            CaptureHealth.AccessRevoked(NOW.minusSeconds(60)),
            health(accessGranted = false, lastEventAt = NOW.minusSeconds(60)),
        )
    }

    @Test
    fun `capture turned off outranks revoked access`() {
        assertEquals(
            CaptureHealth.Paused(NOW),
            health(accessGranted = false, captureEnabled = false),
        )
    }

    @Test
    fun `silence past the threshold is an alarm, and one second short of it is not`() {
        val threshold = NOW.minus(CaptureHealth.SILENT_AFTER)

        assertEquals(CaptureHealth.Silent(threshold), health(lastEventAt = threshold))
        assertEquals(
            CaptureHealth.Listening(threshold.plusSeconds(1)),
            health(lastEventAt = threshold.plusSeconds(1)),
        )
    }

    @Test
    fun `a listener that has never bound waits instead of accusing`() {
        assertEquals(CaptureHealth.Listening(null), health(lastEventAt = null))
    }

    @Test
    fun `only the two broken states ask for attention`() {
        assertTrue(CaptureHealth.AccessRevoked(NOW).needsAttention)
        assertTrue(CaptureHealth.Silent(NOW).needsAttention)
        assertFalse(CaptureHealth.Paused(NOW).needsAttention)
        assertFalse(CaptureHealth.Listening(NOW).needsAttention)
    }

    private fun health(
        accessGranted: Boolean = true,
        captureEnabled: Boolean = true,
        lastEventAt: Instant? = NOW,
    ) = CaptureHealth.of(accessGranted, captureEnabled, lastEventAt, NOW)

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-09T14:00:00Z")
    }
}
