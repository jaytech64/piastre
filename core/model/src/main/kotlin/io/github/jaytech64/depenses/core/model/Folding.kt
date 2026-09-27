// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import java.text.Normalizer

/** The string with its accents removed: `MARCHÉ` becomes `MARCHE`, and nothing else changes. */
internal fun String.foldedForMatching(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

private val COMBINING_MARKS = Regex("\\p{Mn}+")

/**
 * Whether [typed] appears in this name, case and accents aside, so `cafe` finds
 * Café Myriade and `ÉPICERIE` finds Épicerie. Blank [typed] finds everything.
 */
fun String.containsTyped(typed: String): Boolean =
    foldedForMatching().contains(typed.trim().foldedForMatching(), ignoreCase = true)
