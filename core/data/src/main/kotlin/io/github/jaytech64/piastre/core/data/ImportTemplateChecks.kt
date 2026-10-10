// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.model.TransactionKind

/** Every template in the file is whole and points only at what the file lists, and none appears twice. */
internal fun ExportDocument.checkTemplates(
    accountIds: Set<Long>,
    merchantIds: Set<Long>,
) {
    if (templates.map { it.uuid }.toSet().size != templates.size) inconsistent("a template appears twice")
    templates.forEach { template ->
        val problem =
            when {
                template.name.isBlank() -> {
                    "no name"
                }

                template.kind !in TransactionKind.entries.names() -> {
                    "kind '${template.kind}'"
                }

                template.currency.length != CURRENCY_CODE_LENGTH -> {
                    "currency '${template.currency}'"
                }

                template.accountId != null && template.accountId !in accountIds -> {
                    "an account not in the file"
                }

                template.toAccountId != null && template.toAccountId !in accountIds -> {
                    "a destination account not in the file"
                }

                template.merchantId != null && template.merchantId !in merchantIds -> {
                    "a merchant not in the file"
                }

                else -> {
                    return@forEach
                }
            }
        inconsistent("template ${template.id} has $problem")
    }
}
