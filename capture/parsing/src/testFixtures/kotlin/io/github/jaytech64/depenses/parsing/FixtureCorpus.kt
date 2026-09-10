// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.parsing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the captured JSONL corpus into parser input.
 *
 * Published as a test fixture: the corpus is what proves the ledger collapses
 * re-posts the same way the parser does, and that test lives in the module that
 * owns the database, not this one.
 */
object FixtureCorpus {
    fun load(name: String): List<RawNotification> {
        val stream =
            requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) {
                "fixture $name not found"
            }
        return stream
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val json = Json.parseToJsonElement(line).jsonObject

                fun str(key: String) = json[key]?.jsonPrimitive?.takeUnless { it.content == "null" }?.content
                RawNotification(
                    sourcePackage = str("packageName")!!,
                    notificationKey = str("notificationKey")!!,
                    postedAtUtcMillis = str("postedAtUtcMillis")!!.toLong(),
                    deviceLocale = str("deviceLocale") ?: "",
                    title = str("title"),
                    text = str("text"),
                    bigText = str("bigText"),
                    subText = str("subText"),
                    isGroupSummary = str("isGroupSummary")?.toBoolean() ?: false,
                )
            }
    }
}
