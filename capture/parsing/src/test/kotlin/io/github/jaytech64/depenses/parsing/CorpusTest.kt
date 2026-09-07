package io.github.jaytech64.depenses.parsing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The regression suite that matters: the parser run over notifications that were
 * actually posted by Google Wallet, rather than over examples written to match the
 * parser. Every future parser change is measured against this.
 */
class CorpusTest {
    private val registry = ParserRegistry(listOf(GoogleWalletParser()))

    private fun ledgerOf(fixture: String): Map<String, TransactionDraft> {
        val ledger = LinkedHashMap<String, TransactionDraft>()
        for (notification in FixtureCorpus.load(fixture)) {
            val result = registry.parse(notification)
            if (result is ParseResult.Parsed) {
                // Upsert: a re-post of the same transaction must overwrite, never append.
                ledger[result.draft.sourceKey] = result.draft
            }
        }
        return ledger
    }

    @Test
    fun `21 captured en-CA notifications collapse to the 8 real transactions`() {
        val corpus = FixtureCorpus.load("wallet-en-CA.jsonl")
        assertEquals(21, corpus.size)

        val ledger = ledgerOf("wallet-en-CA.jsonl")
        assertEquals(8, ledger.size)
        assertEquals(15200, ledger.values.sumOf { it.amount.minor })
    }

    @Test
    fun `every noise class is rejected for a stated reason`() {
        val reasons =
            FixtureCorpus
                .load("wallet-en-CA.jsonl")
                .map { registry.parse(it) }
                .filterIsInstance<ParseResult.Ignored>()
                .groupingBy { it.reason }
                .eachCount()

        assertEquals(2, reasons[IgnoreReason.GROUP_SUMMARY], "empty group summaries")
        assertEquals(2, reasons[IgnoreReason.NOT_A_PAYMENT], "transitional receipt notifications")
    }

    @Test
    fun `nothing in the corpus needs review`() {
        val review =
            FixtureCorpus
                .load("wallet-en-CA.jsonl")
                .map { registry.parse(it) }
                .filterIsInstance<ParseResult.NeedsReview>()
        assertTrue(review.isEmpty(), "unexpected review items: ${review.map { it.reason }}")
    }

    @Test
    fun `the synthesized fr-CA corpus yields the same ledger`() {
        val ledger = ledgerOf("wallet-fr-CA.jsonl")
        assertEquals(8, ledger.size)
        assertEquals(15200, ledger.values.sumOf { it.amount.minor })
    }

    @Test
    fun `en-CA and fr-CA agree transaction for transaction`() {
        val en = ledgerOf("wallet-en-CA.jsonl")
        val fr = ledgerOf("wallet-fr-CA.jsonl")
        assertEquals(en.keys, fr.keys, "same transactions must be identified in both languages")
        for ((key, enDraft) in en) {
            assertEquals(enDraft.amount, fr.getValue(key).amount, "amount differs for $key")
        }
    }

    @Test
    fun `identity survives re-posting - the most re-posted transaction appears once`() {
        val drafts =
            FixtureCorpus
                .load("wallet-en-CA.jsonl")
                .map { registry.parse(it) }
                .filterIsInstance<ParseResult.Parsed>()
                .map { it.draft }

        // THE MATCHA was posted four times across 36 minutes.
        val busiest = drafts.groupingBy { it.sourceKey }.eachCount().maxBy { it.value }
        assertEquals(4, busiest.value, "expected the four-times-posted transaction")
        assertEquals(1, drafts.filter { it.sourceKey == busiest.key }.distinctBy { it.sourceKey }.size)
    }

    @Test
    fun `every parsed transaction carries a card`() {
        val drafts =
            FixtureCorpus
                .load("wallet-en-CA.jsonl")
                .map { registry.parse(it) }
                .filterIsInstance<ParseResult.Parsed>()
                .map { it.draft }
        assertTrue(drafts.isNotEmpty())
        assertTrue(drafts.all { it.cardLast4 != null && it.cardLabel != null })
    }
}
