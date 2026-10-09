// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.ledger

import io.github.jaytech64.piastre.core.data.CategoryGroupRepository
import io.github.jaytech64.piastre.core.database.DepensesDatabase

/** The groups categories are arranged under, over this database. */
internal fun DepensesDatabase.categoryGroupRepository() = CategoryGroupRepository(categoryGroupDao(), categoryDao())
