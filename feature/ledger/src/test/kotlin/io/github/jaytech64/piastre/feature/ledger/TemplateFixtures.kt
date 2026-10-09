// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import io.github.jaytech64.piastre.core.data.MerchantRepository
import io.github.jaytech64.piastre.core.data.TemplateRepository
import io.github.jaytech64.piastre.core.database.DepensesDatabase
import java.time.Clock

/** Templates over this database, on [clock]. */
internal fun DepensesDatabase.templateRepository(clock: Clock = Clock.systemUTC()): TemplateRepository =
    TemplateRepository(transactionTemplateDao(), MerchantRepository(merchantDao()), clock)
