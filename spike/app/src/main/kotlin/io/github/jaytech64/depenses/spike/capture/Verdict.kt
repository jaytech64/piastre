package io.github.jaytech64.depenses.spike.capture

import io.github.jaytech64.depenses.parsing.GoogleWalletParser
import io.github.jaytech64.depenses.parsing.ParseResult
import io.github.jaytech64.depenses.parsing.ParserRegistry

/**
 * What the production parser makes of each captured notification.
 *
 * Showing this live is the point of a second capture week: a duplicate slipping
 * through is visible as a transaction count higher than the number of purchases
 * actually made, and the offending row can be identified on the spot rather than
 * by re-reading raw JSON afterwards.
 */
sealed interface Verdict {
    /** A payment that reaches the ledger. Exactly one capture per source key gets this. */
    data class Transaction(val sourceKey: String, val amountMinor: Long, val merchant: String?) : Verdict

    /** A re-post of a payment another capture already accounts for. Costs nothing, adds nothing. */
    data class Duplicate(val sourceKey: String, val amountMinor: Long) : Verdict

    data class Review(val reason: String) : Verdict
    data class Ignored(val reason: String) : Verdict
    data object Unhandled : Verdict
}

object Classifier {

    private val registry = ParserRegistry(listOf(GoogleWalletParser()))

    /**
     * Classifies a whole list at once and returns one verdict per capture, in the
     * order given.
     *
     * A single notification cannot be judged alone: whether it is a payment is a
     * property of its own content, but whether it is a *duplicate* is only
     * answerable against its neighbours. Classifying positionally is what keeps
     * the per-row badges and the ledger total from ever disagreeing.
     *
     * Among the re-posts sharing a source key the most recently posted one wins,
     * which is the ledger's upsert rule: later content replaces earlier content.
     */
    fun classify(captures: List<Capture>): List<Verdict> {
        val verdicts = captures.map(::parse).toMutableList()
        captures.indices
            .filter { verdicts[it] is Verdict.Transaction }
            .groupBy { (verdicts[it] as Verdict.Transaction).sourceKey }
            .forEach { (_, rows) ->
                val winner = rows.maxBy { captures[it].postedAtUtcMillis }
                for (row in rows - winner) {
                    val superseded = verdicts[row] as Verdict.Transaction
                    verdicts[row] = Verdict.Duplicate(superseded.sourceKey, superseded.amountMinor)
                }
            }
        return verdicts
    }

    /** Distinct transactions, i.e. what the ledger would actually contain. */
    fun distinctTransactions(captures: List<Capture>): List<Verdict.Transaction> =
        classify(captures).filterIsInstance<Verdict.Transaction>()

    private fun parse(capture: Capture): Verdict =
        when (val result = registry.parse(capture.toRawNotification())) {
            is ParseResult.Parsed -> Verdict.Transaction(
                sourceKey = result.draft.sourceKey,
                amountMinor = result.draft.amount.minor,
                merchant = result.draft.merchantRaw,
            )
            is ParseResult.NeedsReview -> Verdict.Review(result.reason.name)
            is ParseResult.Ignored -> Verdict.Ignored(result.reason.name)
            ParseResult.NotApplicable -> Verdict.Unhandled
        }
}
