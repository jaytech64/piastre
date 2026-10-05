// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.TemplateWithNames
import io.github.jaytech64.depenses.core.database.TransactionTemplateDao
import io.github.jaytech64.depenses.core.database.TransactionTemplateEntity
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.TemplateContent
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Templates: saved, changed, and deleted.
 *
 * A template only ever fills the form. Nothing here writes a transaction, and a
 * transaction keeps no link to the template that filled it, so no change made
 * here reaches the ledger.
 */
@Singleton
class TemplateRepository
    @Inject
    constructor(
        private val templates: TransactionTemplateDao,
        private val merchants: MerchantRepository,
        private val clock: Clock,
    ) {
        /** Every template, the one last used first, then those never used, the newest first. */
        fun all(): Flow<List<TransactionTemplate>> = templates.allWithNames().map { rows -> rows.map(::toDomain) }

        fun count(): Flow<Int> = templates.count()

        suspend fun byId(id: Long): TransactionTemplate? = templates.withNamesById(id)?.let(::toDomain)

        /** Keeps [content] as a new template and returns its id. */
        suspend fun save(content: TemplateContent): Long {
            val merchant = content.merchant()
            return templates.insert(
                TransactionTemplateEntity(
                    uuid = UUID.randomUUID().toString(),
                    name = content.name.trim(),
                    kind = content.kind,
                    amountMinor = content.amount?.minor,
                    currency = content.amount?.currency ?: Money.CAD,
                    merchantId = merchant?.let { merchants.assign(raw = it, displayName = it) },
                    merchantRaw = merchant,
                    accountId = content.accountId,
                    toAccountId = content.destination(),
                    categoryId = content.categoryId,
                    note = content.note(),
                    lastUsedAtUtcMillis = null,
                    recurring = content.recurring,
                ),
            )
        }

        /** Rewrites template [id] as [content]. When it was last used, and so its place in the list, is kept. */
        suspend fun change(
            id: Long,
            content: TemplateContent,
        ) {
            val named = templates.withNamesById(id) ?: return
            val existing = named.template
            val merchant = content.merchant()
            val renamed = merchant != (named.merchantName ?: existing.merchantRaw)
            templates.update(
                existing.copy(
                    name = content.name.trim(),
                    kind = content.kind,
                    amountMinor = content.amount?.minor,
                    currency = content.amount?.currency ?: existing.currency,
                    merchantId =
                        if (renamed) {
                            merchant?.let { merchants.assign(raw = it, displayName = it) }
                        } else {
                            existing.merchantId
                        },
                    merchantRaw = if (renamed) merchant else existing.merchantRaw,
                    accountId = content.accountId,
                    toAccountId = content.destination(),
                    categoryId = content.categoryId,
                    note = content.note(),
                    recurring = content.recurring,
                ),
            )
        }

        /** Notes that a transaction filled from template [id] was just saved, which moves it to the front. */
        suspend fun markUsed(id: Long) = templates.markUsed(id, clock.millis())

        /** Removes template [id]. Every transaction it filled stays as it is. */
        suspend fun delete(id: Long) = templates.delete(id)
    }

/** A transfer moves your own money, so it names no merchant. */
private fun TemplateContent.merchant(): String? =
    merchantName?.takeIf { it.isNotBlank() && kind != TransactionKind.TRANSFER }?.trim()

/** Only a transfer has a destination. */
private fun TemplateContent.destination(): Long? = toAccountId.takeIf { kind == TransactionKind.TRANSFER }

private fun TemplateContent.note(): String? = note?.takeIf { it.isNotBlank() }?.trim()

private fun toDomain(row: TemplateWithNames): TransactionTemplate =
    with(row.template) {
        TransactionTemplate(
            id = id,
            name = name,
            kind = kind,
            amount = amountMinor?.let { Money(it, currency) },
            merchantId = merchantId,
            merchantName = row.merchantName ?: merchantRaw,
            accountId = accountId,
            accountName = row.accountName,
            accountType = row.accountType,
            toAccountId = toAccountId,
            toAccountName = row.toAccountName,
            toAccountType = row.toAccountType,
            categoryId = categoryId,
            categoryName = row.categoryName,
            note = note,
            recurring = recurring,
        )
    }
