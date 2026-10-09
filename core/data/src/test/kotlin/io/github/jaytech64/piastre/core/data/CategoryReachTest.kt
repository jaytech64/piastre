// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.DepensesDatabase
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Transaction
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionStatus
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import io.github.jaytech64.piastre.parsing.GoogleWalletParser
import io.github.jaytech64.piastre.parsing.ParserRegistry
import io.github.jaytech64.piastre.parsing.RawNotification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Provider

/**
 * A category given to one transaction reaching the rest of its merchant's, and
 * the ones still to come — through the form, through capture, and through the
 * review inbox, against a real database fed by real Wallet notifications.
 */
@RunWith(RobolectricTestRunner::class)
class CategoryReachTest {
    private lateinit var database: DepensesDatabase
    private lateinit var capture: NotificationCapture
    private lateinit var transactions: TransactionRepository
    private lateinit var editor: TransactionEditor
    private lateinit var review: ReviewRepository
    private lateinit var categories: CategoryRepository
    private lateinit var accounts: AccountRepository

    @Before
    fun open() {
        database = inMemoryDatabase()
        val merchants = MerchantRepository(database.merchantDao())
        categories = database.categoryRepository()
        accounts = AccountRepository(database.accountDao())
        capture =
            NotificationCapture(
                RawEventRepository(database.rawNotificationEventDao()),
                ParsePipeline(
                    events = database.rawNotificationEventDao(),
                    transactions = database.transactionDao(),
                    registry = ParserRegistry(listOf(GoogleWalletParser())),
                    merchants = merchants,
                    accounts = accounts,
                    zoneId = Provider { ZONE },
                ),
            )
        transactions =
            TransactionRepository(database, database.transactionDao(), merchants, categories, Provider { ZONE })
        val clock = Clock.fixed(Instant.ofEpochMilli(EDITED_AT), ZONE)
        editor = TransactionEditor(database, database.transactionDao(), merchants, categories, clock)
        review =
            ReviewRepository(database.transactionDao(), merchants, ParserRegistry(listOf(GoogleWalletParser())), clock)
    }

    @After
    fun close() = database.close()

    @Test
    fun `a category given to one purchase reaches the merchant's others with none, and no further`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            val home = categories.named("Maison")!!
            captureAt("METRO PLUS", "tag-1")
            captureAt("METRO PLUS", "tag-2")
            captureAt("METRO PLUS", "tag-3")
            captureAt("ZOO CAFE", "tag-4")
            val (chosen, blank, filed) = confirmedAt("METRO PLUS")
            categorise(filed, home, reach = null)

            categorise(chosen, groceries, CategoryReach(others = true, fromNowOn = false))

            assertEquals(groceries, categoryOf(chosen))
            assertEquals(groceries, categoryOf(blank))
            assertEquals(home, categoryOf(filed))
            assertNull(confirmedAt("ZOO CAFE").single().categoryId)
        }

    @Test
    fun `without the offer ticked a category stays on the one purchase`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            captureAt("METRO PLUS", "tag-1")
            captureAt("METRO PLUS", "tag-2")
            val (chosen, other) = confirmedAt("METRO PLUS")

            categorise(chosen, groceries, CategoryReach(others = false, fromNowOn = false))

            assertNull(categoryOf(other))
            captureAt("METRO PLUS", "tag-3")
            assertNull(confirmedAt("METRO PLUS").last().categoryId)
        }

    @Test
    fun `from now on, the merchant's next Wallet purchase arrives categorised`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            captureAt("METRO PLUS", "tag-1")

            categorise(confirmedAt("METRO PLUS").single(), groceries, CategoryReach(others = false, fromNowOn = true))
            captureAt("METRO PLUS  0042", "tag-2")

            assertEquals(listOf(groceries, groceries), confirmedAt("METRO PLUS").map { it.categoryId })
        }

    @Test
    fun `a purchase that waits for review arrives with its merchant's category`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            captureAt("METRO PLUS", "tag-1")
            categorise(confirmedAt("METRO PLUS").single(), groceries, CategoryReach(others = false, fromNowOn = true))

            captureAt("METRO PLUS", "tag-2", text = UNREADABLE_CARD)

            assertEquals(
                groceries,
                review
                    .queue()
                    .first()
                    .single()
                    .transaction.categoryId,
            )
        }

    @Test
    fun `correcting the merchant in the review inbox brings the corrected merchant's category`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            val coffee = categories.named("Café")!!
            captureAt("METRO PLUS", "tag-1")
            captureAt("ZOO CAFE", "tag-2")
            categorise(confirmedAt("METRO PLUS").single(), groceries, CategoryReach(others = false, fromNowOn = true))
            categorise(confirmedAt("ZOO CAFE").single(), coffee, CategoryReach(others = false, fromNowOn = true))
            captureAt("METRO PLUS", "tag-3", text = UNREADABLE_CARD)
            val queued =
                review
                    .queue()
                    .first()
                    .single()
                    .transaction

            review.correct(queued.id, queued.amount, "Zoo Cafe")

            assertEquals(coffee, transactions.byId(queued.id)?.categoryId)
        }

    @Test
    fun `a purchase Wallet posts again keeps the category you gave it`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            val home = categories.named("Maison")!!
            captureAt("METRO PLUS", "tag-1")
            val purchase = confirmedAt("METRO PLUS").single()
            categorise(purchase, home, reach = null)
            categorise(purchase, groceries, CategoryReach(others = false, fromNowOn = true))
            categorise(purchase, home, reach = null)

            captureAt("METRO PLUS", "tag-1", postedAt = POSTED_AT + RE_POST_DELAY_MILLIS)

            assertEquals(home, categoryOf(purchase))
        }

    @Test
    fun `unticking from now on stops the merchant giving its category`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            captureAt("METRO PLUS", "tag-1")
            val purchase = confirmedAt("METRO PLUS").single()
            categorise(purchase, groceries, CategoryReach(others = false, fromNowOn = true))

            categorise(purchase, groceries, CategoryReach(others = false, fromNowOn = false))
            captureAt("METRO PLUS", "tag-2")

            assertNull(confirmedAt("METRO PLUS").last().categoryId)
        }

    @Test
    fun `leaving from now on unticked for another category keeps the merchant's own`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            val home = categories.named("Maison")!!
            captureAt("METRO PLUS", "tag-1")
            val purchase = confirmedAt("METRO PLUS").single()
            categorise(purchase, groceries, CategoryReach(others = false, fromNowOn = true))

            categorise(purchase, home, CategoryReach(others = false, fromNowOn = false))
            captureAt("METRO PLUS", "tag-2")

            assertEquals(groceries, confirmedAt("METRO PLUS").last().categoryId)
        }

    @Test
    fun `a new place typed by hand can give its category from its very first entry`() =
        runTest {
            val groceries = categories.named("Épicerie")!!

            addExpense("Fruiterie du marché", groceries, CategoryReach(others = false, fromNowOn = true))
            val merchant = database.merchantDao().allOnce().single()

            assertEquals(groceries, merchant.categoryId)
        }

    @Test
    fun `categorising a purchase leaves every total where it was`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            captureAt("METRO PLUS", "tag-1")
            captureAt("METRO PLUS", "tag-2")
            val before = confirmedAt("METRO PLUS").map { it.signedAmount }

            categorise(confirmedAt("METRO PLUS").first(), groceries, CategoryReach(others = true, fromNowOn = true))

            assertEquals(before, confirmedAt("METRO PLUS").map { it.signedAmount })
        }

    @Test
    fun `a mortgage principal reaches the other transfers into the loan, and the next one`() =
        runTest {
            val mortgage = categories.named("Hypothèque")!!
            val debit = accounts.add("Débit", AccountType.BANK, last4 = "")
            val loan = accounts.add("Prêt hypothécaire", AccountType.BANK, last4 = "")
            val visa = accounts.add("Visa", AccountType.CARD, last4 = "7373")
            val march = addTransfer(debit, loan, categoryId = null, reach = null)
            val cardPayment = addTransfer(debit, visa, categoryId = null, reach = null)

            addTransfer(debit, loan, mortgage, CategoryReach(others = true, fromNowOn = true))
            val may =
                addTransfer(
                    debit,
                    loan,
                    categoryId =
                        accounts
                            .all(LocalDate.of(2026, 12, 31))
                            .first()
                            .single { it.id == loan }
                            .categoryId,
                    reach = null,
                )

            assertEquals(mortgage, transactions.byId(march)?.categoryId)
            assertEquals(mortgage, transactions.byId(may)?.categoryId)
            assertNull(transactions.byId(cardPayment)?.categoryId)
            assertEquals(
                3,
                categories
                    .all()
                    .first()
                    .single()
                    .transactionCount,
            )
        }

    @Test
    fun `the count the form shows is what ticking the offer would change`() =
        runTest {
            val groceries = categories.named("Épicerie")!!
            captureAt("METRO PLUS", "tag-1")
            captureAt("METRO PLUS", "tag-2")
            captureAt("METRO PLUS", "tag-3")
            val (chosen, _, filed) = confirmedAt("METRO PLUS")
            categorise(filed, categories.named("Maison")!!, reach = null)
            val anchor = CategoryAnchor.AtMerchant(chosen.merchantId!!)

            assertEquals(1, categories.uncategorisedAt(anchor, TransactionKind.EXPENSE, chosen.id).first())
            categorise(chosen, groceries, CategoryReach(others = true, fromNowOn = false))
            assertEquals(0, categories.uncategorisedAt(anchor, TransactionKind.EXPENSE, chosen.id).first())
        }

    @Test
    fun `a category given to chosen transactions replaces theirs and reaches no other`() =
        runTest {
            val internet = categories.named("Internet")!!
            val phone = categories.named("Cellulaire")!!
            captureAt("VIDEOTRON", "tag-1")
            captureAt("VIDEOTRON", "tag-2")
            captureAt("VIDEOTRON", "tag-3")
            val (bill, other, blank) = confirmedAt("VIDEOTRON")
            categorise(bill, phone, CategoryReach(others = false, fromNowOn = true))
            categorise(other, phone, reach = null)

            editor.categoriseAll(setOf(bill.id, blank.id), internet)

            assertEquals(listOf(internet, phone, internet), confirmedAt("VIDEOTRON").map { it.categoryId })
            captureAt("VIDEOTRON", "tag-4")
            assertEquals(phone, confirmedAt("VIDEOTRON").last().categoryId)
        }

    @Test
    fun `chosen transactions can be left with no category`() =
        runTest {
            val phone = categories.named("Cellulaire")!!
            captureAt("VIDEOTRON", "tag-1")
            val bill = confirmedAt("VIDEOTRON").single()
            categorise(bill, phone, reach = null)

            editor.categoriseAll(setOf(bill.id), categoryId = null)

            assertNull(categoryOf(bill))
        }

    @Test
    fun `categorising chosen transactions leaves a parser fix free to repair them`() =
        runTest {
            val internet = categories.named("Internet")!!
            captureAt("VIDEOTRON", "tag-1")
            val bill = confirmedAt("VIDEOTRON").single()

            editor.categoriseAll(setOf(bill.id), internet)
            captureAt(
                "VIDEOTRON",
                "tag-1",
                text = "85,00 $ avec Carte d'accès Desrives ••0000",
                postedAt = POSTED_AT + RE_POST_DELAY_MILLIS,
            )

            assertNull(database.transactionDao().byId(bill.id)?.reviewedAtUtcMillis)
            val reread = transactions.byId(bill.id)!!
            assertEquals(Money(8_500, Money.CAD), reread.amount)
            assertEquals(internet, reread.categoryId)
        }

    @Test
    fun `a deleted purchase among the chosen ones is not categorised`() =
        runTest {
            val internet = categories.named("Internet")!!
            captureAt("VIDEOTRON", "tag-1")
            val bill = confirmedAt("VIDEOTRON").single()
            editor.remove(bill.id)

            editor.categoriseAll(setOf(bill.id), internet)

            assertNull(database.transactionDao().byId(bill.id)?.categoryId)
        }

    private suspend fun categorise(
        transaction: Transaction,
        categoryId: Long,
        reach: CategoryReach?,
    ) = editor.edit(
        id = transaction.id,
        amount = transaction.amount,
        kind = transaction.kind,
        merchantName = transaction.displayMerchant,
        occurredAt = transaction.occurredAt,
        note = transaction.note,
        accountId = transaction.accountId,
        toAccountId = transaction.toAccountId,
        categoryId = categoryId,
        reach = reach,
    )

    private suspend fun addExpense(
        merchant: String,
        categoryId: Long,
        reach: CategoryReach,
    ) = transactions.addManual(
        amount = Money(1_250, Money.CAD),
        merchantName = merchant,
        occurredAt = Instant.ofEpochMilli(POSTED_AT),
        note = null,
        kind = TransactionKind.EXPENSE,
        accountId = null,
        toAccountId = null,
        categoryId = categoryId,
        reach = reach,
    )

    private suspend fun addTransfer(
        from: Long,
        to: Long,
        categoryId: Long?,
        reach: CategoryReach?,
    ) = transactions.addManual(
        amount = Money(110_437, Money.CAD),
        merchantName = null,
        occurredAt = Instant.ofEpochMilli(POSTED_AT),
        note = null,
        kind = TransactionKind.TRANSFER,
        accountId = from,
        toAccountId = to,
        categoryId = categoryId,
        reach = reach,
    )

    private suspend fun categoryOf(transaction: Transaction): Long? = transactions.byId(transaction.id)?.categoryId

    /** Oldest first, so destructuring reads in the order the purchases were captured. */
    private suspend fun confirmedAt(merchant: String): List<Transaction> =
        transactions
            .confirmed()
            .first()
            .filter { it.displayMerchant == merchant }
            .sortedBy { it.id }

    private suspend fun captureAt(
        merchant: String,
        tag: String,
        text: String = "6,25 $ avec Carte d'accès Desrives ••0000",
        postedAt: Long = POSTED_AT,
    ) = capture.capture(
        RawNotification(
            sourcePackage = WALLET,
            notificationKey = "0|$WALLET|1001|$tag|10348",
            postedAtUtcMillis = postedAt,
            deviceLocale = "fr-CA",
            title = merchant,
            text = text,
        ),
        extrasJson = "{}",
    )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        const val WALLET = "com.google.android.apps.walletnfcrel"
        const val POSTED_AT = 1_788_740_166_564L
        const val RE_POST_DELAY_MILLIS = 1_800_000L
        const val EDITED_AT = 1_788_750_000_000L

        /** No card digits: the parser reads the amount and declines to confirm the rest. */
        const val UNREADABLE_CARD = "6,25 $ avec Carte"
    }
}
