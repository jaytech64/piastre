// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.model.AccountOrigin
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.AliasOrigin
import io.github.jaytech64.depenses.core.model.MatchType
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.DateTimeException
import java.time.ZoneId

/**
 * The version is read before anything else, so a file from another version is
 * refused for being from another version rather than for failing to fit this
 * one's shape. Unknown fields are ignored: adding one is what keeps a format at
 * its version.
 */
internal fun decodeExport(text: String): ExportDocument {
    val root =
        try {
            READER.parseToJsonElement(text.removePrefix(BYTE_ORDER_MARK))
        } catch (notJson: SerializationException) {
            refuse(ImportProblem.NotAnExport, notJson)
        }
    val version = ((root as? JsonObject)?.get("version") as? JsonPrimitive)?.intOrNull
    when (version) {
        null -> refuse(ImportProblem.NotAnExport)
        LEDGER_FORMAT_VERSION -> Unit
        else -> refuse(ImportProblem.UnsupportedVersion(version))
    }
    return try {
        READER.decodeFromJsonElement(ExportDocument.serializer(), root)
    } catch (malformed: SerializationException) {
        refuse(ImportProblem.Inconsistent(malformed.message.orEmpty()), malformed)
    }
}

/**
 * Every id the file refers to is one it lists, and every stored code is one the
 * app knows — checked before anything is written, so a bad file is refused
 * whole rather than half imported.
 */
internal fun ExportDocument.check() {
    val accountIds = accounts.map { it.id }.toSet()
    val merchantIds = merchants.map { it.id }.toSet()
    val categoryIds = categories.map { it.id }.toSet()
    if (accountIds.size != accounts.size) inconsistent("two accounts share an id")
    if (merchantIds.size != merchants.size) inconsistent("two merchants share an id")
    if (categoryIds.size != categories.size) inconsistent("two categories share an id")
    accounts.forEach { it.checkCodes() }
    checkCategoryReferences(categoryIds)
    val recurringIds = checkRecurring(accountIds, merchantIds)
    checkTemplates(accountIds, merchantIds)
    aliases.forEach { it.checkCodes(merchantIds) }
    val identities = HashSet<Pair<String, String>>()
    transactions.forEach { transaction ->
        transaction.checkCodes()
        transaction.checkReferences(accountIds, merchantIds)
        if (transaction.recurringId != null && transaction.recurringId !in recurringIds) {
            inconsistent("transaction ${transaction.sourceKey} belongs to a recurring transaction not in the file")
        }
        if (!identities.add(transaction.sourcePackage to transaction.sourceKey)) {
            inconsistent("transaction ${transaction.sourceKey} appears twice")
        }
    }
}

/**
 * Every category anything in the file names is one the file lists,
 * and so is every group a category is in.
 */
private fun ExportDocument.checkCategoryReferences(categoryIds: Set<Long>) {
    val groupIds = categoryGroups.map { it.id }.toSet()
    if (groupIds.size != categoryGroups.size) inconsistent("two category groups share an id")
    categories.forEach { category ->
        if (category.groupId != null && category.groupId !in groupIds) {
            inconsistent("category ${category.id} is in a group not in the file")
        }
    }
    val named =
        transactions.map { it.categoryId to "transaction ${it.sourceKey}" } +
            merchants.map { it.categoryId to "merchant ${it.id}" } +
            accounts.map { it.categoryId to "account ${it.id}" } +
            recurring.map { it.categoryId to "recurring transaction ${it.id}" } +
            templates.map { it.categoryId to "template ${it.id}" }
    named.forEach { (id, owner) ->
        if (id != null && id !in categoryIds) inconsistent("$owner has a category not in the file")
    }
}

private fun ExportedAccount.checkCodes() {
    if (type !in AccountType.entries.names()) inconsistent("account $id has type '$type'")
    if (origin !in AccountOrigin.entries.names()) inconsistent("account $id has origin '$origin'")
}

private fun ExportedAlias.checkCodes(merchantIds: Set<Long>) {
    if (matchType !in MatchType.entries.names()) inconsistent("alias '$pattern' has match type '$matchType'")
    if (createdBy !in AliasOrigin.entries.names()) inconsistent("alias '$pattern' was created by '$createdBy'")
    if (merchantId !in merchantIds) inconsistent("alias '$pattern' points at a merchant not in the file")
}

private fun ExportedTransaction.checkCodes() {
    val problem =
        when {
            source !in TransactionSource.entries.names() -> "source '$source'"
            status !in TransactionStatus.entries.names() -> "status '$status'"
            kind !in TransactionKind.entries.names() -> "kind '$kind'"
            currency.length != CURRENCY_CODE_LENGTH -> "currency '$currency'"
            !isZone(zoneId) -> "zone '$zoneId'"
            else -> return
        }
    inconsistent("transaction $sourceKey has $problem")
}

private fun ExportedTransaction.checkReferences(
    accountIds: Set<Long>,
    merchantIds: Set<Long>,
) {
    val problem =
        when {
            accountId != null && accountId !in accountIds -> "an account not in the file"
            toAccountId != null && toAccountId !in accountIds -> "a destination account not in the file"
            isTransferMissingAnAccount -> "a transfer missing an account"
            merchantId != null && merchantId !in merchantIds -> "a merchant not in the file"
            else -> return
        }
    inconsistent("transaction $sourceKey has $problem")
}

private val ExportedTransaction.isTransferMissingAnAccount: Boolean
    get() = kind == TransactionKind.TRANSFER.name && (accountId == null || toAccountId == null)

private fun isZone(id: String): Boolean =
    try {
        ZoneId.of(id)
        true
    } catch (expected: DateTimeException) {
        false
    }

internal fun <E : Enum<E>> List<E>.names(): Set<String> = mapTo(HashSet()) { it.name }

internal fun inconsistent(detail: String): Nothing = refuse(ImportProblem.Inconsistent(detail))

private val READER = Json { ignoreUnknownKeys = true }

private const val BYTE_ORDER_MARK = "\uFEFF"
internal const val CURRENCY_CODE_LENGTH = 3
