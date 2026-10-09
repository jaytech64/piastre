// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.CategoryEntity
import io.github.jaytech64.piastre.core.database.PiastreDatabase
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.AliasOrigin
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionPart
import io.github.jaytech64.piastre.core.model.TransactionSource
import io.github.jaytech64.piastre.core.model.TransactionStatus
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
import io.github.jaytech64.piastre.parsing.GoogleWalletParser
import io.github.jaytech64.piastre.parsing.ParserRegistry
import io.github.jaytech64.piastre.parsing.RawNotification
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Provider

/**
 * Editing and removing purchases already in the ledger, against a real database
 * fed by real Wallet notifications.
 *
 * The captured case is the one worth the setup: an edit only matters if the
 * notification that produced the row, posted again, cannot undo it.
 */
@RunWith(RobolectricTestRunner::class)
class TransactionEditorTest {
    private lateinit var database: PiastreDatabase
    private lateinit var capture: NotificationCapture
    private lateinit var repository: TransactionRepository
    private lateinit var editor: TransactionEditor

    @Before
    fun open() {
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
                    accounts = AccountRepository(database.accountDao()),
                    zoneId = Provider { ZONE },
                ),
            )
        repository =
            TransactionRepository(
                database = database,
                transactions = database.transactionDao(),
                merchants = merchants,
                categories =
                    CategoryRepository(
                        database.categoryDao(),
                        database.merchantDao(),
                        database.accountDao(),
                    ),
                zoneId = Provider { ZONE },
            )
        editor =
            TransactionEditor(
                database,
                database.transactionDao(),
                merchants,
                CategoryRepository(
                    database.categoryDao(),
                    database.merchantDao(),
                    database.accountDao(),
                ),
                Clock.fixed(Instant.ofEpochMilli(EDITED_AT), ZONE),
            )
    }

    @After
    fun close() = database.close()

    @Test
    fun `an edit to a captured purchase survives Wallet posting it again`() =
        runTest {
            captureCoffee()
            val id = capturedId()

            editor.edit(
                id,
                Money(700, Money.CAD),
                TransactionKind.EXPENSE,
                MERCHANT_RAW,
                occurredAt(),
                note = "Avec un croissant",
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )
            captureCoffee(postedAt = POSTED_AT + RE_POST_DELAY_MILLIS)

            val stored = repository.byId(id)
            assertEquals(700L, stored?.amount?.minor)
            assertEquals("Avec un croissant", stored?.note)
            assertEquals(EDITED_AT, database.transactionDao().byId(id)?.reviewedAtUtcMillis)
            assertEquals(1, repository.confirmed().first().size)
        }

    @Test
    fun `renaming a captured merchant keeps the bank's string and teaches the new name`() =
        runTest {
            captureCoffee()
            val id = capturedId()

            editor.edit(
                id,
                Money(625, Money.CAD),
                TransactionKind.EXPENSE,
                "Café Olimpico",
                occurredAt(),
                note = null,
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )

            val stored = repository.byId(id)
            assertEquals("Café Olimpico", stored?.displayMerchant)
            assertEquals(MERCHANT_RAW, stored?.merchantRaw)
            assertEquals("Café Olimpico", database.merchantDao().byExactAlias(MERCHANT_RAW)?.displayName)
        }

    /**
     * Matching folds case, so the new name finds the merchant it is renaming. The
     * point of typing it is the capitalisation, and it has to survive.
     */
    @Test
    fun `a merchant can be renamed to the same name, capitalised properly`() =
        runTest {
            captureShouting(merchant = "MCDONALD'S")
            val id = capturedId()

            editor.edit(
                id,
                Money(625, Money.CAD),
                TransactionKind.EXPENSE,
                "McDonald's",
                occurredAt(),
                note = null,
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )

            assertEquals("McDonald's", repository.byId(id)?.displayMerchant)
            assertEquals(1, database.merchantDao().allOnce().size)
        }

    /**
     * Saving an edit to the note must not quietly promote the normaliser's guess
     * to a mapping you made, which would then outrank a later normaliser fix.
     */
    @Test
    fun `an edit that leaves the merchant alone leaves its alias alone`() =
        runTest {
            captureCoffee()
            val id = capturedId()
            val shownAs = repository.byId(id)?.displayMerchant

            editor.edit(
                id,
                Money(625, Money.CAD),
                TransactionKind.EXPENSE,
                shownAs,
                occurredAt(),
                note = "Réunion",
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )

            val alias = database.merchantDao().allAliases().single { it.pattern == MERCHANT_RAW }
            assertEquals(AliasOrigin.AUTO, alias.createdBy)
        }

    @Test
    fun `clearing a captured merchant's name keeps the one the bank gave`() =
        runTest {
            captureCoffee()
            val id = capturedId()
            val before = repository.byId(id)?.merchantId

            editor.edit(
                id,
                Money(625, Money.CAD),
                TransactionKind.EXPENSE,
                "  ",
                occurredAt(),
                note = null,
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )

            assertEquals(before, repository.byId(id)?.merchantId)
        }

    @Test
    fun `clearing a manual purchase's merchant leaves it unnamed`() =
        runTest {
            val id = addManual(merchant = "Dépanneur du coin")

            editor.edit(
                id,
                Money(1_250, Money.CAD),
                TransactionKind.EXPENSE,
                "",
                occurredAt(),
                note = null,
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )

            assertNull(repository.byId(id)?.displayMerchant)
        }

    /**
     * The day is recomputed in the zone the purchase was made in. 22:30 in
     * Montréal is already the next day in UTC, so a day derived from the instant
     * alone would file it a day late.
     */
    @Test
    fun `moving a purchase to another day files it under that day where it was made`() =
        runTest {
            captureCoffee()
            val id = capturedId()
            val lateEvening =
                LocalDate
                    .of(2026, 9, 5)
                    .atTime(22, 30)
                    .atZone(ZONE)
                    .toInstant()

            editor.edit(
                id,
                Money(625, Money.CAD),
                TransactionKind.EXPENSE,
                MERCHANT_RAW,
                lateEvening,
                note = null,
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )

            val stored = repository.byId(id)
            assertEquals(LocalDate.of(2026, 9, 5), stored?.localDate)
            assertEquals(lateEvening, stored?.occurredAt)
            assertEquals(ZONE, stored?.zoneId)
        }

    @Test
    fun `removing a manual purchase deletes it`() =
        runTest {
            val id = addManual(merchant = "Dépanneur du coin")

            editor.remove(id)

            assertNull(database.transactionDao().byId(id))
        }

    /**
     * Deleting a captured row outright would not stick: the next re-post would
     * find nothing under its source key and insert the purchase afresh.
     */
    @Test
    fun `a removed captured purchase is dismissed and stays out after a re-post`() =
        runTest {
            captureCoffee()
            val id = capturedId()

            editor.remove(id)
            captureCoffee(postedAt = POSTED_AT + RE_POST_DELAY_MILLIS)

            assertEquals(emptyList<Any>(), repository.confirmed().first())
            assertEquals(TransactionStatus.DISMISSED, database.transactionDao().byId(id)?.status)
            assertEquals(2, database.rawNotificationEventDao().count().first())
        }

    @Test
    fun `removing several purchases deletes the manual ones and dismisses the captured one`() =
        runTest {
            captureCoffee()
            val captured = capturedId()
            val first = addManual(merchant = "Dépanneur du coin")
            val second = addManual(merchant = "Fruiterie du marché")
            val kept = addManual(merchant = "Boulangerie")

            editor.removeAll(setOf(captured, first, second))

            assertEquals(listOf(kept), repository.confirmed().first().map { it.id })
            assertNull(database.transactionDao().byId(first))
            assertNull(database.transactionDao().byId(second))
            assertEquals(TransactionStatus.DISMISSED, database.transactionDao().byId(captured)?.status)
        }

    @Test
    fun `turning a captured purchase into income survives Wallet posting it again`() =
        runTest {
            captureCoffee()
            val id = capturedId()

            editor.edit(
                id,
                Money(625, Money.CAD),
                TransactionKind.INCOME,
                MERCHANT_RAW,
                occurredAt(),
                note = "Remboursé",
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )
            captureCoffee(postedAt = POSTED_AT + RE_POST_DELAY_MILLIS)

            assertEquals(TransactionKind.INCOME, repository.byId(id)?.kind)
        }

    /** Even a row nobody has ruled on keeps a kind you set, the way it keeps your note. */
    @Test
    fun `a re-parse keeps the kind of a row nobody has ruled on`() =
        runTest {
            captureCoffee()
            val id = capturedId()
            val dao = database.transactionDao()
            dao.update(dao.byId(id)!!.copy(kind = TransactionKind.INCOME))

            captureCoffee(postedAt = POSTED_AT + RE_POST_DELAY_MILLIS)

            assertEquals(TransactionKind.INCOME, dao.byId(id)?.kind)
        }

    @Test
    fun `a manual entry takes the account it came from, or none`() =
        runTest {
            val accountId =
                AccountRepository(
                    database.accountDao(),
                ).add(name = "Amex papier", type = AccountType.CARD, last4 = "8484")
            val id = addManual(merchant = "Dépanneur du coin")

            editor.edit(
                id,
                Money(1_250, Money.CAD),
                TransactionKind.EXPENSE,
                "Dépanneur du coin",
                occurredAt(),
                note = null,
                accountId = accountId,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )
            assertEquals(accountId, repository.byId(id)?.accountId)
            assertEquals("Amex papier", repository.byId(id)?.accountName)

            editor.edit(
                id,
                Money(1_250, Money.CAD),
                TransactionKind.EXPENSE,
                "Dépanneur du coin",
                occurredAt(),
                note = null,
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )
            assertNull(repository.byId(id)?.accountId)
        }

    /** The notification named the account; an edit cannot say otherwise. */
    @Test
    fun `a captured purchase keeps the account its notification named`() =
        runTest {
            captureCoffee()
            val id = capturedId()
            val named = repository.byId(id)?.accountId

            editor.edit(
                id,
                Money(625, Money.CAD),
                TransactionKind.EXPENSE,
                MERCHANT_RAW,
                occurredAt(),
                note = null,
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
            )

            assertNotNull(named)
            assertEquals(named, repository.byId(id)?.accountId)
        }

    /** Typed at 23:40, it is still that day's: noon is worked out from the day, not the other way round. */
    @Test
    fun `a typed transaction said to be recurring has its day and no hour`() =
        runTest {
            val lateEvening =
                LocalDate
                    .of(2026, 9, 5)
                    .atTime(23, 40)
                    .atZone(ZONE)
                    .toInstant()

            val id =
                repository.addManual(
                    Money(10_132, Money.CAD),
                    "Hydro-Québec",
                    lateEvening,
                    note = null,
                    kind = TransactionKind.EXPENSE,
                    accountId = null,
                    toAccountId = null,
                    categoryId = null,
                    reach = null,
                    recurring = true,
                )

            val stored = repository.byId(id)
            assertEquals(TransactionSource.RECURRING, stored?.source)
            assertEquals(LocalDate.of(2026, 9, 5), stored?.localDate)
            assertEquals(noonOn(LocalDate.of(2026, 9, 5)), stored?.occurredAt)
            assertNull(stored?.recurringId)
        }

    @Test
    fun `a typed transaction can be made recurring, and given its hour back`() =
        runTest {
            val id = addManual(merchant = "Hydro-Québec")
            val day = repository.byId(id)?.localDate

            editAt(id, occurredAt(), recurring = true)
            val recurring = repository.byId(id)
            editAt(id, occurredAt(), recurring = false)
            val timed = repository.byId(id)

            assertEquals(TransactionSource.RECURRING, recurring?.source)
            assertEquals(day?.let(::noonOn), recurring?.occurredAt)
            assertEquals(TransactionSource.MANUAL, timed?.source)
            assertEquals(occurredAt(), timed?.occurredAt)
        }

    /** Its hour is what Wallet said, and its source is what lets a parser fix reach it. */
    @Test
    fun `a captured purchase keeps its hour whatever is asked`() =
        runTest {
            captureCoffee()
            val id = capturedId()

            editAt(id, occurredAt(), recurring = true, merchant = MERCHANT_RAW)

            val stored = repository.byId(id)
            assertEquals(TransactionSource.NOTIFICATION, stored?.source)
            assertEquals(occurredAt(), stored?.occurredAt)
        }

    @Test
    fun `a split is saved with its parts and no category of its own`() =
        runTest {
            val groceries = category("Épicerie")
            val home = category("Maison")

            val id = addSplit(part(12_000, groceries), part(6_742, home, note = "Ampoules"))

            val stored = repository.confirmed().first().single()
            assertEquals(id, stored.id)
            assertEquals(18_742L, stored.amount.minor)
            assertNull(stored.categoryId)
            assertEquals(
                listOf("Épicerie" to 12_000L, "Maison" to 6_742L),
                stored.parts.map {
                    it.categoryName to
                        it.amount.minor
                },
            )
            assertEquals("Ampoules", stored.parts.last().note)
        }

    @Test
    fun `parts that do not add up are refused and nothing is written`() =
        runTest {
            val refused = runCatching { addSplit(part(12_000, null), part(6_000, null)) }

            assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, repository.confirmed().first().size)
            assertEquals(0, database.transactionPartDao().allOnce().size)
        }

    @Test
    fun `a single part is not a split, and gives the transaction its category`() =
        runTest {
            val groceries = category("Épicerie")

            val id = addSplit(part(18_742, groceries))

            val stored = repository.byId(id)
            assertEquals(groceries, stored?.categoryId)
            assertEquals(false, stored?.isSplit)
        }

    @Test
    fun `an edit with no parts leaves a split an ordinary transaction again`() =
        runTest {
            val groceries = category("Épicerie")
            val id = addSplit(part(12_000, groceries), part(6_742, null))

            editCostco(id, categoryId = groceries, parts = emptyList())

            val stored = repository.byId(id)
            assertEquals(groceries, stored?.categoryId)
            assertEquals(false, stored?.isSplit)
            assertEquals(0, database.transactionPartDao().allOnce().size)
        }

    @Test
    fun `splitting a captured purchase survives Wallet posting it again`() =
        runTest {
            val groceries = category("Épicerie")
            captureCoffee()
            val id = capturedId()
            val amount = checkNotNull(repository.byId(id)).amount

            editor.edit(
                id,
                amount,
                TransactionKind.EXPENSE,
                MERCHANT_RAW,
                occurredAt(),
                note = null,
                accountId = null,
                toAccountId = null,
                categoryId = null,
                reach = null,
                parts = listOf(part(amount.minor - 100, groceries), part(100, null)),
            )
            captureCoffee(postedAt = POSTED_AT + RE_POST_DELAY_MILLIS)

            val stored = repository.confirmed().first().single()
            assertEquals(listOf(amount.minor - 100, 100L), stored.parts.map { it.amount.minor })
            assertNull(stored.categoryId)
        }

    @Test
    fun `removing a typed split takes its parts with it`() =
        runTest {
            val id = addSplit(part(12_000, null), part(6_742, null))

            editor.remove(id)

            assertEquals(0, database.transactionPartDao().allOnce().size)
        }

    @Test
    fun `deleting a category leaves its part with its amount and no category`() =
        runTest {
            val groceries = category("Épicerie")
            val id = addSplit(part(12_000, groceries), part(6_742, null))

            database.categoryDao().delete(groceries)

            assertEquals(
                listOf(null to 12_000L, null to 6_742L),
                repository.byId(id)?.parts?.map {
                    it.categoryId to
                        it.amount.minor
                },
            )
        }

    private suspend fun category(name: String): Long =
        database.categoryDao().insert(
            CategoryEntity(name = name, normalisedName = name.uppercase()),
        )

    private fun part(
        minor: Long,
        categoryId: Long?,
        note: String? = null,
    ) = TransactionPart(Money(minor, Money.CAD), categoryId, note = note)

    private suspend fun addSplit(vararg parts: TransactionPart): Long =
        repository.addManual(
            Money(18_742, Money.CAD),
            "Costco",
            occurredAt(),
            note = null,
            kind = TransactionKind.EXPENSE,
            accountId = null,
            toAccountId = null,
            categoryId = null,
            reach = null,
            parts = parts.toList(),
        )

    private suspend fun editCostco(
        id: Long,
        categoryId: Long?,
        parts: List<TransactionPart>,
    ) = editor.edit(
        id,
        Money(18_742, Money.CAD),
        TransactionKind.EXPENSE,
        "Costco",
        occurredAt(),
        note = null,
        accountId = null,
        toAccountId = null,
        categoryId = categoryId,
        reach = null,
        parts = parts,
    )

    private suspend fun editAt(
        id: Long,
        occurredAt: Instant,
        recurring: Boolean,
        merchant: String = "Hydro-Québec",
    ) = editor.edit(
        id,
        Money(1_250, Money.CAD),
        TransactionKind.EXPENSE,
        merchant,
        occurredAt,
        note = null,
        accountId = null,
        toAccountId = null,
        categoryId = null,
        reach = null,
        recurring = recurring,
    )

    private fun noonOn(day: LocalDate): Instant = day.atTime(LocalTime.NOON).atZone(ZONE).toInstant()

    private suspend fun captureShouting(merchant: String) =
        capture.capture(
            RawNotification(
                sourcePackage = WALLET,
                notificationKey = KEY,
                postedAtUtcMillis = POSTED_AT,
                deviceLocale = "fr-CA",
                title = merchant,
                text = "6,25 $ avec Carte d'accès Desrives ••0000",
            ),
            extrasJson = "{}",
        )

    private suspend fun capturedId(): Long =
        repository
            .confirmed()
            .first()
            .single()
            .id

    private suspend fun addManual(merchant: String?): Long =
        repository.addManual(
            Money(1_250, Money.CAD),
            merchant,
            occurredAt(),
            note = null,
            kind = TransactionKind.EXPENSE,
            accountId = null,
            toAccountId = null,
            categoryId = null,
            reach = null,
        )

    private fun occurredAt(): Instant = Instant.ofEpochMilli(POSTED_AT)

    private suspend fun captureCoffee(postedAt: Long = POSTED_AT) =
        capture.capture(
            RawNotification(
                sourcePackage = WALLET,
                notificationKey = KEY,
                postedAtUtcMillis = postedAt,
                deviceLocale = "fr-CA",
                title = MERCHANT_RAW,
                text = "6,25 $ avec Carte d'accès Desrives ••0000",
            ),
            extrasJson = "{}",
        )

    private companion object {
        val ZONE: ZoneId = ZoneId.of("America/Toronto")
        const val WALLET = "com.google.android.apps.walletnfcrel"
        const val KEY = "0|com.google.android.apps.walletnfcrel|1001|tag-77|10348"
        const val MERCHANT_RAW = "SQ *CAFE OLIMPICO 12"
        const val POSTED_AT = 1_788_740_166_564L
        const val RE_POST_DELAY_MILLIS = 1_800_000L
        const val EDITED_AT = 1_788_750_000_000L
    }
}
