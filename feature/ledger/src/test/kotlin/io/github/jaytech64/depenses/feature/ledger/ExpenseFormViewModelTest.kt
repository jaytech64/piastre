// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.depenses.core.data.CardRepository
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.TransactionEditor
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Payment
import io.github.jaytech64.depenses.core.model.PaymentMethod
import io.github.jaytech64.depenses.core.model.TransactionKind
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
import java.time.Clock
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
class ExpenseFormViewModelTest {
    private lateinit var database: DepensesDatabase
    private lateinit var repository: TransactionRepository
    private lateinit var merchants: MerchantRepository
    private lateinit var viewModel: ExpenseFormViewModel

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

    private fun newViewModel(editing: Long? = null) =
        ExpenseFormViewModel(
            savedStateHandle = SavedStateHandle(editing?.let { mapOf(EXPENSE_ID_ARG to it) } ?: emptyMap()),
            transactions = repository,
            editor = TransactionEditor(database.transactionDao(), merchants, Clock.systemUTC()),
            merchants = merchants,
            cards = CardRepository(database.cardDao()),
            zoneId = Provider { ZONE },
        )

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
            viewModel.onPaymentChange(Payment.Cash)
            viewModel.onDateChange(LocalDate.of(2026, 9, 7))
            viewModel.onTimeChange(LocalTime.of(9, 41))

            viewModel.save()
            awaitDone()

            val stored = repository.confirmed().first().single()
            assertEquals(1_250L, stored.amount.minor)
            assertEquals("Dépanneur du coin", stored.displayMerchant)
            assertEquals(TransactionSource.MANUAL, stored.source)
            assertEquals(TransactionStatus.CONFIRMED, stored.status)
            assertEquals("Argent comptant", stored.note)
            assertEquals(Payment.Cash, stored.payment)
            assertEquals(LocalDate.of(2026, 9, 7), stored.localDate)
        }

    @Test
    fun `a typed merchant becomes one the app can offer back`() =
        runTest {
            viewModel.onAmountChange("6,25")
            viewModel.onMerchantChange("Café Olimpico")
            viewModel.save()
            awaitDone()

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
                awaitDone()
            }

            assertEquals(2, repository.confirmed().first().size)
        }

    @Test
    fun `editing opens on the purchase as stored, in the zone it was made in`() =
        runTest {
            val id = addCashPurchase()

            viewModel = newViewModel(editing = id)
            val state = viewModel.uiState.first { !it.loading }

            assertTrue(state.isEditing)
            assertEquals(Formatters.amountInput(Money(1_250, Money.CAD)), state.amountText)
            assertEquals("Dépanneur du coin", state.merchantText)
            assertEquals("Argent comptant", state.note)
            assertEquals(LocalDate.of(2026, 9, 7), state.date)
            assertEquals(LocalTime.of(9, 41), state.time)
        }

    @Test
    fun `saving an edit rewrites the purchase rather than adding another`() =
        runTest {
            val id = addCashPurchase()
            viewModel = newViewModel(editing = id)
            viewModel.uiState.first { !it.loading }

            viewModel.onAmountChange("13,75")
            viewModel.onDateChange(LocalDate.of(2026, 9, 6))
            viewModel.save()
            awaitDone()

            val stored = repository.confirmed().first().single()
            assertEquals(id, stored.id)
            assertEquals(1_375L, stored.amount.minor)
            assertEquals(LocalDate.of(2026, 9, 6), stored.localDate)
            assertEquals("Dépanneur du coin", stored.displayMerchant)
        }

    @Test
    fun `an edit saved untouched leaves the purchase exactly as it was`() =
        runTest {
            val id = addCashPurchase()
            val before = repository.byId(id)
            viewModel = newViewModel(editing = id)
            viewModel.uiState.first { !it.loading }

            viewModel.save()
            awaitDone()

            assertEquals(before, repository.byId(id))
        }

    @Test
    fun `deleting from the form takes the purchase out of the ledger`() =
        runTest {
            val id = addCashPurchase()
            viewModel = newViewModel(editing = id)
            viewModel.uiState.first { !it.loading }

            viewModel.delete()
            awaitDone()

            assertEquals(emptyList<Any>(), repository.confirmed().first())
        }

    @Test
    fun `a purchase that is gone by the time the form opens closes the form`() =
        runTest {
            viewModel = newViewModel(editing = MISSING_ID)

            awaitDone()
        }

    @Test
    fun `money received is saved as income`() =
        runTest {
            viewModel.onKindChange(TransactionKind.INCOME)
            viewModel.onAmountChange("150,00")
            viewModel.onMerchantChange("Acme inc.")
            viewModel.save()
            awaitDone()

            val stored = repository.confirmed().first().single()
            assertEquals(TransactionKind.INCOME, stored.kind)
            assertEquals(15_000L, stored.amount.minor)
        }

    @Test
    fun `editing opens on the kind that was saved`() =
        runTest {
            viewModel.onKindChange(TransactionKind.INCOME)
            viewModel.onAmountChange("150,00")
            viewModel.save()
            awaitDone()
            val id =
                repository
                    .confirmed()
                    .first()
                    .single()
                    .id

            viewModel = newViewModel(editing = id)

            assertEquals(TransactionKind.INCOME, viewModel.uiState.first { !it.loading }.kind)
        }

    @Test
    fun `a card added from the form is picked and paid with`() =
        runTest {
            viewModel.onAmountChange("6,25")
            viewModel.addCard(name = "Amex papier", last4 = "8484")
            viewModel.uiState.first { it.payment is Payment.ByCard }

            viewModel.save()
            awaitDone()

            val stored = repository.confirmed().first().single()
            assertEquals(PaymentMethod.CARD, stored.paymentMethod)
            assertEquals("Amex papier", stored.cardName)
        }

    /** Income has no Paid with field, so what it said before the switch is not kept. */
    @Test
    fun `switching to income drops what it was paid with`() =
        runTest {
            viewModel.onAmountChange("150,00")
            viewModel.onPaymentChange(Payment.Cash)
            viewModel.onKindChange(TransactionKind.INCOME)

            viewModel.save()
            awaitDone()

            assertNull(
                repository
                    .confirmed()
                    .first()
                    .single()
                    .paymentMethod,
            )
        }

    @Test
    fun `editing opens on what it was paid with`() =
        runTest {
            val id = addCashPurchase()

            viewModel = newViewModel(editing = id)

            assertEquals(Payment.Cash, viewModel.uiState.first { !it.loading }.payment)
        }

    private suspend fun addCashPurchase(): Long =
        repository.addManual(
            amount = Money(1_250, Money.CAD),
            merchantName = "Dépanneur du coin",
            occurredAt =
                LocalDate
                    .of(2026, 9, 7)
                    .atTime(9, 41)
                    .atZone(ZONE)
                    .toInstant(),
            note = "Argent comptant",
            kind = TransactionKind.EXPENSE,
            payment = Payment.Cash,
        )

    /**
     * Room's writes run on its own executor, off the test scheduler, so the flag is
     * awaited rather than assumed to be set by the time the dispatcher goes idle.
     */
    private suspend fun awaitDone() {
        viewModel.uiState.first { it.done }
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        const val MISSING_ID = 404L
    }
}
