// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.capture.listener

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import io.github.jaytech64.depenses.parsing.RawNotification
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Everything worth keeping from one posted notification, already detached from
 * Android's objects.
 *
 * [extras] stays a map rather than a JSON string because building it is cheap and
 * serialising it is not: extraction happens on the callback thread, encoding
 * happens on the one that writes to the database.
 */
internal data class CapturedNotification(
    val notification: RawNotification,
    val extras: Map<String, String>,
) {
    fun extrasJson(): String = JsonObject(extras.mapValues { (_, value) -> JsonPrimitive(value) }).toString()
}

/**
 * Copies a posted notification into plain data.
 *
 * Called from `onNotificationPosted`, which runs on the main thread — reading a
 * [Bundle] is a map lookup, and nothing here parses, allocates a regex, or
 * touches disk.
 */
internal fun StatusBarNotification.capture(deviceLocale: String): CapturedNotification {
    val extras = notification.extras ?: Bundle.EMPTY
    return CapturedNotification(
        notification =
            RawNotification(
                sourcePackage = packageName,
                notificationKey = key,
                postedAtUtcMillis = postTime,
                deviceLocale = deviceLocale,
                title = extras.text(Notification.EXTRA_TITLE),
                text = extras.text(Notification.EXTRA_TEXT),
                bigText = extras.text(Notification.EXTRA_BIG_TEXT),
                subText = extras.text(Notification.EXTRA_SUB_TEXT),
                isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            ),
        extras = textualExtras(extras, notification.category),
    )
}

/**
 * The `android.*` extras whose value is text, plus the notification's category.
 *
 * Serialising the whole [Bundle] would be the obvious implementation and the
 * wrong default: it drags in people, icons and pending intents that no parser can
 * read, into the most sensitive table this app has. Everything kept here is text
 * that was already on the lock screen, which is generous enough that a parser
 * written years from now still has something to re-read.
 *
 * Sorted so that two identical notifications produce byte-identical JSON, which
 * is what makes the stored payload diffable.
 */
private fun textualExtras(
    extras: Bundle,
    category: String?,
): Map<String, String> {
    val textual =
        extras
            .keySet()
            .filter { it.startsWith(ANDROID_PREFIX) }
            .sorted()
            .mapNotNull { key -> extras.text(key)?.let { key to it } }
    return buildMap {
        putAll(textual)
        category?.let { put(CATEGORY_KEY, it) }
    }
}

/** Null for a key that is absent *or* holds something that is not text. */
private fun Bundle.text(key: String): String? = getCharSequence(key)?.toString()

private const val ANDROID_PREFIX = "android."
private const val CATEGORY_KEY = "category"
