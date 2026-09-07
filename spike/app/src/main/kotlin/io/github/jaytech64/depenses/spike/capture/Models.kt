package io.github.jaytech64.depenses.spike.capture

import io.github.jaytech64.depenses.parsing.RawNotification
import org.json.JSONObject

/** One captured notification, as it will appear in the exported fixture corpus. */
data class Capture(
    val packageName: String,
    val appLabel: String,
    val postedAtUtcMillis: Long,
    val deviceLocale: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val template: String?,
    val category: String?,
    val isGroupSummary: Boolean,
    val notificationKey: String,
    val rawJson: String,
) {
    /** Feeds the real production parser, so the spike tests what will actually ship. */
    fun toRawNotification(): RawNotification = RawNotification(
        sourcePackage = packageName,
        notificationKey = notificationKey,
        postedAtUtcMillis = postedAtUtcMillis,
        deviceLocale = deviceLocale,
        title = title,
        text = text,
        bigText = bigText,
        subText = subText,
        isGroupSummary = isGroupSummary,
    )

    companion object {
        fun fromJson(json: JSONObject): Capture = Capture(
            packageName = json.getString("packageName"),
            appLabel = json.optString("appLabel", json.getString("packageName")),
            postedAtUtcMillis = json.optLong("postedAtUtcMillis"),
            deviceLocale = json.optString("deviceLocale", ""),
            title = json.optStringOrNull("title"),
            text = json.optStringOrNull("text"),
            bigText = json.optStringOrNull("bigText"),
            subText = json.optStringOrNull("subText"),
            template = json.optStringOrNull("template"),
            category = json.optStringOrNull("category"),
            isGroupSummary = json.optBoolean("isGroupSummary"),
            notificationKey = json.optString("notificationKey"),
            rawJson = json.toString(2),
        )
    }
}

/**
 * Discovery record. Deliberately holds no notification content: this is how the
 * app can tell you which package to watch without ever writing an unrelated
 * app's messages to disk.
 */
data class Sighting(
    val packageName: String,
    val appLabel: String,
    val firstSeenUtcMillis: Long,
    val lastSeenUtcMillis: Long,
    val count: Int,
    val capturedCount: Int,
    val watched: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appLabel", appLabel)
        put("firstSeenUtcMillis", firstSeenUtcMillis)
        put("lastSeenUtcMillis", lastSeenUtcMillis)
        put("count", count)
        put("capturedCount", capturedCount)
    }

    companion object {
        fun fromJson(json: JSONObject): Sighting = Sighting(
            packageName = json.getString("packageName"),
            appLabel = json.optString("appLabel", json.getString("packageName")),
            firstSeenUtcMillis = json.optLong("firstSeenUtcMillis"),
            lastSeenUtcMillis = json.optLong("lastSeenUtcMillis"),
            count = json.optInt("count"),
            capturedCount = json.optInt("capturedCount"),
        )
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
