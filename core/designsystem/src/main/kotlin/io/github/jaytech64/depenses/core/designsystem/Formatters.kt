// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import io.github.jaytech64.depenses.core.model.Money
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/**
 * One place decides what money and dates look like.
 *
 * Formatting is deliberately not `"%.2f $".format(...)`: in fr-CA the symbol goes
 * after the amount, the decimal separator is a comma and the spacer is U+202F, and
 * a hand-rolled string gets at least one of those wrong.
 */
object Formatters {
    /**
     * The locale decides placement, separators and spacing; the currency decides
     * the symbol.
     *
     * Java's default pairing of the two is wrong for this app: asked for CAD in
     * fr-CA it produces "1 234,56 $ CA", tagging the country to disambiguate
     * against other dollars nobody on a Quebec phone is looking at. The home
     * currency therefore carries a bare symbol, and every other currency keeps
     * the locale's disambiguated one — so "7,45 $ US" still cannot be mistaken
     * for local money.
     */
    fun money(
        amount: Money,
        locale: Locale = Locale.getDefault(),
    ): String {
        val currency = Currency.getInstance(amount.currency)
        val format = NumberFormat.getCurrencyInstance(locale)
        format.currency = currency
        if (format is DecimalFormat) {
            format.decimalFormatSymbols =
                format.decimalFormatSymbols.apply {
                    this.currency = currency
                    currencySymbol = HOME_SYMBOLS[amount.currency] ?: currency.getSymbol(locale)
                }
        }
        return format.format(amount.minor.toDouble() / Money.MINOR_UNITS)
    }

    /** Currencies this app treats as local, and the symbol to write them with. */
    private val HOME_SYMBOLS = mapOf(Money.CAD to "$")

    fun date(
        date: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

    /**
     * The time of day, in the zone the purchase happened in rather than the one the
     * phone is in now. Reading a Montréal coffee back as 03:47 from Lisbon would be
     * technically true and useless.
     */
    fun time(
        instant: Instant,
        zone: ZoneId,
        locale: Locale = Locale.getDefault(),
    ): String =
        DateTimeFormatter
            .ofLocalizedTime(FormatStyle.SHORT)
            .withLocale(locale)
            .format(instant.atZone(zone))

    /**
     * A day heading that says how long ago it was when that is what you actually
     * think in, and names the day otherwise.
     *
     * The relative labels are the caller's, because the words belong to the screen's
     * resources and this module has none.
     */
    fun dayHeader(
        date: LocalDate,
        today: LocalDate,
        labels: RelativeDayLabels,
        locale: Locale = Locale.getDefault(),
    ): String =
        when (date) {
            today -> labels.today
            today.minusDays(1) -> labels.yesterday
            else -> DAY_AND_MONTH.withLocale(locale).format(date)
        }

    /** Wording for the two days a person names rather than dates. */
    data class RelativeDayLabels(
        val today: String,
        val yesterday: String,
    )

    /** Weekday, day, month — never the year, which on a ledger is always this one. */
    private val DAY_AND_MONTH = DateTimeFormatter.ofPattern("EEEE d MMMM")

    /** The two bullets Wallet itself uses ahead of the last four digits. */
    fun cardMask(last4: String?): String? = last4?.let { "\u2022\u2022$it" }
}
