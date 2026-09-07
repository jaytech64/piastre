package io.github.jaytech64.depenses.parsing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GoogleWalletParserTest {
    private val parser = GoogleWalletParser()

    private fun notification(
        text: String?,
        title: String? = "THE MATCHA",
        key: String = "0|com.google.android.apps.walletnfcrel|1001|495571253|10348",
        groupSummary: Boolean = false,
        locale: String = "en-CA",
    ) = RawNotification(
        sourcePackage = "com.google.android.apps.walletnfcrel",
        notificationKey = key,
        postedAtUtcMillis = 1_787_235_111_656,
        deviceLocale = locale,
        title = title,
        text = text,
        isGroupSummary = groupSummary,
    )

    @Test
    fun `parses an en-CA charge`() {
        val result = parser.parse(notification("\$20.63 with Remises Visa ••0000"))
        val parsed = assertIs<ParseResult.Parsed>(result)
        assertEquals(2063, parsed.draft.amount.minor)
        assertEquals("CAD", parsed.draft.amount.currency)
        assertEquals("THE MATCHA", parsed.draft.merchantRaw)
        assertEquals("Remises Visa", parsed.draft.cardLabel)
        assertEquals("0000", parsed.draft.cardLast4)
        assertEquals("495571253", parsed.draft.sourceKey)
    }

    @Test
    fun `parses an fr-CA charge with a narrow no-break space`() {
        val result =
            parser.parse(
                notification("7,45\u202F\$ avec Carte d’accès Desrives ••0000", locale = "fr-CA"),
            )
        val parsed = assertIs<ParseResult.Parsed>(result)
        assertEquals(745, parsed.draft.amount.minor)
        assertEquals("Carte d’accès Desrives", parsed.draft.cardLabel)
        assertEquals("0000", parsed.draft.cardLast4)
    }

    @Test
    fun `parses fr-CA whatever space and apostrophe arrived`() {
        for (space in listOf("\u202F", "\u00A0", "\u2009", " ", "")) {
            for (apostrophe in listOf("’", "'")) {
                val text = "1${space}234,56$space\$ avec Carte d${apostrophe}accès Desrives ••0000"
                val parsed = assertIs<ParseResult.Parsed>(parser.parse(notification(text)))
                assertEquals(123456, parsed.draft.amount.minor, "failed on space=${space.map { it.code }}")
            }
        }
    }

    @Test
    fun `the card label is not assumed to match the device language`() {
        // Observed on a real en-CA device: English chrome, French card name.
        val parsed =
            assertIs<ParseResult.Parsed>(
                parser.parse(notification("\$7.45 with Carte d’accès Desrives ••0000")),
            )
        assertEquals("Carte d’accès Desrives", parsed.draft.cardLabel)
    }

    @Test
    fun `ignores an empty group summary`() {
        val result = parser.parse(notification(text = null, title = null, groupSummary = true))
        assertEquals(IgnoreReason.GROUP_SUMMARY, assertIs<ParseResult.Ignored>(result).reason)
    }

    @Test
    fun `ignores the transitional receipt notification`() {
        val result =
            parser.parse(
                notification(
                    title = "Preparing your receipt",
                    text = "We're adding the location to your receipt",
                    key = "0|com.google.android.apps.walletnfcrel|930510|null|10348",
                ),
            )
        assertEquals(IgnoreReason.NOT_A_PAYMENT, assertIs<ParseResult.Ignored>(result).reason)
    }

    @Test
    fun `a re-post carries the identity of the original`() {
        val first = parser.parse(notification("\$20.63 with Remises Visa ••0000"))
        val repostedHalfAnHourLater =
            parser.parse(
                notification("\$20.63 with Remises Visa ••0000").copy(postedAtUtcMillis = 1_787_237_000_000),
            )
        assertEquals(
            assertIs<ParseResult.Parsed>(first).draft.sourceKey,
            assertIs<ParseResult.Parsed>(repostedHalfAnHourLater).draft.sourceKey,
        )
    }

    @Test
    fun `an unrecognised tail goes to review rather than the ledger`() {
        val result = parser.parse(notification("\$20.63 charged somewhere odd"))
        assertEquals(ReviewReason.UNRECOGNISED_FORMAT, assertIs<ParseResult.NeedsReview>(result).reason)
    }

    @Test
    fun `a missing merchant goes to review`() {
        val result = parser.parse(notification("\$20.63 with Remises Visa ••0000", title = null))
        assertEquals(ReviewReason.MISSING_MERCHANT, assertIs<ParseResult.NeedsReview>(result).reason)
    }

    @Test
    fun `declines notifications from other apps`() {
        val foreign = notification("\$20.63 with Remises Visa ••0000").copy(sourcePackage = "com.example.other")
        assertEquals(false, parser.handles(foreign))
        assertEquals(ParseResult.NotApplicable, ParserRegistry(listOf(parser)).parse(foreign))
    }

    @Test
    fun `reads a US charge as USD`() {
        val parsed =
            assertIs<ParseResult.Parsed>(
                parser.parse(notification("US\$12.00 with Remises Visa ••0000")),
            )
        assertEquals("USD", parsed.draft.amount.currency)
        assertEquals(1200, parsed.draft.amount.minor)
    }
}
