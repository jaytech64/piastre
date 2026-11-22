// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import io.github.jaytech64.piastre.core.model.CaptureHealth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** What an empty ledger promises about the next payment. */
@RunWith(RobolectricTestRunner::class)
class LedgerEmptyBodyTest : LedgerTest() {
    @Test
    fun `an empty ledger promises nothing before capture has answered`() {
        assertNull(emptyLedgerBody(null))
    }

    @Test
    fun `a listening capture promises the payment will appear on its own`() {
        assertEquals(R.string.ledger_empty_body, emptyLedgerBody(CaptureHealth.Listening(null)))
    }

    @Test
    fun `access never granted makes no promise and says where to allow it`() {
        assertEquals(R.string.ledger_empty_body_no_access, emptyLedgerBody(CaptureHealth.AccessNeverGranted))
    }

    @Test
    fun `revoked access makes no promise and says where to allow it`() {
        assertEquals(R.string.ledger_empty_body_no_access, emptyLedgerBody(CaptureHealth.AccessRevoked(HEARD_AT)))
    }

    @Test
    fun `paused capture says it is paused, with or without a heartbeat`() {
        assertEquals(R.string.ledger_empty_body_paused, emptyLedgerBody(CaptureHealth.Paused(null)))
        assertEquals(R.string.ledger_empty_body_paused, emptyLedgerBody(CaptureHealth.Paused(HEARD_AT)))
    }

    @Test
    fun `a stalled listener keeps the usual words under the banner that reports it`() {
        assertEquals(R.string.ledger_empty_body, emptyLedgerBody(CaptureHealth.Silent(HEARD_AT)))
    }

    @Test
    fun `the ledger follows access being granted while it is open`() =
        runTest {
            captureHealth.value = CaptureHealth.AccessNeverGranted
            assertEquals(
                CaptureHealth.AccessNeverGranted,
                viewModel.captureHealth.first { it == CaptureHealth.AccessNeverGranted },
            )

            captureHealth.value = CaptureHealth.Listening(null)

            assertEquals(
                CaptureHealth.Listening(null),
                viewModel.captureHealth.first { it is CaptureHealth.Listening },
            )
        }

    private companion object {
        val HEARD_AT: Instant = Instant.parse("2026-11-20T12:00:00Z")
    }
}
