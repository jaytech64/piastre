// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import io.github.jaytech64.depenses.core.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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

    /**
     * A balance, which unlike a price has a direction worth stating: a gain
     * carries an explicit plus, a loss the locale's minus, and zero neither.
     */
    fun signedMoney(
        amount: Money,
        locale: Locale = Locale.getDefault(),
    ): String = if (amount.minor > 0) "+" + money(amount, locale) else money(amount, locale)

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

    /**
     * A day and its month, without the weekday — the two ends of a week's label,
     * where naming the weekday twice would say nothing the dates do not.
     */
    fun dayAndMonth(
        date: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String = DAY_MONTH.withLocale(locale).format(date)

    /**
     * A day short enough for a chip: `Sep 1` and `1er sept.`, with the year only
     * when it is not [today]'s, where leaving it out would say the wrong one.
     *
     * English puts the month first here, where [dayAndMonth] does not: a chip
     * reads as a date written on a form, and that is how English writes it.
     */
    fun shortDate(
        date: LocalDate,
        today: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String {
        val dayAndMonth =
            if (locale.language == Locale.FRENCH.language) {
                dayOfMonth(date.dayOfMonth, locale) + NBSP + SHORT_MONTH.withLocale(locale).format(date)
            } else {
                SHORT_MONTH.withLocale(locale).format(date) + NBSP + date.dayOfMonth
            }
        return withYearUnlessCurrent(dayAndMonth, date, today, locale)
    }

    /**
     * Two days as one span, both included: `Sep 1 – 30` and `1er – 30 sept.`
     * within a month, and each day whole — `Sep 28 – Oct 3` — across two.
     */
    fun dateSpan(
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (YearMonth.from(from) != YearMonth.from(to)) {
            return shortDate(from, today, locale) + SPAN + shortDate(to, today, locale)
        }
        val month = SHORT_MONTH.withLocale(locale).format(from)
        val days =
            if (locale.language == Locale.FRENCH.language) {
                dayOfMonth(from.dayOfMonth, locale) + SPAN + to.dayOfMonth + NBSP + month
            } else {
                month + NBSP + from.dayOfMonth + SPAN + to.dayOfMonth
            }
        return withYearUnlessCurrent(days, from, today, locale)
    }

    private fun withYearUnlessCurrent(
        text: String,
        date: LocalDate,
        today: LocalDate,
        locale: Locale,
    ): String =
        when {
            date.year == today.year -> text
            locale.language == Locale.FRENCH.language -> text + NBSP + date.year
            else -> text + "," + NBSP + date.year
        }

    private val SHORT_MONTH = DateTimeFormatter.ofPattern("MMM")

    private const val SPAN = " – "

    /** Between the parts of one date, so a line never breaks a day from its month. */
    private const val NBSP = "\u00A0"

    /** The day of the week, named: `Friday`, `vendredi`. */
    fun weekday(
        date: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String = WEEKDAY.withLocale(locale).format(date)

    /**
     * A day of the month as a date says it, for the sentence around it to place:
     * `25th` and `1st` in English, `25` and `1er` in French, where only the first
     * of the month takes an ordinal.
     *
     * Worked out by hand rather than by ICU's ordinal rules, which give French
     * `25e` — right for a rank, wrong for a date.
     */
    fun dayOfMonth(
        day: Int,
        locale: Locale = Locale.getDefault(),
    ): String =
        when (locale.language) {
            Locale.FRENCH.language -> if (day == 1) "1er" else day.toString()
            else -> day.toString() + englishOrdinalSuffix(day)
        }

    /** `st`, `nd`, `rd` after a 1, 2 or 3, except from 10 to 19, which all take `th` like the rest. */
    private fun englishOrdinalSuffix(day: Int): String =
        if (day / TEN ==
            1
        ) {
            ENGLISH_SUFFIXES.first()
        } else {
            ENGLISH_SUFFIXES.getOrElse(day % TEN) { ENGLISH_SUFFIXES.first() }
        }

    private val ENGLISH_SUFFIXES = listOf("th", "st", "nd", "rd")

    private const val TEN = 10

    /**
     * The month a report is read over, named and dated.
     *
     * The year is carried here where [dayHeader] drops it, because stepping back
     * through months is how you reach last December, and a heading that said only
     * "décembre" would not say which one.
     */
    fun monthYear(
        month: YearMonth,
        locale: Locale = Locale.getDefault(),
    ): String = MONTH_AND_YEAR.withLocale(locale).format(month)

    /** Weekday, day, month — never the year, which on a ledger is always this one. */
    private val DAY_AND_MONTH = DateTimeFormatter.ofPattern("EEEE d MMMM")

    /**
     * Day then month, as `fr-CA` writes it — the locale this app has to get right,
     * and the same ordering [DAY_AND_MONTH] has always used for the ledger's day
     * headings. `en` reads it as "14 September" rather than "September 14", which
     * is the fallback locale wearing the target locale's word order.
     */
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM")

    private val MONTH_AND_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy")

    private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE")

    /** The two bullets Wallet itself uses ahead of the last four digits. */
    fun cardMask(last4: String?): String? = last4?.let { "\u2022\u2022$it" }

    /** What an account is called on screen: the name you gave it, or else its masked digits. */
    fun accountName(
        name: String?,
        last4: String?,
    ): String? = name ?: cardMask(last4)

    /**
     * Reads an amount a person typed, in whichever convention they typed it.
     *
     * Locale-driven parsing is not enough here. A Quebec phone is set to fr-CA,
     * where the decimal mark is a comma and the group separator a space, but the
     * same person types "12.50" out of habit half the time — and fr-CA parsing
     * reads that as 12, losing fifty cents in silence. So the separators are
     * resolved from the text itself: whichever mark sits ahead of the last one or
     * two digits is the decimal one, and everything else is grouping.
     *
     * Returns null rather than zero when nothing numeric was typed, so a caller
     * can keep the field in an error state instead of saving a free purchase.
     */
    fun parseMoney(
        text: String,
        currency: String = Money.CAD,
    ): Money? {
        // Trimmed at both ends because the app's own output ends in a separator:
        // "65,54 $" keeps its non-breaking space, which would otherwise read as the
        // last mark and turn the amount into 655 400 cents.
        val digitsAndMarks =
            text
                .filter { it.isDigit() || it in SEPARATORS }
                .trim { it in SEPARATORS }
        if (digitsAndMarks.none { it.isDigit() }) return null

        val lastMark = digitsAndMarks.indexOfLast { it in SEPARATORS }
        val decimalsTyped = digitsAndMarks.length - lastMark - 1
        val hasDecimalMark = lastMark >= 0 && decimalsTyped in 1..MAX_DECIMALS

        val whole = (if (hasDecimalMark) digitsAndMarks.take(lastMark) else digitsAndMarks).filter(Char::isDigit)
        val fraction = if (hasDecimalMark) digitsAndMarks.substring(lastMark + 1) else ""

        val amount = BigDecimal(whole.ifEmpty { "0" } + "." + fraction.padEnd(MAX_DECIMALS, '0'))
        val minor = amount.movePointRight(MAX_DECIMALS).setScale(0, RoundingMode.HALF_UP).toLong()
        return Money(if (text.trimStart().startsWith('-')) -minor else minor, currency)
    }

    /**
     * An amount the way you would type it into a field: the locale's decimal
     * mark, and no symbol or grouping to delete before you can change a digit.
     * Reads back through [parseMoney] to the same amount.
     */
    fun amountInput(
        amount: Money,
        locale: Locale = Locale.getDefault(),
    ): String =
        NumberFormat
            .getNumberInstance(locale)
            .apply {
                isGroupingUsed = false
                minimumFractionDigits = MAX_DECIMALS
                maximumFractionDigits = MAX_DECIMALS
            }.format(BigDecimal.valueOf(amount.minor, MAX_DECIMALS))

    /** Both marks are accepted wherever the phone is set, so both are stripped. */
    private const val SEPARATORS = ",.\u00A0\u202F\u2009\u2007 "

    private const val MAX_DECIMALS = 2
}
