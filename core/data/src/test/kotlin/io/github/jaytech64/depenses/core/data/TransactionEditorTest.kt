// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.model.AliasOrigin
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Payment
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.testing.inMemoryDatabase
import io.github.jaytech64.depenses.parsing.GoogleWalletParser
import io.github.jaytech64.depenses.parsing.ParserRegistry
import io.github.jaytech64.depenses.parsing.RawNotification
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
import java.time.Instant
import java.time.LocalDate
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
    private lateinit var database: DepensesDatabase
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
                    cards = CardRepository(database.cardDao()),
                    zoneId = Provider { ZONE },
                ),
            )
        repository =
            TransactionRepository(
                transactions = database.transactionDao(),
                reports = database.reportDao(),
                merchants = merchants,
                zoneId = Provider { ZONE },
            )
        editor =
            TransactionEditor(database.transactionDao(), merchants, Clock.fixed(Instant.ofEpochMilli(EDITED_AT), ZONE))
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
                payment = null,
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
                payment = null,
            )

            val stored = repository.byId(id)
            assertEquals("Café Olimpico", stored?.displayMerchant)
            assertEquals(MERCHANT_RAW, stored?.merchantRaw)
            assertEquals("Café Olimpico", database.merchantDao().byExactAlias(MERCHANT_RAW)?.displayName)
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
                payment = null,
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
                payment = null,
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
                payment = null,
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
                payment = null,
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
                payment = null,
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
    fun `a manual entry takes the card it was paid with, or cash`() =
        runTest {
            val cardId = CardRepository(database.cardDao()).add(name = "Amex papier", last4 = "8484")
            val id = addManual(merchant = "Dépanneur du coin")

            editor.edit(
                id,
                Money(1_250, Money.CAD),
                TransactionKind.EXPENSE,
                "Dépanneur du coin",
                occurredAt(),
                note = null,
                payment = Payment.ByCard(cardId),
            )
            assertEquals(Payment.ByCard(cardId), repository.byId(id)?.payment)

            editor.edit(
                id,
                Money(1_250, Money.CAD),
                TransactionKind.EXPENSE,
                "Dépanneur du coin",
                occurredAt(),
                note = null,
                payment = Payment.Cash,
            )
            assertEquals(Payment.Cash, repository.byId(id)?.payment)
        }

    /** The notification named the card; an edit cannot say otherwise. */
    @Test
    fun `a captured purchase keeps the card its notification named`() =
        runTest {
            captureCoffee()
            val id = capturedId()
            val named = repository.byId(id)?.payment

            editor.edit(
                id,
                Money(625, Money.CAD),
                TransactionKind.EXPENSE,
                MERCHANT_RAW,
                occurredAt(),
                note = null,
                payment = Payment.Cash,
            )

            assertTrue(named is Payment.ByCard)
            assertEquals(named, repository.byId(id)?.payment)
        }

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
            payment = null,
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
