// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import androidx.lifecycle.SavedStateHandle
import io.github.jaytech64.piastre.core.data.AccountRepository
import io.github.jaytech64.piastre.core.data.CategoryRepository
import io.github.jaytech64.piastre.core.data.MerchantRepository
import io.github.jaytech64.piastre.core.data.RecurringRepository
import io.github.jaytech64.piastre.core.data.TransactionEditor
import io.github.jaytech64.piastre.core.data.TransactionRepository
import io.github.jaytech64.piastre.core.database.DepensesDatabase
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Rhythm
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionSource
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** The form's *Repeats* field, on a clock that says it is Sunday 27 September 2026. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExpenseFormRepeatTest {
    private lateinit var database: DepensesDatabase
    private lateinit var transactions: TransactionRepository
    private lateinit var recurring: RecurringRepository
    private lateinit var viewModel: ExpenseFormViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        val categories = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
        transactions =
            TransactionRepository(
                database,
                database.transactionDao(),
                MerchantRepository(database.merchantDao()),
                categories,
                Provider { ZONE },
            )
        recurring = database.recurringRepository(CLOCK, ZONE)
        viewModel = open()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a bill set to repeat from today is saved once, and comes back next month`() =
        runTest {
            viewModel.onAmountChange("85,00")
            viewModel.onMerchantChange("Vidéotron")
            viewModel.repeat.onRhythmChange(Rhythm.MONTHLY)

            viewModel.save()
            viewModel.uiState.first { it.done }

            assertEquals(1, transactions.confirmed().first().size)
            assertEquals(
                LocalDate.of(2026, 10, 27),
                recurring
                    .all()
                    .first()
                    .single()
                    .nextDue,
            )
        }

    @Test
    fun `a start in the past asks first, with the count, and writes nothing until you agree`() =
        runTest {
            viewModel.onAmountChange("1 450,00")
            viewModel.onMerchantChange("Loyer")
            viewModel.onDateChange(LocalDate.of(2026, 6, 1))
            viewModel.repeat.onRhythmChange(Rhythm.MONTHLY)

            viewModel.save()
            advanceUntilIdle()

            assertEquals(
                Backfill(3, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 1)),
                viewModel.uiState.value.backfill,
            )
            assertTrue(transactions.confirmed().first().isEmpty())

            viewModel.save(pastDaysAgreed = true)
            viewModel.uiState.first { it.done }

            assertEquals(4, transactions.confirmed().first().size)
        }

    @Test
    fun `saying no to the rows for past days saves nothing and keeps the form`() =
        runTest {
            viewModel.onAmountChange("1 450,00")
            viewModel.onDateChange(LocalDate.of(2026, 8, 1))
            viewModel.repeat.onRhythmChange(Rhythm.MONTHLY)
            viewModel.save()

            viewModel.repeat.dismissBackfill()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.backfill)
            assertFalse(viewModel.uiState.value.done)
            assertTrue(transactions.confirmed().first().isEmpty())
        }

    @Test
    fun `a transaction that does not repeat asks nothing, whatever its date`() =
        runTest {
            viewModel.onAmountChange("12,50")
            viewModel.onDateChange(LocalDate.of(2026, 1, 1))

            viewModel.save()
            viewModel.uiState.first { it.done }

            assertTrue(recurring.all().first().isEmpty())
        }

    @Test
    fun `an end before the transaction's own day cannot be saved`() {
        viewModel.onAmountChange("85,00")
        viewModel.repeat.onRhythmChange(Rhythm.MONTHLY)
        viewModel.repeat.onLastDateChange(LocalDate.of(2026, 9, 1))

        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test
    fun `a typed transaction made to repeat when it is edited becomes the first of its schedule`() =
        runTest {
            val typed = typedOn(LocalDate.of(2026, 9, 25))
            viewModel = open(editing = typed)
            viewModel.uiState.first { !it.loading }

            viewModel.repeat.onRhythmChange(Rhythm.MONTHLY)
            viewModel.save()
            viewModel.uiState.first { it.done }

            val schedule = recurring.all().first().single()
            assertEquals(schedule.id, transactions.byId(typed)?.recurringId)
            assertEquals(TransactionSource.RECURRING, transactions.byId(typed)?.source)
            assertEquals(LocalDate.of(2026, 10, 25), schedule.nextDue)
        }

    @Test
    fun `a row a schedule wrote, or one that started it, is not offered to repeat again`() =
        runTest {
            val typed = typedOn(LocalDate.of(2026, 8, 25))
            recurring.repeat(typed, Rhythm.MONTHLY, lastDate = null)
            val written = transactions.confirmed().first().single { it.id != typed }

            val first = open(editing = typed).uiState.first { !it.loading }
            val second = open(editing = written.id).uiState.first { !it.loading }

            assertFalse(first.canRepeat)
            assertFalse(second.canRepeat)
        }

    @Test
    fun `a recurring transaction opens on its next day, and a new amount reaches only what comes next`() =
        runTest {
            recurring.repeat(typedOn(LocalDate.of(2026, 8, 25)), Rhythm.MONTHLY, lastDate = null)
            val id =
                recurring
                    .all()
                    .first()
                    .single()
                    .id
            viewModel = open(schedule = id)
            val opened = viewModel.uiState.first { !it.loading }
            assertEquals(LocalDate.of(2026, 10, 25), opened.date)
            assertEquals(Rhythm.MONTHLY, opened.rhythm)
            assertTrue(opened.isSchedule)

            viewModel.onAmountChange("90,00")
            viewModel.save()
            viewModel.uiState.first { it.done }

            assertEquals(9_000L, recurring.byId(id)?.amount?.minor)
            assertEquals(listOf(8_500L, 8_500L), transactions.confirmed().first().map { it.amount.minor })
        }

    @Test
    fun `moving a recurring transaction's next day into the past asks, counting that day`() =
        runTest {
            recurring.repeat(typedOn(LocalDate.of(2026, 9, 25)), Rhythm.WEEKLY, lastDate = null)
            viewModel =
                open(
                    schedule =
                        recurring
                            .all()
                            .first()
                            .single()
                            .id,
                )
            viewModel.uiState.first { !it.loading }

            viewModel.onDateChange(LocalDate.of(2026, 9, 20))
            viewModel.save()
            advanceUntilIdle()

            assertEquals(
                Backfill(2, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 27)),
                viewModel.uiState.value.backfill,
            )
        }

    @Test
    fun `stopping a recurring transaction from the form keeps what it wrote`() =
        runTest {
            recurring.repeat(typedOn(LocalDate.of(2026, 8, 25)), Rhythm.MONTHLY, lastDate = null)
            viewModel =
                open(
                    schedule =
                        recurring
                            .all()
                            .first()
                            .single()
                            .id,
                )
            viewModel.uiState.first { !it.loading }

            viewModel.delete()
            viewModel.uiState.first { it.done }

            assertTrue(recurring.all().first().isEmpty())
            assertEquals(2, transactions.confirmed().first().size)
        }

    @Test
    fun `a transaction said to be recurring is saved with its day and no hour, and repeats nothing`() =
        runTest {
            viewModel.onAmountChange("101,32")
            viewModel.onMerchantChange("Hydro-Québec")
            viewModel.repeat.onRecurringChange(true)

            viewModel.save()
            viewModel.uiState.first { it.done }

            val saved = transactions.confirmed().first().single()
            assertEquals(TransactionSource.RECURRING, saved.source)
            assertEquals(LocalDate.of(2026, 9, 27), saved.localDate)
            assertEquals(LocalTime.NOON, saved.occurredAt.atZone(ZONE).toLocalTime())
            assertTrue(recurring.all().first().isEmpty())
        }

    @Test
    fun `picking an hour takes recurring back`() =
        runTest {
            viewModel.onAmountChange("101,32")
            viewModel.repeat.onRecurringChange(true)
            viewModel.onTimeChange(LocalTime.of(14, 12))

            viewModel.save()
            viewModel.uiState.first { it.done }

            val saved = transactions.confirmed().first().single()
            assertEquals(TransactionSource.MANUAL, saved.source)
            assertEquals(LocalTime.of(14, 12), saved.occurredAt.atZone(ZONE).toLocalTime())
        }

    @Test
    fun `a row a schedule wrote opens as recurring, and keeps no hour when its amount is corrected`() =
        runTest {
            val typed = typedOn(LocalDate.of(2026, 8, 25))
            recurring.repeat(typed, Rhythm.MONTHLY, lastDate = null)
            val written = transactions.confirmed().first().single { it.id != typed }
            viewModel = open(editing = written.id)

            assertTrue(viewModel.uiState.first { !it.loading }.recurring)
            viewModel.onAmountChange("91,20")
            viewModel.save()
            viewModel.uiState.first { it.done }

            val corrected = transactions.byId(written.id)
            assertEquals(TransactionSource.RECURRING, corrected?.source)
            assertEquals(written.occurredAt, corrected?.occurredAt)
            assertEquals(9_120L, corrected?.amount?.minor)
        }

    @Test
    fun `a transaction said to be recurring and made to repeat starts its schedule`() =
        runTest {
            viewModel.onAmountChange("85,00")
            viewModel.repeat.onRecurringChange(true)
            viewModel.repeat.onRhythmChange(Rhythm.MONTHLY)

            viewModel.save()
            viewModel.uiState.first { it.done }

            val schedule = recurring.all().first().single()
            assertEquals(
                schedule.id,
                transactions
                    .confirmed()
                    .first()
                    .single()
                    .recurringId,
            )
        }

    private suspend fun typedOn(day: LocalDate): Long =
        transactions.addManual(
            amount = Money(8_500, Money.CAD),
            merchantName = "Vidéotron",
            occurredAt = day.atTime(LocalTime.of(9, 2)).atZone(ZONE).toInstant(),
            note = null,
            kind = TransactionKind.EXPENSE,
            accountId = null,
            toAccountId = null,
            categoryId = null,
            reach = null,
        )

    private fun open(
        editing: Long? = null,
        schedule: Long? = null,
    ): ExpenseFormViewModel {
        val merchants = MerchantRepository(database.merchantDao())
        val categories = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
        return ExpenseFormViewModel(
            savedStateHandle =
                SavedStateHandle(
                    listOfNotNull(
                        editing?.let { EXPENSE_ID_ARG to it },
                        schedule?.let { RECURRING_ID_ARG to it },
                    ).toMap(),
                ),
            transactions = transactions,
            editor = TransactionEditor(database, database.transactionDao(), merchants, categories, CLOCK),
            merchants = merchants,
            accounts = AccountRepository(database.accountDao()),
            categories = categories,
            categoryGroups = database.categoryGroupRepository(),
            recurring = recurring,
            templates = database.templateRepository(CLOCK),
            clock = CLOCK,
            zoneId = Provider { ZONE },
        )
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        val CLOCK: Clock =
            Clock.fixed(
                LocalDate
                    .of(2026, 9, 27)
                    .atTime(9, 0)
                    .atZone(ZONE)
                    .toInstant(),
                ZONE,
            )
    }
}
