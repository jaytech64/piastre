// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.model.Money
import java.time.LocalDate

/** What reading a file found: something importable, with what importing it would do, or a refusal. */
sealed interface ImportReading {
    /** The file, checked, and what importing it would do to the ledger as it is now. */
    class Ready internal constructor(
        internal val document: ExportDocument,
        val summary: ImportSummary,
    ) : ImportReading

    data class Refused(
        val problem: ImportProblem,
    ) : ImportReading
}

/** How an import ended. [Imported] carries what was actually written, which can differ from what was read. */
sealed interface ImportOutcome {
    data class Imported(
        val summary: ImportSummary,
    ) : ImportOutcome

    data class Refused(
        val problem: ImportProblem,
    ) : ImportOutcome
}

/** Why a file was refused, in terms the screen can put into words. */
sealed interface ImportProblem {
    /** Not JSON, or JSON that is not this app's export. */
    data object NotAnExport : ImportProblem

    /** An export in a format this version of the app does not read. */
    data class UnsupportedVersion(
        val version: Int,
    ) : ImportProblem

    /**
     * Shaped like an export but contradicting itself — a transaction on an account
     * the file does not list, a kind the app has never stored. [detail] names the
     * spot, for whoever has to open the file to find out why.
     */
    data class Inconsistent(
        val detail: String,
    ) : ImportProblem

    /** Two of your accounts answer to a name the file uses, and picking one would be a guess. */
    data class AmbiguousAccount(
        val name: String,
    ) : ImportProblem
}

/** What an import adds to the ledger, and what it leaves as it is. */
data class ImportSummary(
    /** Transactions the file has and the ledger does not. */
    val newTransactions: Int,
    /** Transactions already in the ledger under the same identity, left untouched. */
    val alreadyHere: Int,
    /** The span of [newTransactions], each on its own local day; null when there are none. */
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
    val accountsFound: Int,
    val accountsCreated: Int,
    /** Opening balances the file gives to accounts that had none. */
    val openingBalancesSet: Int,
    val openingBalancesKept: List<KeptOpeningBalance>,
    val newMerchants: Int,
)

/** An opening balance you had already set, kept over the different one in the file. */
data class KeptOpeningBalance(
    val account: String,
    val inFile: Money,
    val kept: Money,
)
