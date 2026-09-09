// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Provider

/**
 * Cash and Interac, entered by hand and read back out of a real database.
 *
 * The round trip is the test: an amount typed the way a Quebec phone types it
 * has to survive parsing, storage and the day bucket without drifting a cent or
 * a day.
 */
@RunWith(RobolectricTestRunner::class)
class ManualEntryViewModelTest {
    private lateinit var database: DepensesDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var merchants: MerchantRepository
    private lateinit var viewModel: ManualEntryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        merchants = MerchantRepository(database.merchantDao())
        repository =
            TransactionRepository(
                transactions = database.transactionDao(),
                reports = database.reportDao(),
                merchants = merchants,
                zoneId = Provider { ZONE },
            )
        viewModel = newViewModel()
    }

    private fun newViewModel() = ManualEntryViewModel(repository, merchants, Provider { ZONE })

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an amount is required and nothing else is`() {
        assertFalse(viewModel.uiState.value.canSave)

        viewModel.onAmountChange("12,50")

        assertTrue(viewModel.uiState.value.canSave)
    }

    @Test
    fun `half-typed input is not yet an amount`() {
        viewModel.onAmountChange("abc")

        assertNull(viewModel.uiState.value.amount)
        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test
    fun `a cash purchase lands in the ledger as a manual row`() =
        runTest {
            viewModel.onAmountChange("12,50")
            viewModel.onMerchantChange("Dépanneur du coin")
            viewModel.onNoteChange("Argent comptant")
            viewModel.onDateChange(LocalDate.of(2026, 9, 7))
            viewModel.onTimeChange(LocalTime.of(9, 41))

            viewModel.save()
            awaitSaved()

            val stored = repository.confirmed().first().single()
            assertEquals(1_250L, stored.amount.minor)
            assertEquals("Dépanneur du coin", stored.displayMerchant)
            assertEquals(TransactionSource.MANUAL, stored.source)
            assertEquals(TransactionStatus.CONFIRMED, stored.status)
            assertEquals("Argent comptant", stored.note)
            assertEquals(LocalDate.of(2026, 9, 7), stored.localDate)
        }

    @Test
    fun `a typed merchant becomes one the app can offer back`() =
        runTest {
            viewModel.onAmountChange("6,25")
            viewModel.onMerchantChange("Café Olimpico")
            viewModel.save()
            awaitSaved()

            assertNotNull(database.merchantDao().byExactAlias("Café Olimpico"))
        }

    /**
     * Two coffees at the same counter within a minute. Notification rows are
     * de-duplicated on a source key; a manual row must not be, or the second one
     * would silently replace the first.
     */
    @Test
    fun `the same purchase entered twice is two purchases`() =
        runTest {
            repeat(2) {
                // A fresh view model each time, because that is what reopening the
                // screen gives you — and the second entry must not find the first.
                viewModel = newViewModel()
                viewModel.onAmountChange("4,00")
                viewModel.onMerchantChange("Le Couteau")
                viewModel.save()
                awaitSaved()
            }

            assertEquals(2, repository.confirmed().first().size)
        }

    /**
     * Room's writes run on its own executor, off the test scheduler, so the flag is
     * awaited rather than assumed to be set by the time the dispatcher goes idle.
     */
    private suspend fun awaitSaved() {
        viewModel.uiState.first { it.saved }
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
    }
}
