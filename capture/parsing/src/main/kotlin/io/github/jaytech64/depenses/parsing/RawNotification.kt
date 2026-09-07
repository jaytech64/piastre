package io.github.jaytech64.depenses.parsing

/**
 * A captured notification, exactly as it was posted. This is the parser's only
 * input: no Android types, so every parser is testable on the JVM in milliseconds.
 */
data class RawNotification(
    val sourcePackage: String,
    val notificationKey: String,
    val postedAtUtcMillis: Long,
    val deviceLocale: String,
    val title: String?,
    val text: String?,
    val bigText: String? = null,
    val subText: String? = null,
    val isGroupSummary: Boolean = false,
)

/**
 * The `userId|package|id|tag|uid` string Android assigns to a notification.
 *
 * Wallet puts a per-transaction value in [tag], which is what makes deduplication
 * possible at all — see [GoogleWalletParser].
 */
data class NotificationKey(
    val userId: String,
    val packageName: String,
    val id: String,
    val tag: String?,
    val uid: String?,
) {
    companion object {
        private const val USER_ID = 0
        private const val PACKAGE = 1
        private const val ID = 2
        private const val TAG = 3
        private const val UID = 4

        fun parse(key: String): NotificationKey? {
            val parts = key.split('|')
            if (parts.size <= TAG) return null
            return NotificationKey(
                userId = parts[USER_ID],
                packageName = parts[PACKAGE],
                id = parts[ID],
                tag = parts[TAG].takeUnless { it == "null" },
                uid = parts.getOrNull(UID),
            )
        }
    }
}
