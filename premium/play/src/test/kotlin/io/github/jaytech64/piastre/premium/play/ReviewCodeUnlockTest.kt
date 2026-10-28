// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.play

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.jaytech64.piastre.core.model.Premium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ReviewCodeUnlockTest {
    /** A real scope: DataStore writes in the scope it is given, and a test scope nobody advances never runs them. */
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var storeFile: File
    private lateinit var store: DataStore<Preferences>

    @Before
    fun setUp() {
        storeFile = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        store = PreferenceDataStoreFactory.create(scope = storeScope) { storeFile }
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        storeFile.delete()
    }

    private fun unlock(sha256: String = TEST_SHA256) = ReviewCodeUnlock(store, sha256)

    @Test
    fun `the review code turns premium on`() =
        runTest {
            val unlock = unlock()
            assertFalse(unlock.entered.first())

            assertTrue(unlock.redeem(TEST_CODE))
            assertTrue(unlock.entered.first())
        }

    @Test
    fun `the code is read however it is typed`() =
        runTest {
            assertTrue(unlock().redeem(" 7k2m-9pqr-4xtv-0h1w "))
        }

    @Test
    fun `letters that look like digits read as those digits`() =
        runTest {
            assertTrue(unlock().redeem("7K2M9PQR4XTVOHIW"))
            assertTrue(unlock().redeem("7K2M9PQR4XTVoHLW"))
        }

    @Test
    fun `a code one character off unlocks nothing`() =
        runTest {
            val unlock = unlock()
            assertFalse(unlock.redeem("7K2M-9PQR-4XTV-0H1X"))
            assertFalse(unlock.redeem(""))
            assertFalse(unlock.entered.first())
        }

    @Test
    fun `an unlock survives the app being restarted`() =
        runTest {
            unlock().redeem(TEST_CODE)
            storeScope.cancel()

            val restartedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val restarted = PreferenceDataStoreFactory.create(scope = restartedScope) { storeFile }
                assertTrue(ReviewCodeUnlock(restarted, TEST_SHA256).entered.first())
            } finally {
                restartedScope.cancel()
            }
        }

    @Test
    fun `a release with a new code revokes the old unlock`() =
        runTest {
            unlock().redeem(TEST_CODE)

            assertFalse(unlock(sha256 = ReviewCodeUnlock.sha256("ANOTHERCODE00000")).entered.first())
        }

    @Test
    fun `the shipped hash is a sha-256 and not a code`() {
        assertTrue(Regex("[0-9a-f]{64}").matches(ReviewCodeUnlock.REVIEW_CODE_SHA256))
    }

    @Test
    fun `a review code turns on what play left off`() {
        assertEquals(Premium.ReviewCode, withReviewCode(Premium.Free, codeEntered = true))
        assertEquals(Premium.ReviewCode, withReviewCode(null, codeEntered = true))
    }

    @Test
    fun `what play says was bought wins over a review code`() {
        val subscribed = Premium.Subscribed(renews = true, trialEndsAt = null)
        assertEquals(subscribed, withReviewCode(subscribed, codeEntered = true))
        assertEquals(Premium.Lifetime, withReviewCode(Premium.Lifetime, codeEntered = true))
    }

    @Test
    fun `without a review code play has the only say`() {
        assertEquals(Premium.Free, withReviewCode(Premium.Free, codeEntered = false))
        assertNull(withReviewCode(null, codeEntered = false))
    }

    private companion object {
        /** A code made up for these tests; the real one never enters the repository. */
        const val TEST_CODE = "7K2M-9PQR-4XTV-0H1W"
        val TEST_SHA256 = ReviewCodeUnlock.sha256("7K2M9PQR4XTV0H1W")
    }
}
