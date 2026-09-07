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

    fun handles(notification: RawNotification): Boolean

    fun parse(notification: RawNotification): ParseResult
}

/** First parser that claims the notification wins. */
class ParserRegistry(
    private val parsers: List<NotificationParser>,
) {
    fun parse(notification: RawNotification): ParseResult =
        parsers
            .firstOrNull { it.handles(notification) }
            ?.parse(notification)
            ?: ParseResult.NotApplicable

    fun parserFor(notification: RawNotification): NotificationParser? = parsers.firstOrNull { it.handles(notification) }
}
