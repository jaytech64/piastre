// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.model.SourceApp
import io.github.jaytech64.piastre.parsing.NotificationParser
import io.github.jaytech64.piastre.parsing.ParseResult
import io.github.jaytech64.piastre.parsing.RawNotification

/**
 * A parser whose version and verdict the test dictates.
 *
 * The re-parse suites need a parser that is *wrong* at version 1, and the real
 * one is not allowed to have a wrong version checked in. What they are testing
 * is the sweep, not any particular payment format.
 */
internal class StubParser(
    private val forPackage: String,
    override val version: Int,
    override val id: String = "stub-$forPackage",
    private val read: (RawNotification) -> ParseResult,
) : NotificationParser {
    override val packages: Set<String> = setOf(forPackage)

    override val sourceApp: SourceApp = SourceApp.GOOGLE_WALLET

    override fun handles(notification: RawNotification): Boolean = notification.sourcePackage == forPackage

    override fun parse(notification: RawNotification): ParseResult = read(notification)
}
