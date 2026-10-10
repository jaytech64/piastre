// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.parsing

import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.ReviewReason

/**
 * Parses Google Wallet tap-to-pay notifications.
 *
 * Shape observed over a 7-day capture on a Canadian device (see the fixture
 * corpus in `src/test/resources/fixtures`):
 *
 *     title : PHARMACIE DU COIN NDG
 *     text  : $7.45 with Carte d'accès Desrives ••0000        (en-CA)
 *     text  : 7,45 $ avec Carte d'accès Desrives ••0000       (fr-CA)
 *
 * Every other field — bigText, subText, category, template — was null on all 21
 * captured rows, so the entire payload is those two strings.
 *
 * Three things this parser must survive, all of them measured rather than guessed:
 *
 *  1. Wallet re-posts the same transaction, sometimes seconds later and sometimes
 *     over half an hour later with a fresh postTime. [TransactionDraft.sourceKey]
 *     comes from the notification tag, which stays constant across every re-post,
 *     so identity never depends on content or timing.
 *  2. Android rolls notifications up into an empty group summary.
 *  3. A transitional "Preparing your receipt" notification is posted roughly a
 *     second BEFORE the real one, and carries no amount.
 */
class GoogleWalletParser : NotificationParser {
    override val id: String = PARSER_ID
    override val version: Int = 1

    override val packages: Set<String> = setOf(WALLET_PACKAGE)

    override val sourceName: String = "Google Wallet"

    override fun handles(notification: RawNotification): Boolean = notification.sourcePackage == WALLET_PACKAGE

    override fun parse(notification: RawNotification): ParseResult {
        if (notification.isGroupSummary) {
            return ParseResult.Ignored(IgnoreReason.GROUP_SUMMARY)
        }

        val text = notification.text?.let(MoneyText::normaliseSpaces)?.trim()
        if (text.isNullOrEmpty()) {
            return ParseResult.Ignored(IgnoreReason.NOT_A_PAYMENT)
        }

        val payment =
            matchPayment(text)
                ?: return ParseResult.Ignored(IgnoreReason.NOT_A_PAYMENT)

        val amountMinor =
            MoneyText.parseAmountMinor(payment.amount)
                ?: return ParseResult.Ignored(IgnoreReason.NOT_A_PAYMENT)

        val sourceKey = NotificationKey.parse(notification.notificationKey)?.tag
        val merchant = notification.title?.trim()?.takeUnless { it.isEmpty() }

        val draft =
            TransactionDraft(
                sourcePackage = notification.sourcePackage,
                sourceKey = sourceKey ?: notification.notificationKey,
                merchantRaw = merchant,
                amount = Money(amountMinor, currencyOf(text)),
                cardLabel = payment.cardLabel,
                cardLast4 = payment.cardLast4,
                occurredAtUtcMillis = notification.postedAtUtcMillis,
            )

        return when {
            // Without a tag the same purchase would land twice on the next re-post.
            sourceKey == null -> ParseResult.NeedsReview(draft, ReviewReason.MISSING_SOURCE_KEY)

            merchant == null -> ParseResult.NeedsReview(draft, ReviewReason.MISSING_MERCHANT)

            payment.cardLast4 == null -> ParseResult.NeedsReview(draft, ReviewReason.UNRECOGNISED_FORMAT)

            else -> ParseResult.Parsed(draft, confidence = 1f)
        }
    }

    private data class Payment(
        val amount: String,
        val cardLabel: String?,
        val cardLast4: String?,
    )

    /**
     * Tries the full form first — amount, connector, card, masked digits — because
     * matching all four is what justifies entering the ledger unattended. Falling
     * back to an amount alone yields a draft that goes to the review inbox.
     */
    private fun matchPayment(text: String): Payment? {
        for (pattern in FULL_PATTERNS) {
            val match = pattern.matchEntire(text) ?: continue
            return Payment(
                amount = match.groups["amount"]!!.value,
                cardLabel = match.groups["card"]?.value?.trim(),
                cardLast4 = match.groups["last4"]?.value,
            )
        }
        for (pattern in AMOUNT_ONLY_PATTERNS) {
            val match = pattern.find(text) ?: continue
            return Payment(match.groups["amount"]!!.value, cardLabel = null, cardLast4 = null)
        }
        return null
    }

    /** `$` alone means CAD on a Canadian card; a foreign charge is prefixed, e.g. `US$`. */
    private fun currencyOf(text: String): String =
        when {
            text.contains("US$") -> "USD"
            else -> DEFAULT_CURRENCY
        }

    private companion object {
        const val PARSER_ID = "google-wallet"
        const val WALLET_PACKAGE = "com.google.android.apps.walletnfcrel"
        const val DEFAULT_CURRENCY = "CAD"

        /** Two U+2022 bullets precede the last four digits. */
        const val MASK = "•{2,}"
        const val AMOUNT = "(?<amount>\\d[\\d .,]*\\d|\\d)"

        /**
         * Connectors are matched as alternatives rather than selected by device
         * locale on purpose: the card label arrives in the issuer's language
         * regardless of the phone's, so a locale-keyed parser is already wrong.
         */
        val FULL_PATTERNS =
            listOf(
                // en-CA:  $7.45 with Remises Visa ••0000
                Regex("^(?:US)?\\\$\\s?$AMOUNT\\s+(?:with|avec)\\s+(?<card>.+?)\\s*$MASK\\s?(?<last4>\\d{4})$"),
                // fr-CA:  7,45 $ avec Carte d'accès Desrives ••0000
                Regex("^$AMOUNT\\s?(?:US)?\\\$\\s+(?:avec|with)\\s+(?<card>.+?)\\s*$MASK\\s?(?<last4>\\d{4})$"),
            )

        val AMOUNT_ONLY_PATTERNS =
            listOf(
                Regex("(?:US)?\\\$\\s?$AMOUNT"),
                Regex("$AMOUNT\\s?(?:US)?\\\$"),
            )
    }
}
