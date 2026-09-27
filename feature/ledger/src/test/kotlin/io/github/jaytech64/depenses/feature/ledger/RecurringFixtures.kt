// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.RecurringPosting
import io.github.jaytech64.depenses.core.data.RecurringRepository
import io.github.jaytech64.depenses.core.database.DepensesDatabase
import java.time.Clock
import java.time.ZoneId
import javax.inject.Provider

/** Recurring transactions over this database, on [clock], in [zone]. */
internal fun DepensesDatabase.recurringRepository(
    clock: Clock,
    zone: ZoneId,
): RecurringRepository =
    RecurringRepository(
        this,
        recurringTransactionDao(),
        transactionDao(),
        MerchantRepository(merchantDao()),
        RecurringPosting(
            this,
            recurringTransactionDao(),
            transactionDao(),
            LocalDateTicker(Provider { zone }),
            clock,
            Provider { zone },
        ),
    )
