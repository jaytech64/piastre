// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import io.github.jaytech64.depenses.core.model.Money
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.LocalDate
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
}
