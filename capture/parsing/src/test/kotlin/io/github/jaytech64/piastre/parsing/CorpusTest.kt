// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.parsing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The regression suite that matters: the parser run over notifications Google
 * Wallet actually posted, rather than over examples written to match the parser.
 * Both corpora were captured on a real device and redacted; every future parser
 * change is measured against them.
 */
class CorpusTest {
    private val registry = ParserRegistry(listOf(GoogleWalletParser()))

    private fun draftsOf(fixture: String): List<TransactionDraft> =
        FixtureCorpus
            .load(fixture)
            .map { registry.parse(it) }
            .filterIsInstance<ParseResult.Parsed>()
            .map { it.draft }

    private fun ledgerOf(fixture: String): Map<String, TransactionDraft> {
        val ledger = LinkedHashMap<String, TransactionDraft>()
        // Upsert: a re-post of the same transaction must overwrite, never append.
        for (draft in draftsOf(fixture)) ledger[draft.sourceKey] = draft
        return ledger
    }

    @Test
    fun `week one - 21 notifications collapse to the 8 real transactions`() {
        assertEquals(21, FixtureCorpus.load(WEEK_1).size)

        val ledger = ledgerOf(WEEK_1)
        assertEquals(8, ledger.size)
        assertEquals(15_200, ledger.values.sumOf { it.amount.minor })
    }

    @Test
    fun `week two - 24 notifications collapse to the 10 real transactions`() {
        assertEquals(24, FixtureCorpus.load(WEEK_2).size)

        val ledger = ledgerOf(WEEK_2)
        assertEquals(10, ledger.size)
        assertEquals(30_342, ledger.values.sumOf { it.amount.minor })
    }

    @Test
    fun `every noise class is rejected for a stated reason`() {
        fun reasons(fixture: String) =
            FixtureCorpus
                .load(fixture)
                .map { registry.parse(it) }
                .filterIsInstance<ParseResult.Ignored>()
                .groupingBy { it.reason }
                .eachCount()

        val one = reasons(WEEK_1)
        assertEquals(2, one[IgnoreReason.GROUP_SUMMARY], "week one empty group summaries")
        assertEquals(2, one[IgnoreReason.NOT_A_PAYMENT], "week one transitional receipts")

        val two = reasons(WEEK_2)
        assertEquals(4, two[IgnoreReason.GROUP_SUMMARY], "week two empty group summaries")
        assertEquals(2, two[IgnoreReason.NOT_A_PAYMENT], "week two transitional receipts")
    }

    @Test
    fun `nothing in either corpus needs review`() {
        for (fixture in CORPORA) {
            val review =
                FixtureCorpus
                    .load(fixture)
                    .map { registry.parse(it) }
                    .filterIsInstance<ParseResult.NeedsReview>()
            assertTrue(review.isEmpty(), "$fixture: unexpected review items ${review.map { it.reason }}")
        }
    }

    /**
     * The card label arrives in the issuer's language whatever the phone is set to,
     * and week two contains a French notification captured while the device
     * reported en-CA. A parser keyed on device locale would mis-read exactly these.
     */
    @Test
    fun `both currency formats parse, and neither depends on the device locale`() {
        val drafts = draftsOf(WEEK_2)

        val french = drafts.filter { it.sourceKey == FRENCH_ONLY_KEY }
        assertTrue(french.isNotEmpty(), "expected the French-only transaction")
        assertEquals(12_019, french.first().amount.minor, "120,19 \$ with a comma decimal")

        val english = drafts.filter { it.sourceKey == ENGLISH_ONLY_KEY }
        assertTrue(english.isNotEmpty(), "expected the English-format transaction")
        assertEquals(1_999, english.first().amount.minor, "\$19.99 with a period decimal")

        // Same purchase, captured once as fr-CA and replayed 51 hours later as en-CA.
        val replayed = drafts.filter { it.sourceKey == REPLAYED_KEY }
        assertEquals(2, replayed.size, "expected the original and its replay")
        assertEquals(1, replayed.distinctBy { it.amount }.size, "replay must not change the amount")
    }

    @Test
    fun `identity survives re-posting - the most re-posted transaction appears once`() {
        val drafts = draftsOf(WEEK_1)

        // THE MATCHA was posted four times across 36 minutes.
        val busiest = drafts.groupingBy { it.sourceKey }.eachCount().maxBy { it.value }
        assertEquals(4, busiest.value, "expected the four-times-posted transaction")
        assertEquals(1, drafts.filter { it.sourceKey == busiest.key }.distinctBy { it.sourceKey }.size)
    }

    /**
     * Wallet rewrites a payment notification about three seconds after posting it.
     * Seven of week two's ten transactions arrived that way, and the tag is
     * identical across the pair — which is the whole reason identity comes from
     * the tag rather than from content or timing.
     */
    @Test
    fun `the deterministic re-post never becomes a second transaction`() {
        val drafts = draftsOf(WEEK_2)
        val repostedKeys = drafts.groupingBy { it.sourceKey }.eachCount().filterValues { it > 1 }

        assertEquals(7, repostedKeys.size, "transactions Wallet re-posted")
        assertEquals(10, ledgerOf(WEEK_2).size, "the ledger is unmoved by any of them")
    }

    @Test
    fun `every parsed transaction carries a card`() {
        for (fixture in CORPORA) {
            val drafts = draftsOf(fixture)
            assertTrue(drafts.isNotEmpty(), fixture)
            assertTrue(drafts.all { it.cardLast4 != null && it.cardLabel != null }, fixture)
        }
    }

    @Test
    fun `a transitional receipt notification has no identity and so can never be counted`() {
        val transitional =
            FixtureCorpus
                .load(WEEK_2)
                .filter { it.title == "Preparing your receipt" }

        assertEquals(2, transitional.size)
        assertTrue(
            transitional.all { NotificationKey.parse(it.notificationKey)?.tag == null },
            "Wallet reuses one fixed, tagless slot for these",
        )
        assertNotNull(transitional.first().text)
    }

    private companion object {
        const val WEEK_1 = "wallet-week1.jsonl"
        const val WEEK_2 = "wallet-week2.jsonl"
        val CORPORA = listOf(WEEK_1, WEEK_2)

        /** 120,19 $ — posted in French, on a French device. */
        const val FRENCH_ONLY_KEY = "-1339632013"

        /** $19.99 — posted in English while the device reported fr-CA. */
        const val ENGLISH_ONLY_KEY = "1737214725"

        /** 18,81 $ — captured as fr-CA, replayed 51 hours later as en-CA. */
        const val REPLAYED_KEY = "400061172"
    }
}
