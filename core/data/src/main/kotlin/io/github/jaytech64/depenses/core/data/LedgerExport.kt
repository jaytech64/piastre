// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.AccountDao
import io.github.jaytech64.depenses.core.database.MerchantDao
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.database.TransactionWithMerchant
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class ExportFormat(
    val mimeType: String,
    val extension: String,
) {
    CSV("text/csv", "csv"),
    JSON("application/json", "json"),
}

/**
 * The CSV headings, handed in by the screen because this module has no
 * resources of its own — the same arrangement the date formatters use.
 *
 * Only the headings are translated. The values below them are not: `MANUAL`,
 * `CONFIRMED` and the currency code are the stored data, and an export whose
 * *contents* change with the phone's language is a worse artifact than one whose
 * column titles do.
 */
data class CsvLabels(
    val date: String,
    val time: String,
    val merchant: String,
    val amount: String,
    val currency: String,
    val source: String,
    val status: String,
    val card: String,
    val note: String,
    val type: String,
    val account: String,
    val toAccount: String,
) {
    /** The columns added with income and accounts come last, so every earlier column keeps its position. */
    internal fun headerRow(): List<String> =
        listOf(date, time, merchant, amount, currency, source, status, card, note, type, account, toAccount)
}

/**
 * Writes the ledger out in a form you can keep, read and one day load back.
 *
 * The raw notification log is deliberately not part of either format. Law 25
 * portability is about the data describing you, and the raw log is this app's
 * working evidence — the half that can be deleted without losing history, and
 * the half you would least want sitting in a file outside the app.
 */
@Singleton
class LedgerExport
    @Inject
    constructor(
        private val transactions: TransactionDao,
        private val merchants: MerchantDao,
        private val accounts: AccountDao,
        private val clock: Clock,
    ) {
        /** Returns how many transactions were written. */
        suspend fun write(
            out: OutputStream,
            format: ExportFormat,
            labels: CsvLabels,
        ): Int {
            val rows = transactions.allWithMerchant()
            when (format) {
                ExportFormat.CSV -> writeCsv(out, rows, labels)
                ExportFormat.JSON -> writeJson(out, rows)
            }
            return rows.size
        }

        /**
         * Semicolon-separated, with a UTF-8 byte-order mark.
         *
         * Both are for one program. Excel on a French system splits on the list
         * separator, which is a semicolon precisely because the comma is already
         * the decimal mark — a comma-separated file turns `16,09` into two
         * columns. And without the BOM the same Excel reads the file as its legacy
         * codepage, which is how `MARCHÉ` arrives as `MARCHÃ‰`.
         */
        private fun writeCsv(
            out: OutputStream,
            rows: List<TransactionWithMerchant>,
            labels: CsvLabels,
        ) {
            val writer = out.bufferedWriter(Charsets.UTF_8)
            writer.write(BYTE_ORDER_MARK)
            writer.appendLine(labels.headerRow().joinToString(FIELD_SEPARATOR))
            rows.forEach { row ->
                writer.appendLine(row.csvFields().joinToString(FIELD_SEPARATOR, transform = ::quote))
            }
            writer.flush()
        }

        private fun TransactionWithMerchant.csvFields(): List<String> {
            val zone = ZoneId.of(transaction.zoneId)
            val moment = Instant.ofEpochMilli(transaction.occurredAtUtcMillis).atZone(zone)
            return listOf(
                DATE.format(moment),
                TIME.format(moment),
                merchantName ?: transaction.merchantRaw.orEmpty(),
                minorUnitsAsDecimal(transaction.amountMinor),
                transaction.currency,
                transaction.source.name,
                transaction.status.name,
                transaction.cardLast4.orEmpty(),
                transaction.note.orEmpty(),
                transaction.kind.name,
                accountLabel(),
                toAccountName.orEmpty(),
            )
        }

        /**
         * The account by the name you gave it, or by its kind when you have not.
         *
         * Never translated, like every value here: `CASH` is what the database
         * says, and the same file read on an English phone must not disagree with
         * itself on a French one.
         */
        private fun TransactionWithMerchant.accountLabel(): String = accountName ?: accountType?.name.orEmpty()

        /**
         * The amount with a comma, written by hand rather than by a locale.
         *
         * `NumberFormat` would follow whatever the phone is set to, and a ledger
         * exported on an English phone and re-opened on a French one would have to
         * be re-read to be understood. The separator is fixed to the one the
         * semicolon delimiter exists to accommodate.
         */
        private fun minorUnitsAsDecimal(minor: Long): String {
            val sign = if (minor < 0) "-" else ""
            val magnitude = abs(minor)
            val units = magnitude / MINOR_UNITS
            val cents = magnitude % MINOR_UNITS
            return "$sign$units,${cents.toString().padStart(2, '0')}"
        }

        /**
         * Quoted only when it has to be, and never left able to break the row.
         *
         * A merchant string arrives from a notification, so it can hold a
         * semicolon, a newline or a quote; a note is typed, so it certainly can.
         */
        private fun quote(field: String): String =
            if (field.any { it in CHARACTERS_NEEDING_QUOTES }) {
                "\"" + field.replace("\"", "\"\"") + "\""
            } else {
                field
            }

        private suspend fun writeJson(
            out: OutputStream,
            rows: List<TransactionWithMerchant>,
        ) {
            val document =
                ExportDocument(
                    version = LEDGER_FORMAT_VERSION,
                    exportedAtUtc = DateTimeFormatter.ISO_INSTANT.format(clock.instant()),
                    merchants =
                        merchants.allOnce().map {
                            ExportedMerchant(it.id, it.displayName, it.normalisedName)
                        },
                    aliases =
                        merchants.allAliases().map {
                            ExportedAlias(it.id, it.merchantId, it.pattern, it.matchType.name, it.createdBy.name)
                        },
                    accounts =
                        accounts.allOnce().map {
                            ExportedAccount(
                                it.id,
                                it.name,
                                it.type.name,
                                it.issuerLabel,
                                it.last4,
                                it.origin.name,
                                it.openingBalanceMinor,
                                it.sortOrder,
                            )
                        },
                    transactions = rows.map { it.toExported() },
                )
            out.bufferedWriter(Charsets.UTF_8).apply {
                write(JSON.encodeToString(document))
                flush()
            }
        }

        private fun TransactionWithMerchant.toExported() =
            ExportedTransaction(
                id = transaction.id,
                amountMinor = transaction.amountMinor,
                currency = transaction.currency,
                occurredAtUtcMillis = transaction.occurredAtUtcMillis,
                zoneId = transaction.zoneId,
                merchantId = transaction.merchantId,
                merchantName = merchantName,
                merchantRaw = transaction.merchantRaw,
                source = transaction.source.name,
                status = transaction.status.name,
                confidence = transaction.confidence,
                parserId = transaction.parserId,
                parserVersion = transaction.parserVersion,
                sourcePackage = transaction.sourcePackage,
                sourceKey = transaction.sourceKey,
                cardLabel = transaction.cardLabel,
                cardLast4 = transaction.cardLast4,
                note = transaction.note,
                kind = transaction.kind.name,
                accountId = transaction.accountId,
                accountName = accountName,
                toAccountId = transaction.toAccountId,
                toAccountName = toAccountName,
            )

        private companion object {
            const val BYTE_ORDER_MARK = "\uFEFF"
            const val FIELD_SEPARATOR = ";"
            const val CHARACTERS_NEEDING_QUOTES = ";\"\n\r"
            const val MINOR_UNITS = 100L

            val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

            /** Defaults are written too: a field added within a version has one, and still belongs in the file. */
            val JSON =
                Json {
                    prettyPrint = true
                    encodeDefaults = true
                }
        }
    }
