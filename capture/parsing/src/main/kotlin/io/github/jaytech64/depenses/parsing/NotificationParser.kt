// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.parsing

interface NotificationParser {
    /** Stable id, persisted on every transaction this parser produced. */
    val id: String

    /**
     * Bump on any behaviour change. The upgrade path re-parses every raw event
     * recorded by a lower version, so fixing a bug repairs history rather than
     * only improving the future.
     */
    val version: Int

    /**
     * The packages this parser reads, declared rather than inferred so the
     * listener can reject a notification before it opens the bundle.
     *
     * [handles] decides whether a payload is this parser's; this decides whether
     * the payload is ever read at all. The distinction is the whole of the app's
     * data minimisation: a notification from a package no parser named leaves no
     * trace, not even a discarded row.
     */
    val packages: Set<String>

    /**
     * What to call the app these notifications come from, on screen.
     *
     * Named here because the parser is the only thing that knows: deriving it from
     * the package gives "walletnfcrel", and a screen that kept its own mapping
     * would be a second list to forget to update.
     */
    val sourceName: String

    fun handles(notification: RawNotification): Boolean

    fun parse(notification: RawNotification): ParseResult
}

/** First parser that claims the notification wins. */
class ParserRegistry(
    private val parsers: List<NotificationParser>,
) {
    /** Every package any parser claims — the listener's allowlist. */
    val watchedPackages: Set<String> = parsers.flatMapTo(mutableSetOf()) { it.packages }

    /** What to call [sourcePackage] on screen, or the package itself if none claims it. */
    fun sourceName(sourcePackage: String): String =
        parsers.firstOrNull { sourcePackage in it.packages }?.sourceName ?: sourcePackage

    fun parse(notification: RawNotification): ParseResult =
        parsers
            .firstOrNull { it.handles(notification) }
            ?.parse(notification)
            ?: ParseResult.NotApplicable

    fun parserFor(notification: RawNotification): NotificationParser? = parsers.firstOrNull { it.handles(notification) }
}
