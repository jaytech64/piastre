// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.settings

import io.github.jaytech64.depenses.core.data.CardRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.model.CardOrigin
import io.github.jaytech64.depenses.core.model.PaymentMethod
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Cards section's rules, against a real database. */
@RunWith(RobolectricTestRunner::class)
class CardsViewModelTest {
    private lateinit var database: DepensesDatabase
    private lateinit var repository: CardRepository
    private lateinit var viewModel: CardsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        database = inMemoryDatabase()
        repository = CardRepository(database.cardDao())
        viewModel = CardsViewModel(repository)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a card added here is listed with its name and digits`() =
        runTest {
            viewModel.save(card = null, name = "Amex papier", last4 = "8484")

            val card = viewModel.cards.first { it.isNotEmpty() }.single()

            assertEquals("Amex papier", card.name)
            assertEquals("8484", card.last4)
            assertEquals(CardOrigin.USER, card.origin)
        }

    /** The digits of a Wallet card are the bank's; only the name is yours. */
    @Test
    fun `a Wallet card takes a name but keeps the bank's digits`() =
        runTest {
            repository.resolve("Carte d'accès Desrives", "0000")
            val wallet = viewModel.cards.first { it.isNotEmpty() }.single()

            viewModel.save(wallet, name = "Débit Desrives", last4 = "9999")

            val saved = viewModel.cards.first { it.single().name != null }.single()
            assertEquals("Débit Desrives", saved.name)
            assertEquals("0000", saved.last4)
        }

    @Test
    fun `a card you added can have its digits corrected`() =
        runTest {
            viewModel.save(card = null, name = "Amex papier", last4 = "1090")
            val added = viewModel.cards.first { it.isNotEmpty() }.single()

            viewModel.save(added, name = "Amex papier", last4 = "8484")

            assertEquals(
                "8484",
                viewModel.cards
                    .first { it.single().last4 == "8484" }
                    .single()
                    .last4,
            )
        }

    @Test
    fun `a card still in use stays, and the screen is told why`() =
        runTest {
            val id = repository.resolve("Carte d'accès Desrives", "0000")!!
            payWith(id)
            val card = viewModel.cards.first { it.isNotEmpty() }.single()

            viewModel.remove(card)

            assertTrue(viewModel.removalRefused.first { it })
            assertEquals(1, viewModel.cards.value.size)
        }

    @Test
    fun `an unused card goes`() =
        runTest {
            viewModel.save(card = null, name = "Amex papier", last4 = "8484")
            val card = viewModel.cards.first { it.isNotEmpty() }.single()

            viewModel.remove(card)

            assertTrue(viewModel.cards.first { it.isEmpty() }.isEmpty())
        }

    private suspend fun payWith(cardId: Long) =
        database.transactionDao().insert(
            ExpenseTransactionEntity(
                rawEventId = null,
                amountMinor = 625,
                currency = "CAD",
                occurredAtUtcMillis = 1_788_740_166_564,
                zoneId = "America/Toronto",
                localDateEpochDay = 20_705,
                merchantId = null,
                merchantRaw = "SQ *CAFE OLIMPICO 12",
                categoryId = null,
                source = TransactionSource.NOTIFICATION,
                status = TransactionStatus.CONFIRMED,
                reviewReason = null,
                reviewedAtUtcMillis = null,
                confidence = 1f,
                parserId = "google-wallet",
                parserVersion = 1,
                sourcePackage = "com.google.android.apps.walletnfcrel",
                sourceKey = "tag-1",
                cardLabel = "Carte d'accès Desrives",
                cardLast4 = "0000",
                note = null,
                paymentMethod = PaymentMethod.CARD,
                cardId = cardId,
            ),
        )
}
