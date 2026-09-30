// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Rhythm
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Recurring transactions against a real database, on a clock that says it is
 * 27 September 2026.
 */
@RunWith(RobolectricTestRunner::class)
class RecurringRepositoryTest {
    private lateinit var database: DepensesDatabase
    private lateinit var transactions: TransactionRepository
    private lateinit var editor: TransactionEditor
    private lateinit var categories: CategoryRepository
    private lateinit var merchants: MerchantRepository
    private lateinit var posting: RecurringPosting
    private lateinit var recurring: RecurringRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        val clock = Clock.fixed(TODAY.atTime(LocalTime.of(9, 0)).atZone(ZONE).toInstant(), ZONE)
        merchants = MerchantRepository(database.merchantDao())
        categories = CategoryRepository(database.categoryDao(), database.merchantDao(), database.accountDao())
        transactions =
            TransactionRepository(database, database.transactionDao(), merchants, categories, Provider { ZONE })
        editor = TransactionEditor(database, database.transactionDao(), merchants, categories, clock)
        posting =
            RecurringPosting(
                database,
                database.recurringTransactionDao(),
                database.transactionDao(),
                LocalDateTicker(clock, Provider { ZONE }),
                clock,
                Provider { ZONE },
            )
        recurring =
            RecurringRepository(
                database,
                database.recurringTransactionDao(),
                database.transactionDao(),
                merchants,
                posting,
            )
    }

    @After
    fun close() = database.close()

    @Test
    fun `a bill made to repeat from June writes each month since, each on its own day`() =
        runTest {
            val first = typed(on = LocalDate.of(2026, 6, 25))

            recurring.repeat(first, Rhythm.MONTHLY, lastDate = null)

            assertEquals(
                listOf(
                    LocalDate.of(2026, 6, 25),
                    LocalDate.of(2026, 7, 25),
                    LocalDate.of(2026, 8, 25),
                    LocalDate.of(2026, 9, 25),
                ),
                ledger().map { it.localDate },
            )
            assertEquals(
                LocalDate.of(2026, 10, 25),
                recurring
                    .all()
                    .first()
                    .single()
                    .nextDue,
            )
        }

    @Test
    fun `a written row is the schedule's, dated on its due day`() =
        runTest {
            recurring.repeat(typed(on = LocalDate.of(2026, 8, 25)), Rhythm.MONTHLY, lastDate = null)

            val written = ledger().single { it.localDate == LocalDate.of(2026, 9, 25) }

            assertEquals(TransactionSource.RECURRING, written.source)
            assertEquals(8_500L, written.amount.minor)
            assertEquals("Vidéotron", written.displayMerchant)
            assertEquals(
                recurring
                    .all()
                    .first()
                    .single()
                    .id,
                written.recurringId,
            )
            assertEquals(
                LocalDate.of(2026, 9, 25),
                written.occurredAt.atZone(written.zoneId).toLocalDate(),
            )
        }

    @Test
    fun `passing over the same days again writes nothing twice`() =
        runTest {
            recurring.repeat(typed(on = LocalDate.of(2026, 7, 25)), Rhythm.MONTHLY, lastDate = null)
            val schedule = recurring.all().first().single()

            database.recurringTransactionDao().moveNextDue(schedule.id, LocalDate.of(2026, 8, 25).toEpochDay().toInt())
            val rewritten = posting.catchUp()

            assertEquals(0, rewritten)
            assertEquals(3, ledger().size)
        }

    @Test
    fun `a row you delete is not written again`() =
        runTest {
            recurring.repeat(typed(on = LocalDate.of(2026, 8, 25)), Rhythm.MONTHLY, lastDate = null)
            val september = ledger().single { it.localDate == LocalDate.of(2026, 9, 25) }

            editor.remove(september.id)
            posting.catchUp()

            assertEquals(listOf(LocalDate.of(2026, 8, 25)), ledger().map { it.localDate })
        }

    @Test
    fun `nothing is written for a day that has not come`() =
        runTest {
            recurring.repeat(typed(on = LocalDate.of(2026, 9, 28)), Rhythm.WEEKLY, lastDate = null)

            assertEquals(1, ledger().size)
        }

    @Test
    fun `nothing is written after the end date, and the schedule goes once it is over`() =
        runTest {
            recurring.repeat(
                typed(on = LocalDate.of(2026, 6, 15)),
                Rhythm.MONTHLY,
                lastDate = LocalDate.of(2026, 7, 15),
            )
            posting.catchUp(LocalDate.of(2027, 1, 1))

            assertEquals(listOf(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 7, 15)), ledger().map { it.localDate })
            assertTrue(ledger().all { it.source == TransactionSource.RECURRING && it.recurringId == null })
            assertTrue(recurring.all().first().isEmpty())
        }

    @Test
    fun `a schedule with a day still to come stays, even past its last written day`() =
        runTest {
            recurring.repeat(
                typed(on = LocalDate.of(2026, 8, 15)),
                Rhythm.MONTHLY,
                lastDate = LocalDate.of(2026, 10, 15),
            )

            assertEquals(
                LocalDate.of(2026, 10, 15),
                recurring
                    .all()
                    .first()
                    .single()
                    .nextDue,
            )
        }

    @Test
    fun `the transaction a schedule starts from reads as recurring, like the rows it writes`() =
        runTest {
            val first = typed(on = LocalDate.of(2026, 8, 1))

            recurring.repeat(first, Rhythm.MONTHLY, lastDate = null)

            val schedule = recurring.all().first().single()
            assertEquals(listOf(TransactionSource.RECURRING, TransactionSource.RECURRING), ledger().map { it.source })
            assertEquals(schedule.id, transactions.byId(first)?.recurringId)
        }

    @Test
    fun `the first day is never written a second time`() =
        runTest {
            val first = typed(on = LocalDate.of(2026, 8, 1))
            recurring.repeat(first, Rhythm.MONTHLY, lastDate = null)
            val schedule = recurring.all().first().single()

            database.recurringTransactionDao().moveNextDue(schedule.id, LocalDate.of(2026, 8, 1).toEpochDay().toInt())
            posting.catchUp()

            assertEquals(2, ledger().size)
        }

    @Test
    fun `the schedule's category is written even where the merchant gives another`() =
        runTest {
            val internet = categories.named("Internet")!!
            val cellphone = categories.named("Cellulaire")!!
            val first = typed(on = LocalDate.of(2026, 8, 25), categoryId = internet)
            database.merchantDao().setCategory(ledger().single().merchantId!!, cellphone)

            recurring.repeat(first, Rhythm.MONTHLY, lastDate = null)

            assertEquals(listOf(internet, internet), ledger().map { it.categoryId })
        }

    @Test
    fun `changing a schedule changes what comes next and not what was written`() =
        runTest {
            recurring.repeat(typed(on = LocalDate.of(2026, 8, 25)), Rhythm.MONTHLY, lastDate = null)
            val schedule = recurring.all().first().single()

            recurring.change(
                schedule.id,
                Money(9_000, Money.CAD),
                TransactionKind.EXPENSE,
                "Vidéotron",
                accountId = null,
                toAccountId = null,
                categoryId = null,
                note = null,
                rhythm = Rhythm.MONTHLY,
                nextDate = LocalDate.of(2026, 10, 25),
                lastDate = null,
            )
            posting.catchUp(LocalDate.of(2026, 10, 25))

            assertEquals(listOf(8_500L, 8_500L, 9_000L), ledger().map { it.amount.minor })
        }

    @Test
    fun `a bill on the 31st left where it was still comes back to the 31st`() =
        runTest {
            recurring.repeat(typed(on = LocalDate.of(2026, 8, 31)), Rhythm.MONTHLY, lastDate = null)
            val schedule = recurring.all().first().single()
            assertEquals(LocalDate.of(2026, 9, 30), schedule.nextDue)

            recurring.change(
                schedule.id,
                Money(9_000, Money.CAD),
                TransactionKind.EXPENSE,
                "Vidéotron",
                accountId = null,
                toAccountId = null,
                categoryId = null,
                note = null,
                rhythm = Rhythm.MONTHLY,
                nextDate = LocalDate.of(2026, 9, 30),
                lastDate = null,
            )
            posting.catchUp(LocalDate.of(2026, 9, 30))

            assertEquals(
                LocalDate.of(2026, 10, 31),
                recurring
                    .all()
                    .first()
                    .single()
                    .nextDue,
            )
        }

    @Test
    fun `stopping a schedule keeps what it wrote and writes nothing more`() =
        runTest {
            recurring.repeat(typed(on = LocalDate.of(2026, 8, 25)), Rhythm.MONTHLY, lastDate = null)
            val schedule = recurring.all().first().single()

            recurring.stop(schedule.id)
            posting.catchUp(LocalDate.of(2027, 1, 1))

            assertEquals(2, ledger().size)
            assertTrue(ledger().all { it.recurringId == null })
            assertTrue(recurring.all().first().isEmpty())
        }

    @Test
    fun `a transaction already on a schedule cannot start a second one`() =
        runTest {
            val first = typed(on = LocalDate.of(2026, 9, 25))
            recurring.repeat(first, Rhythm.MONTHLY, lastDate = null)

            assertNull(recurring.repeat(first, Rhythm.WEEKLY, lastDate = null))
            assertEquals(1, recurring.all().first().size)
        }

    private suspend fun typed(
        on: LocalDate,
        categoryId: Long? = null,
    ): Long =
        transactions.addManual(
            amount = Money(8_500, Money.CAD),
            merchantName = "Vidéotron",
            occurredAt = on.atTime(LocalTime.of(9, 2)).atZone(ZONE).toInstant(),
            note = null,
            kind = TransactionKind.EXPENSE,
            accountId = null,
            toAccountId = null,
            categoryId = categoryId,
            reach = null,
        )

    private suspend fun ledger(): List<Transaction> = transactions.confirmed().first().sortedBy { it.localDate }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        val TODAY: LocalDate = LocalDate.of(2026, 9, 27)
    }
}
