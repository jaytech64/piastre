// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

/** Unwinds a read, or a database transaction, the moment a file turns out not to be importable. */
internal class ImportRefused(
    val problem: ImportProblem,
    cause: Throwable? = null,
) : Exception(problem.toString(), cause)

internal fun refuse(
    problem: ImportProblem,
    cause: Throwable? = null,
): Nothing = throw ImportRefused(problem, cause)
