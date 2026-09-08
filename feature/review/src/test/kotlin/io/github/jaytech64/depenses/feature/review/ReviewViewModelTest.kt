// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.review

import app.cash.turbine.test
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.NotificationCapture
import io.github.jaytech64.depenses.core.data.ParsePipeline
import io.github.jaytech64.depenses.core.data.RawEventRepository
import io.github.jaytech64.depenses.core.data.ReviewRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.ReviewReason
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import io.github.jaytech64.depenses.parsing.GoogleWalletParser
import io.github.jaytech64.depenses.parsing.ParserRegistry
import io.github.jaytech64.depenses.parsing.RawNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
class ReviewViewModelTest {
    private lateinit var database: DepensesDatabase
    private lateinit var capture: NotificationCapture
    private lateinit var viewModel: ReviewViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        val merchants = MerchantRepository(database.merchantDao())
        capture =
            NotificationCapture(
                RawEventRepository(database.rawNotificationEventDao()),
                ParsePipeline(
                    events = database.rawNotificationEventDao(),
                    transactions = database.transactionDao(),
                    registry = ParserRegistry(listOf(GoogleWalletParser())),
                    merchants = merchants,
                    zoneId = Provider { ZONE },
                ),
            )
        viewModel =
            ReviewViewModel(
                ReviewRepository(
                    transactions = database.transactionDao(),
                    merchants = merchants,
                    registry = ParserRegistry(listOf(GoogleWalletParser())),
                    clock = Clock.fixed(Instant.ofEpochMilli(RULED_AT), ZONE),
                ),
            )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an empty queue says so instead of showing a blank list`() =
        runTest {
            viewModel.uiState.test {
                assertTrue(settled().isEmpty)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a purchase the parser could not name waits with its reason and its raw text`() =
        runTest {
            captureUnnamedMerchant()

            viewModel.uiState.test {
                val item = settled().items.single()

                assertEquals(ReviewReason.MISSING_MERCHANT, item.reason)
                assertEquals(UNNAMED_TEXT, item.rawText)
                assertEquals(10_200L, item.transaction.amount.minor)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `confirming empties the queue`() =
        runTest {
            captureUnnamedMerchant()

            viewModel.uiState.test {
                val item = settled().items.single()
                viewModel.confirm(item.transaction.id)
                advanceUntilIdle()

                assertTrue(awaitItem().items.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a correction leaves the queue and keeps what was typed`() =
        runTest {
            captureUnnamedMerchant()
            var id = 0L

            viewModel.uiState.test {
                id =
                    settled()
                        .items
                        .single()
                        .transaction.id
                viewModel.correct(id, Money(1_250, Money.CAD), "Dépanneur du coin")
                advanceUntilIdle()

                assertTrue(awaitItem().items.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }

            val corrected = database.transactionDao().byId(id)
            assertEquals(1_250L, corrected?.amountMinor)
            assertNotNull(corrected?.merchantId)
        }

    /** The first emission is the placeholder the flow starts with; the next is real. */
    private suspend fun app.cash.turbine.TurbineTestContext<ReviewUiState>.settled(): ReviewUiState {
        var state = awaitItem()
        while (state.loading) state = awaitItem()
        return state
    }

    private suspend fun captureUnnamedMerchant() =
        capture.capture(
            RawNotification(
                sourcePackage = "com.google.android.apps.walletnfcrel",
                notificationKey = "0|com.google.android.apps.walletnfcrel|1001|tag-77|10348",
                postedAtUtcMillis = POSTED_AT,
                deviceLocale = "fr-CA",
                title = null,
                text = UNNAMED_TEXT,
            ),
            extrasJson = "{}",
        )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        const val UNNAMED_TEXT = "102,00 $ avec Carte d'accès Desrives ••0000"
        const val POSTED_AT = 1_788_740_166_564L
        const val RULED_AT = 1_788_750_000_000L
    }
}
