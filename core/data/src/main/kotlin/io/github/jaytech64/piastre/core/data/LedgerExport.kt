// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.database.AccountDao
import io.github.jaytech64.piastre.core.database.BudgetDao
import io.github.jaytech64.piastre.core.database.CategoryDao
import io.github.jaytech64.piastre.core.database.CategoryGroupDao
import io.github.jaytech64.piastre.core.database.MerchantDao
import io.github.jaytech64.piastre.core.database.PartWithCategory
import io.github.jaytech64.piastre.core.database.RecurringTransactionDao
import io.github.jaytech64.piastre.core.database.RecurringWithNames
import io.github.jaytech64.piastre.core.database.TemplateWithNames
import io.github.jaytech64.piastre.core.database.TransactionDao
import io.github.jaytech64.piastre.core.database.TransactionPartDao
import io.github.jaytech64.piastre.core.database.TransactionTemplateDao
import io.github.jaytech64.piastre.core.database.TransactionWithMerchant
import io.github.jaytech64.piastre.core.model.TransactionKind
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
    val category: String,
    val categoryGroup: String,
) {
    /** Columns added since the first export come last, so every earlier column keeps its position. */
    internal fun headerRow(): List<String> =
        listOf(
            date,
            time,
            merchant,
            amount,
            currency,
            source,
            status,
            card,
            note,
            type,
            account,
            toAccount,
            category,
            categoryGroup,
        )
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
        private val categories: CategoryDao,
        private val categoryGroups: CategoryGroupDao,
        private val recurring: RecurringTransactionDao,
        private val templates: TransactionTemplateDao,
        private val parts: TransactionPartDao,
        private val budgets: BudgetDao,
        private val clock: Clock,
    ) {
        /** Returns how many transactions were written. */
        suspend fun write(
            out: OutputStream,
            format: ExportFormat,
            labels: CsvLabels,
        ): Int {
            val rows = transactions.allWithMerchant()
            val partsByTransaction = parts.allWithNames().first().groupBy { it.part.transactionId }
            when (format) {
                ExportFormat.CSV -> {
                    val groupNames = groupNamesByCategory(categories, categoryGroups)
                    writeCsv(out, rows, labels) { row ->
                        row.csvLines(partsByTransaction[row.transaction.id].orEmpty(), groupNames)
                    }
                }

                ExportFormat.JSON -> {
                    writeJson(out, rows, partsByTransaction)
                }
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
            lines: (TransactionWithMerchant) -> List<List<String>>,
        ) {
            val writer = out.bufferedWriter(Charsets.UTF_8)
            writer.write(BYTE_ORDER_MARK)
            writer.appendLine(labels.headerRow().joinToString(FIELD_SEPARATOR))
            rows.flatMap(lines).forEach { fields ->
                writer.appendLine(fields.joinToString(FIELD_SEPARATOR, transform = ::quote))
            }
            writer.flush()
        }

        /**
         * One line, or for a split one line per part: the same day, merchant and
         * account on each, with that part's amount, category and note. The amount
         * column therefore still adds up to the ledger, and a table pivoted on the
         * category says what went to each (ADR-0018). A part with no note of its
         * own carries the transaction's.
         *
         * A part that goes into another account reads as the transfer it is, with
         * that account where a transfer names its destination (ADR-0019).
         */
        private fun TransactionWithMerchant.csvLines(
            parts: List<PartWithCategory>,
            groupNames: Map<Long, String>,
        ): List<List<String>> {
            if (parts.isEmpty()) {
                val whole =
                    CsvShare(
                        amountMinor = transaction.amountMinor,
                        note = transaction.note,
                        categoryName = categoryName,
                        categoryId = transaction.categoryId,
                        kind = transaction.kind,
                        toAccountName = toAccountName,
                    )
                return listOf(csvFields(whole, groupNames))
            }
            return parts.map {
                val movedOn = it.part.toAccountId != null
                csvFields(
                    CsvShare(
                        amountMinor = it.part.amountMinor,
                        note = it.part.note ?: transaction.note,
                        categoryName = it.categoryName,
                        categoryId = it.part.categoryId,
                        kind = if (movedOn) TransactionKind.TRANSFER else transaction.kind,
                        toAccountName = if (movedOn) it.toAccountName else toAccountName,
                    ),
                    groupNames,
                )
            }
        }

        private fun TransactionWithMerchant.csvFields(
            share: CsvShare,
            groupNames: Map<Long, String>,
        ): List<String> {
            val zone = ZoneId.of(transaction.zoneId)
            val moment = Instant.ofEpochMilli(transaction.occurredAtUtcMillis).atZone(zone)
            return listOf(
                DATE.format(moment),
                TIME.format(moment),
                merchantName ?: transaction.merchantRaw.orEmpty(),
                minorUnitsAsDecimal(share.amountMinor),
                transaction.currency,
                transaction.source.name,
                transaction.status.name,
                transaction.cardLast4.orEmpty(),
                share.note.orEmpty(),
                share.kind.name,
                accountLabel(),
                share.toAccountName.orEmpty(),
                share.categoryName.orEmpty(),
                share.categoryId?.let(groupNames::get).orEmpty(),
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

        private suspend fun writeJson(
            out: OutputStream,
            rows: List<TransactionWithMerchant>,
            partsByTransaction: Map<Long, List<PartWithCategory>>,
        ) {
            val document =
                ExportDocument(
                    version = LEDGER_FORMAT_VERSION,
                    exportedAtUtc = DateTimeFormatter.ISO_INSTANT.format(clock.instant()),
                    merchants =
                        merchants.allOnce().map {
                            ExportedMerchant(
                                it.id,
                                it.displayName,
                                it.normalisedName,
                                it.expenseCategoryId,
                                it.incomeCategoryId,
                            )
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
                                it.categoryId,
                            )
                        },
                    transactions = rows.map { it.toExported(partsByTransaction[it.transaction.id].orEmpty()) },
                    categories = categories.allOnce().map { ExportedCategory(it.id, it.name, it.groupId, it.icon) },
                    categoryGroups = categoryGroups.allOnce().map { ExportedCategoryGroup(it.id, it.name) },
                    recurring = recurring.allWithNames().first().map { it.toExported() },
                    templates = templates.allWithNames().first().map { it.toExported() },
                    budgets =
                        budgets.allOnce().map {
                            val change = it.toChange()
                            ExportedBudget(
                                it.targetKind,
                                it.targetId,
                                change.from.toString(),
                                it.amountMinor,
                                it.currency,
                            )
                        },
                )
            out.bufferedWriter(Charsets.UTF_8).apply {
                write(JSON.encodeToString(document))
                flush()
            }
        }

        private fun TransactionWithMerchant.toExported(parts: List<PartWithCategory>) =
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
                categoryId = transaction.categoryId,
                categoryName = categoryName,
                recurringId = transaction.recurringId,
                parts =
                    parts.map {
                        ExportedPart(
                            amountMinor = it.part.amountMinor,
                            categoryId = it.part.categoryId,
                            categoryName = it.categoryName,
                            note = it.part.note,
                            toAccountId = it.part.toAccountId,
                            toAccountName = it.toAccountName,
                        )
                    },
            )

        private fun RecurringWithNames.toExported() =
            ExportedRecurring(
                id = recurring.id,
                uuid = recurring.uuid,
                amountMinor = recurring.amountMinor,
                currency = recurring.currency,
                kind = recurring.kind.name,
                merchantId = recurring.merchantId,
                merchantName = merchantName,
                merchantRaw = recurring.merchantRaw,
                accountId = recurring.accountId,
                toAccountId = recurring.toAccountId,
                categoryId = recurring.categoryId,
                note = recurring.note,
                rhythm = recurring.rhythm.name,
                firstDate = isoDay(recurring.firstEpochDay),
                lastDate = recurring.lastEpochDay?.let(::isoDay),
                nextDue = recurring.nextDueEpochDay?.let(::isoDay),
            )

        private fun isoDay(epochDay: Int): String = LocalDate.ofEpochDay(epochDay.toLong()).toString()

        private companion object {
            const val BYTE_ORDER_MARK = "\uFEFF"
            const val FIELD_SEPARATOR = ";"
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

/** The name of each grouped category's group, by category id; a category in none is absent. */
private suspend fun groupNamesByCategory(
    categories: CategoryDao,
    groups: CategoryGroupDao,
): Map<Long, String> {
    val names = groups.allOnce().associate { it.id to it.name }
    return categories
        .allOnce()
        .mapNotNull { category -> category.groupId?.let(names::get)?.let { category.id to it } }
        .toMap()
}

private fun TemplateWithNames.toExported() =
    ExportedTemplate(
        id = template.id,
        uuid = template.uuid,
        name = template.name,
        kind = template.kind.name,
        amountMinor = template.amountMinor,
        currency = template.currency,
        merchantId = template.merchantId,
        merchantName = merchantName,
        merchantRaw = template.merchantRaw,
        accountId = template.accountId,
        toAccountId = template.toAccountId,
        categoryId = template.categoryId,
        note = template.note,
        lastUsedAtUtcMillis = template.lastUsedAtUtcMillis,
        recurring = template.recurring,
    )

/** What one CSV line says of a transaction: the whole of it, or one part of a split. */
private data class CsvShare(
    val amountMinor: Long,
    val note: String?,
    val categoryName: String?,
    val categoryId: Long?,
    val kind: TransactionKind,
    val toAccountName: String?,
)

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

private const val CHARACTERS_NEEDING_QUOTES = ";\"\n\r"
