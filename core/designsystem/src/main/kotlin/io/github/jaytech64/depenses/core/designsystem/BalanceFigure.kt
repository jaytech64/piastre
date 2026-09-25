// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * One of the figures a balance is made of: a label, an already formatted amount,
 * and any lines that qualify it, such as a count or a comparison, drawn under
 * the amount.
 */
@Immutable
class BalanceFigure(
    val label: String,
    val amount: String,
    val supporting: List<@Composable () -> Unit> = emptyList(),
)
