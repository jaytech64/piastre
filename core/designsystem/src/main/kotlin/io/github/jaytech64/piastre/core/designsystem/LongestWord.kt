// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * The width in pixels of the widest word of [text] set in [style] — the least
 * room it can have without a word being cut. A column narrower than this breaks
 * « remplac/er »; one at least this wide only ever wraps between words.
 */
@Composable
fun rememberLongestWord(
    text: String,
    style: TextStyle,
): Int {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, style, density) {
        text
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
    }
}

private val WHITESPACE = Regex("\\s+")
