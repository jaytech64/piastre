// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.DepensesDatabase
import io.github.jaytech64.piastre.core.model.AccountType
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Recurrence
import io.github.jaytech64.piastre.core.model.Rhythm
import io.github.jaytech64.piastre.core.model.TemplateContent
import io.github.jaytech64.piastre.core.model.TransactionKind
import io.github.jaytech64.piastre.core.model.TransactionPart
import io.github.jaytech64.piastre.core.testing.inMemoryDatabase
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
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Provider

/** A split read back from a file: its parts, and the account one of them may go into. */
@RunWith(RobolectricTestRunner::class)
class LedgerImportSplitTest : LedgerImportFixture() {
    @Test
    fun `a split arrives split, each part under the category it had`() =
        runTest {
            val groceries = source.categoryRepository().named("Épicerie")!!
            source.enterSplit(
                TransactionPart(cad(120_00), groceries),
                TransactionPart(cad(67_42), null, note = "Ampoules"),
            )

            target.importing(source.export())

            val arrived =
                target
                    .transactions()
                    .confirmed()
                    .first()
                    .single()
            assertEquals(cad(187_42), arrived.amount)
            assertNull(arrived.categoryId)
            assertEquals(
                listOf(Triple("Épicerie", cad(120_00), null), Triple(null, cad(67_42), "Ampoules")),
                arrived.parts.map { Triple(it.categoryName, it.amount, it.note) },
            )
        }

    @Test
    fun `a part that goes into another account arrives going into it, and both accounts hold what they did`() =
        runTest {
            val chequing = source.accounts().add("Chèques", AccountType.CARD, last4 = "")
            val mortgage = source.accounts().add("Hypothèque", AccountType.BANK, last4 = "")
            source.enterSplit(
                TransactionPart(cad(120_00), null),
                TransactionPart(cad(67_42), null, toAccountId = mortgage),
                accountId = chequing,
            )

            target.importing(source.export())

            val arrived =
                target
                    .transactions()
                    .confirmed()
                    .first()
                    .single()
            assertEquals(listOf(null, "Hypothèque"), arrived.parts.map { it.toAccountName })
            assertEquals(source.balances(), target.balances())
        }

    @Test
    fun `a file written before a part could go into an account is still read`() =
        runTest {
            source.enterSplit(TransactionPart(cad(120_00), null), TransactionPart(cad(67_42), null))
            val older = source.export().replace("\"version\": 4", "\"version\": 3")

            target.importing(older)

            assertEquals(
                2,
                target
                    .transactions()
                    .confirmed()
                    .first()
                    .single()
                    .parts.size,
            )
        }

    @Test
    fun `a part into an account the file does not list refuses the file before anything is written`() =
        runTest {
            val chequing = source.accounts().add("Chèques", AccountType.CARD, last4 = "")
            val mortgage = source.accounts().add("Hypothèque", AccountType.BANK, last4 = "")
            source.enterSplit(
                TransactionPart(cad(120_00), null),
                TransactionPart(cad(67_42), null, toAccountId = mortgage),
                accountId = chequing,
            )
            val file = source.export()
            val tampered =
                file.replace(
                    Regex("\"toAccountId\": $mortgage,(\\s+)\"toAccountName\""),
                    "\"toAccountId\": 99,$1\"toAccountName\"",
                )

            val reading = target.reading(tampered)

            assertTrue(file != tampered)
            assertTrue(reading.toString(), (reading as ImportReading.Refused).problem is ImportProblem.Inconsistent)
            assertEquals(0, target.transactionDao().count().first())
        }

    @Test
    fun `a file whose parts do not add up to their transaction is refused before anything is written`() =
        runTest {
            source.enterSplit(TransactionPart(cad(120_00), null), TransactionPart(cad(67_42), null))
            val tampered = source.export().replace("12000", "11000")

            val reading = target.reading(tampered)

            assertTrue(reading.toString(), (reading as ImportReading.Refused).problem is ImportProblem.Inconsistent)
            assertEquals(0, target.transactionDao().count().first())
        }
}
