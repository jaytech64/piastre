// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.jaytech64.piastre.core.model.Premium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant

class PremiumRepositoryTest {
    /** A real scope: DataStore writes in the scope it is given, and a test scope nobody advances never runs them. */
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var storeFile: File
    private val owned = MutableStateFlow<Premium?>(null)
    private lateinit var repository: PremiumRepository

    @Before
    fun setUp() {
        storeFile = File.createTempFile("settings", ".preferences_pb").apply { delete() }
        repository = newRepository()
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        storeFile.delete()
    }

    private fun newRepository() =
        PremiumRepository(
            source = FakeSource(owned),
            store = PreferenceDataStoreFactory.create(scope = storeScope) { storeFile },
        )

    @Test
    fun `nobody has ever paid when the store has never answered`() =
        runTest {
            assertEquals(Premium.Free, repository.premium.first())
        }

    @Test
    fun `a store that cannot be asked keeps the last answer it gave`() =
        runTest {
            val subscribed = Premium.Subscribed(renews = true, trialEndsAt = Instant.ofEpochMilli(1_791_939_796_706))
            owned.value = subscribed
            assertEquals(subscribed, repository.premium.first())

            owned.value = null
            assertEquals(subscribed, repository.premium.first())
        }

    @Test
    fun `premium ends only when the store says so`() =
        runTest {
            owned.value = Premium.Lifetime
            repository.premium.first()

            owned.value = Premium.Free
            assertEquals(Premium.Free, repository.premium.first())

            owned.value = null
            assertEquals(Premium.Free, repository.premium.first())
        }

    @Test
    fun `a cancelled subscription without a recorded trial survives the round trip`() =
        runTest {
            val cancelled = Premium.Subscribed(renews = false, trialEndsAt = null)
            owned.value = cancelled
            repository.premium.first()

            owned.value = null
            assertEquals(cancelled, repository.premium.first())
        }

    @Test
    fun `a review code survives the round trip`() =
        runTest {
            owned.value = Premium.ReviewCode
            repository.premium.first()

            owned.value = null
            assertEquals(Premium.ReviewCode, repository.premium.first())
        }

    private class FakeSource(
        override val owned: Flow<Premium?>,
    ) : PremiumSource {
        override val donationPage: String? = null
        override val managePage: String? = null
        override val storeName: String = "F-Droid"
        override val offers: Flow<PremiumOffers> = flowOf(PremiumOffers.None)

        override fun refresh() = Unit

        override suspend fun redeem(code: String) = false

        override fun buy(
            activity: Activity,
            offer: PremiumOffer,
        ) = Unit
    }
}
