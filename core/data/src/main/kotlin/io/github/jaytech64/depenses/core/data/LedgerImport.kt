// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.AccountDao
import io.github.jaytech64.depenses.core.database.AccountEntity
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import io.github.jaytech64.depenses.core.database.MerchantDao
import io.github.jaytech64.depenses.core.database.RawNotificationEventDao
import io.github.jaytech64.depenses.core.database.RecurringTransactionDao
import io.github.jaytech64.depenses.core.database.TransactionDao
import io.github.jaytech64.depenses.core.database.inTransaction
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.MerchantNormaliser
import io.github.jaytech64.depenses.core.model.Money
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a JSON export back into the ledger, adding to it and never replacing it.
 *
 * Nothing is written until the whole file has been read, checked and summarised,
 * and then everything is written in one database transaction: a refused file, or
 * a failure halfway, leaves the ledger exactly as it was. A transaction already
 * here under the same source identity is left alone, which is what makes
 * importing the same file twice change nothing. See ADR-0005.
 */
@Singleton
class LedgerImport
    @Inject
    constructor(
        private val database: DepensesDatabase,
        private val transactions: TransactionDao,
        private val merchants: MerchantRepository,
        private val merchantRows: MerchantDao,
        private val accounts: AccountDao,
        private val categories: CategoryRepository,
        private val recurring: RecurringTransactionDao,
        private val posting: RecurringPosting,
    ) {
        /** Reads and checks [input], and says what importing it would do, without writing anything. */
        suspend fun read(input: InputStream): ImportReading {
            val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return try {
                val document = decodeExport(text)
                ImportReading.Ready(document, plan(document).summary)
            } catch (refused: ImportRefused) {
                ImportReading.Refused(refused.problem)
            }
        }

        /**
         * Writes what [reading] found, all or nothing.
         *
         * The plan is made again inside the transaction rather than trusted from the
         * reading: the ledger can change while the summary is on screen, and a
         * purchase captured in the meantime must count as already here.
         *
         * A recurring transaction the file brings may have days that came since it
         * was written, so they are caught up straight away rather than at the next
         * midnight.
         */
        suspend fun apply(reading: ImportReading.Ready): ImportOutcome {
            val outcome =
                try {
                    database.inTransaction {
                        val plan = plan(reading.document)
                        write(reading.document, plan)
                        ImportOutcome.Imported(plan.summary)
                    }
                } catch (refused: ImportRefused) {
                    ImportOutcome.Refused(refused.problem)
                }
            if (outcome is ImportOutcome.Imported) posting.catchUp()
            return outcome
        }

        private suspend fun plan(document: ExportDocument): ImportPlan {
            document.check()
            val existing = accounts.allOnce()
            val targets = document.accounts.associate { it.id to targetFor(it, existing) }
            val fresh =
                document.transactions.filter { transactions.bySourceKey(it.sourcePackage, it.sourceKey) == null }
            val days = fresh.map { it.localDay() }
            val merchantNames =
                document.merchants
                    .mapNotNull { MerchantNormaliser.normalise(it.displayName)?.normalisedName }
                    .distinct()
            return ImportPlan(
                accounts = targets,
                newTransactions = fresh,
                summary =
                    ImportSummary(
                        newTransactions = fresh.size,
                        alreadyHere = document.transactions.size - fresh.size,
                        firstDay = days.minOrNull(),
                        lastDay = days.maxOrNull(),
                        accountsFound =
                            targets.values
                                .filterIsInstance<AccountTarget.Existing>()
                                .distinctBy { it.account.id }
                                .size,
                        accountsCreated = targets.values.count { it is AccountTarget.New },
                        openingBalancesSet = targets.values.count { it.setsOpeningBalance },
                        openingBalancesKept = targets.values.mapNotNull { it.keptOpeningBalance() },
                        newMerchants = merchantNames.count { merchantRows.byNormalisedName(it) == null },
                    ),
            )
        }

        /**
         * A Wallet card is found by the issuer and digits every notification carries,
         * whatever either side named it. Any other account is found by name, an
         * unnamed cash account finds your cash account, and one found nowhere is
         * created.
         */
        private fun targetFor(
            file: ExportedAccount,
            existing: List<AccountEntity>,
        ): AccountTarget {
            val sameCard = existing.cardMatching(file)
            val sameName = existing.namedLike(file)
            return when {
                sameCard != null -> {
                    AccountTarget.Existing(file, sameCard)
                }

                sameName.size > 1 -> {
                    refuse(ImportProblem.AmbiguousAccount(file.name.orEmpty()))
                }

                sameName.size == 1 -> {
                    AccountTarget.Existing(file, sameName.single())
                }

                file.name == null && file.type == AccountType.CASH.name -> {
                    existing.firstOrNull { it.type == AccountType.CASH }?.let { AccountTarget.Existing(file, it) }
                        ?: AccountTarget.New(file)
                }

                else -> {
                    AccountTarget.New(file)
                }
            }
        }

        private suspend fun write(
            document: ExportDocument,
            plan: ImportPlan,
        ) {
            // New accounts join after yours, in the order the file had them. An account
            // you already have keeps the place you gave it here.
            val accountIds =
                plan.accounts.entries
                    .sortedBy { (_, target) -> target.file.sortOrder ?: Int.MAX_VALUE }
                    .associate { (fileId, target) -> fileId to accountIdFor(target) }
            val merchantIds = document.merchants.associate { it.id to merchants.named(it.displayName) }
            document.aliases.forEach { alias ->
                merchantIds[alias.merchantId]?.let { merchantRows.insertAlias(alias.toEntity(it)) }
            }
            val categoryIds = categoryIdsFor(document)
            giveDefaults(document, accountIds, merchantIds, categoryIds)
            val recurringIds =
                document.recurring.associate { it.id to recurringIdFor(it, accountIds, merchantIds, categoryIds) }
            plan.newTransactions.forEach {
                transactions.insert(it.toEntity(accountIds, merchantIds, categoryIds, recurringIds))
            }
        }

        /**
         * A recurring transaction already here, found by its identity, is left
         * exactly as it is — what you changed since the file was written wins — and
         * the file's rows join it. One not here is added.
         */
        private suspend fun recurringIdFor(
            file: ExportedRecurring,
            accountIds: Map<Long, Long>,
            merchantIds: Map<Long, Long?>,
            categoryIds: Map<Long, Long?>,
        ): Long =
            recurring.byUuid(file.uuid)?.id
                ?: recurring.insert(file.toEntity(accountIds, merchantIds, categoryIds))

        /**
         * Each category in the file, found by name or added, in the group the file
         * puts it in. A category of yours already in a group stays there: merging
         * never replaces what you chose (ADR-0005).
         */
        private suspend fun categoryIdsFor(document: ExportDocument): Map<Long, Long?> {
            val groupIds = document.categoryGroups.associate { it.id to categories.namedGroup(it.name) }
            return document.categories.associate { file ->
                val groupId = file.groupId?.let { groupIds[it] }
                val id = categories.named(file.name, groupId)
                if (id != null && groupId != null) categories.groupIfNone(id, groupId)
                file.id to id
            }
        }

        /**
         * The category a merchant or account in the file gives what arrives there,
         * given to yours only when yours gives none: merging adds what is missing
         * and never replaces what you chose (ADR-0005).
         */
        private suspend fun giveDefaults(
            document: ExportDocument,
            accountIds: Map<Long, Long>,
            merchantIds: Map<Long, Long?>,
            categoryIds: Map<Long, Long?>,
        ) {
            document.merchants.forEach { file ->
                val merchant = merchantIds[file.id]?.let { merchantRows.byId(it) } ?: return@forEach
                val category = file.categoryId?.let { categoryIds[it] } ?: return@forEach
                if (merchant.categoryId == null) merchantRows.setCategory(merchant.id, category)
            }
            document.accounts.forEach { file ->
                val account = accountIds[file.id] ?: return@forEach
                val category = file.categoryId?.let { categoryIds[it] } ?: return@forEach
                if (accounts.categoryOf(account) == null) accounts.setCategory(account, category)
            }
        }

        private suspend fun accountIdFor(target: AccountTarget): Long =
            when (target) {
                is AccountTarget.Existing -> {
                    if (target.setsOpeningBalance) {
                        accounts.setOpeningBalance(target.account.id, target.file.openingBalanceMinor)
                    }
                    target.account.id
                }

                is AccountTarget.New -> {
                    accounts.insertLast(target.file.toEntity()).takeIf { it != RawNotificationEventDao.INSERT_IGNORED }
                        ?: refuse(
                            ImportProblem.Inconsistent("account ${target.file.id} collides with one already here"),
                        )
                }
            }
    }

private class ImportPlan(
    val accounts: Map<Long, AccountTarget>,
    val newTransactions: List<ExportedTransaction>,
    val summary: ImportSummary,
)

/** Where an account in the file lands: on one of yours, or on a new one. */
private sealed interface AccountTarget {
    val file: ExportedAccount

    /** True when the file gives an opening balance to an account that has none. */
    val setsOpeningBalance: Boolean

    fun keptOpeningBalance(): KeptOpeningBalance?

    class Existing(
        override val file: ExportedAccount,
        val account: AccountEntity,
    ) : AccountTarget {
        override val setsOpeningBalance: Boolean
            get() = account.openingBalanceMinor == null && file.openingBalanceMinor != null

        /** Yours wins over the file's, decided in review; the summary names the difference rather than hiding it. */
        override fun keptOpeningBalance(): KeptOpeningBalance? {
            val kept = account.openingBalanceMinor ?: return null
            val inFile = file.openingBalanceMinor ?: return null
            if (kept == inFile) return null
            return KeptOpeningBalance(
                account = account.name ?: listOfNotNull(account.issuerLabel, account.last4).joinToString(" "),
                inFile = Money(inFile, Money.CAD),
                kept = Money(kept, Money.CAD),
            )
        }
    }

    class New(
        override val file: ExportedAccount,
    ) : AccountTarget {
        override val setsOpeningBalance: Boolean get() = file.openingBalanceMinor != null

        override fun keptOpeningBalance(): KeptOpeningBalance? = null
    }
}
