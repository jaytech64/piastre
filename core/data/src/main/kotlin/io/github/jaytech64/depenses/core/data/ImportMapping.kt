// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.database.AccountEntity
import io.github.jaytech64.depenses.core.database.ExpenseTransactionEntity
import io.github.jaytech64.depenses.core.database.MerchantAliasEntity
import io.github.jaytech64.depenses.core.database.RecurringTransactionEntity
import io.github.jaytech64.depenses.core.database.TransactionTemplateEntity
import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.AliasOrigin
import io.github.jaytech64.depenses.core.model.MatchType
import io.github.jaytech64.depenses.core.model.Rhythm
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import io.github.jaytech64.depenses.core.model.localDateEpochDay
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The Wallet card a Wallet account in the file is, found by the issuer and digits every notification carries. */
internal fun List<AccountEntity>.cardMatching(file: ExportedAccount): AccountEntity? {
    if (file.origin != AccountOrigin.WALLET.name || file.last4 == null) return null
    return firstOrNull {
        it.origin == AccountOrigin.WALLET && it.issuerLabel == file.issuerLabel && it.last4 == file.last4
    }
}

/** Your accounts answering to the file account's name; none when it has no name. */
internal fun List<AccountEntity>.namedLike(file: ExportedAccount): List<AccountEntity> {
    val name = file.name?.forMatching() ?: return emptyList()
    return filter { it.name?.forMatching() == name }
}

/**
 * How two account names are compared: case folded, accents kept, so `débit`
 * finds Débit and `Debit` does not. Composed first, because a name typed on one
 * system can arrive from another with its accents decomposed.
 */
private fun String.forMatching(): String = Normalizer.normalize(trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

internal fun ExportedTransaction.localDay(): LocalDate =
    LocalDate.ofEpochDay(localDateEpochDay(occurredAtUtcMillis, ZoneId.of(zoneId)).toLong())

internal fun ExportedAccount.toEntity() =
    AccountEntity(
        name = name?.trim()?.takeIf { it.isNotEmpty() },
        type = AccountType.valueOf(type),
        issuerLabel = issuerLabel,
        last4 = last4,
        origin = AccountOrigin.valueOf(origin),
        openingBalanceMinor = openingBalanceMinor,
    )

internal fun ExportedAlias.toEntity(merchantId: Long) =
    MerchantAliasEntity(
        merchantId = merchantId,
        pattern = pattern,
        matchType = MatchType.valueOf(matchType),
        createdBy = AliasOrigin.valueOf(createdBy),
    )

/**
 * The raw log is never exported, so a restored row points at no raw event; and
 * a review reason is not exported either, so a row still waiting for review
 * comes back without one.
 */
internal fun ExportedTransaction.toEntity(
    accountIds: Map<Long, Long>,
    merchantIds: Map<Long, Long?>,
    categoryIds: Map<Long, Long?>,
    recurringIds: Map<Long, Long>,
) = ExpenseTransactionEntity(
    rawEventId = null,
    amountMinor = amountMinor,
    currency = currency,
    occurredAtUtcMillis = occurredAtUtcMillis,
    zoneId = zoneId,
    localDateEpochDay = localDateEpochDay(occurredAtUtcMillis, ZoneId.of(zoneId)),
    merchantId = merchantId?.let { merchantIds[it] },
    merchantRaw = merchantRaw,
    categoryId = categoryId?.let { categoryIds[it] },
    source = TransactionSource.valueOf(source),
    status = TransactionStatus.valueOf(status),
    reviewReason = null,
    reviewedAtUtcMillis = null,
    confidence = confidence,
    parserId = parserId,
    parserVersion = parserVersion,
    sourcePackage = sourcePackage,
    sourceKey = sourceKey,
    cardLabel = cardLabel,
    cardLast4 = cardLast4,
    note = note,
    kind = TransactionKind.valueOf(kind),
    accountId = accountId?.let(accountIds::getValue),
    toAccountId = toAccountId?.let(accountIds::getValue),
    recurringId = recurringId?.let(recurringIds::getValue),
)

internal fun ExportedRecurring.toEntity(
    accountIds: Map<Long, Long>,
    merchantIds: Map<Long, Long?>,
    categoryIds: Map<Long, Long?>,
) = RecurringTransactionEntity(
    uuid = uuid,
    amountMinor = amountMinor,
    currency = currency,
    kind = TransactionKind.valueOf(kind),
    merchantId = merchantId?.let { merchantIds[it] },
    merchantRaw = merchantRaw,
    accountId = accountId?.let(accountIds::getValue),
    toAccountId = toAccountId?.let(accountIds::getValue),
    categoryId = categoryId?.let { categoryIds[it] },
    note = note,
    rhythm = Rhythm.valueOf(rhythm),
    firstEpochDay = epochDay(firstDate),
    lastEpochDay = lastDate?.let(::epochDay),
    nextDueEpochDay = nextDue?.let(::epochDay),
)

internal fun ExportedTemplate.toEntity(
    accountIds: Map<Long, Long>,
    merchantIds: Map<Long, Long?>,
    categoryIds: Map<Long, Long?>,
) = TransactionTemplateEntity(
    uuid = uuid,
    name = name,
    kind = TransactionKind.valueOf(kind),
    amountMinor = amountMinor,
    currency = currency,
    merchantId = merchantId?.let { merchantIds[it] },
    merchantRaw = merchantRaw,
    accountId = accountId?.let(accountIds::getValue),
    toAccountId = toAccountId?.let(accountIds::getValue),
    categoryId = categoryId?.let { categoryIds[it] },
    note = note,
    lastUsedAtUtcMillis = lastUsedAtUtcMillis,
)

private fun epochDay(isoDate: String): Int = LocalDate.parse(isoDate).toEpochDay().toInt()
