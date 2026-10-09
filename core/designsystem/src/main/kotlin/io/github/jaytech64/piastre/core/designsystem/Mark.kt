// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * What stands in the circle a row starts with.
 *
 * It only ever repeats what the row's title says in words, so nothing here is
 * read out: a mark is drawn with no description of its own.
 */
sealed interface Mark {
    /** One thing, which fills the circle or half of it. */
    sealed interface Single : Mark

    data class Icon(
        @DrawableRes val drawable: Int,
    ) : Single

    data class Letters(
        val letters: String,
    ) : Single

    /**
     * Two things at once, the circle cut on its diagonal: [first] in the upper
     * left and [second] in the lower right. A half with nothing to show is null.
     */
    data class Halves(
        val first: Single?,
        val second: Single?,
    ) : Mark

    companion object {
        /** What is divided among more than a circle can show. */
        val Split: Single = Icon(R.drawable.category_icon_call_split)
    }
}

/** A category as a circle shows it: the icon you picked, or its initials until you pick one. */
fun categoryMark(
    name: String,
    icon: String?,
): Mark.Single = categoryIconRes(icon)?.let(Mark::Icon) ?: Mark.Letters(name.initials())

/** A category in half a circle, where only one letter fits. */
fun categoryHalfMark(
    name: String,
    icon: String?,
): Mark.Single = categoryIconRes(icon)?.let(Mark::Icon) ?: Mark.Letters(name.initials().take(1))

/** The first letter of each of the first two words, or a question mark for no words at all. */
fun String?.initials(): String =
    orEmpty()
        .split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }

@Composable
fun MarkCircle(
    mark: Mark,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.size(MARK_SIZE),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (mark) {
                is Mark.Icon -> {
                    Icon(
                        painterResource(mark.drawable),
                        contentDescription = null,
                        Modifier.size(WHOLE_ICON),
                    )
                }

                is Mark.Letters -> {
                    Text(mark.letters, style = MaterialTheme.typography.labelMedium)
                }

                is Mark.Halves -> {
                    Halves(mark)
                }
            }
        }
    }
}

/**
 * Each half is clipped to its side of the diagonal, so an icon that reaches
 * past it is cut there instead of running under the other one.
 */
@Composable
private fun Halves(mark: Mark.Halves) {
    Box(Modifier.fillMaxSize().clip(UPPER_LEFT)) {
        mark.first?.let { Half(it, Modifier.align(Alignment.TopStart)) }
    }
    Box(Modifier.fillMaxSize().clip(LOWER_RIGHT)) {
        mark.second?.let { Half(it, Modifier.align(Alignment.BottomEnd)) }
    }
    val cut = MaterialTheme.colorScheme.surface
    Canvas(Modifier.fillMaxSize()) {
        drawLine(cut, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = CUT_WIDTH.toPx())
    }
}

@Composable
private fun Half(
    mark: Mark.Single,
    modifier: Modifier,
) {
    Box(modifier.padding(HALF_INSET).size(HALF_ICON), contentAlignment = Alignment.Center) {
        when (mark) {
            is Mark.Icon -> {
                Icon(painterResource(mark.drawable), contentDescription = null, Modifier.fillMaxSize())
            }

            is Mark.Letters -> {
                // Sized in dp: the circle does not grow with the font, and half of
                // it has no room for a letter that does.
                val size = with(LocalDensity.current) { HALF_LETTER.toSp() }
                Text(
                    mark.letters,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = size, lineHeight = size),
                )
            }
        }
    }
}

/** The circle a row starts with. */
val MARK_SIZE = 36.dp

private val WHOLE_ICON = 20.dp
private val HALF_ICON = 18.dp
private val HALF_INSET = 3.dp
private val HALF_LETTER = 12.dp
private val CUT_WIDTH = 1.5.dp

private val UPPER_LEFT =
    GenericShape { size, _ ->
        lineTo(size.width, 0f)
        lineTo(0f, size.height)
        close()
    }

private val LOWER_RIGHT =
    GenericShape { size, _ ->
        moveTo(size.width, 0f)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
