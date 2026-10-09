package io.github.jaytech64.piastre.spike.capture

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Flattens a notification into JSON without interpreting it.
 *
 * The whole point of the spike is that we do not yet know which field carries the
 * merchant or the amount, so every textual extra is kept verbatim and anything
 * non-textual is recorded as a type marker rather than dropped silently.
 */
internal fun StatusBarNotification.toCaptureJson(appLabel: String): JSONObject {
    val extras = notification.extras
    return JSONObject().apply {
        put("packageName", packageName)
        put("appLabel", appLabel)
        put("notificationKey", key)
        put("postedAtUtcMillis", postTime)
        put("deviceLocale", Locale.getDefault().toLanguageTag())
        put("capturedAtUtcMillis", System.currentTimeMillis())

        put("title", extras.textOrNull(Notification.EXTRA_TITLE))
        put("titleBig", extras.textOrNull(Notification.EXTRA_TITLE_BIG))
        put("text", extras.textOrNull(Notification.EXTRA_TEXT))
        put("bigText", extras.textOrNull(Notification.EXTRA_BIG_TEXT))
        put("subText", extras.textOrNull(Notification.EXTRA_SUB_TEXT))
        put("infoText", extras.textOrNull(Notification.EXTRA_INFO_TEXT))
        put("summaryText", extras.textOrNull(Notification.EXTRA_SUMMARY_TEXT))
        put("template", extras.getString(Notification.EXTRA_TEMPLATE) ?: JSONObject.NULL)

        put("category", notification.category ?: JSONObject.NULL)
        put("tickerText", notification.tickerText?.toString() ?: JSONObject.NULL)
        put("flags", notification.flags)
        put("isGroupSummary", notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        put("isOngoing", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

        put("extras", extras.toJson())
    }
}

private fun Bundle.textOrNull(key: String): Any =
    getCharSequence(key)?.toString() ?: JSONObject.NULL

/**
 * Every extra, best-effort. Icons and PendingIntents become type markers so the
 * export stays small and readable while still proving what was present.
 */
private fun Bundle.toJson(): JSONObject {
    val out = JSONObject()
    for (key in keySet()) {
        val value = try {
            @Suppress("DEPRECATION")
            get(key)
        } catch (error: RuntimeException) {
            // A malformed or unmarshallable extra must not cost us the capture.
            "<unreadable: ${error.javaClass.simpleName}>"
        }
        out.put(key, value.toJsonValue())
    }
    return out
}

private fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    is CharSequence -> toString()
    is Number, is Boolean -> this
    is Bundle -> toJson()
    is Array<*> -> JSONArray().also { array -> forEach { array.put(it.toJsonValue()) } }
    is Iterable<*> -> JSONArray().also { array -> forEach { array.put(it.toJsonValue()) } }
    else -> "<${javaClass.simpleName}>"
}
