// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.AccountRepository
import io.github.jaytech64.depenses.core.data.LocalDateTicker
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** One local day, with its balance: what came in minus what went out. */
data class LedgerDay(
    val date: LocalDate,
    val total: Money,
    val transactions: List<Transaction>,
)

/**
 * What the month's balance is made of.
 *
 * Income and expenses are counted by kind, the same rows a report counts, so
 * the whole ledger's expenses and a report's *dépensé* for the month are one
 * number. A transfer is neither: it is worth zero to the whole ledger and moves
 * a filtered account's balance (ADR-0006), so it is a figure of its own rather
 * than being folded into either of the other two, and the three always add up
 * to [balance].
 */
data class MonthSummary(
    val income: Money = Money(0, Money.CAD),
    val expenses: Money = Money(0, Money.CAD),
    /** Zero on the whole ledger; under a filter, signed by which end the account is on. */
    val transfers: Money = Money(0, Money.CAD),
) {
    /** Negative on a month you only spent, which is most months. */
    val balance: Money get() = income + -expenses + transfers
}

data class LedgerUiState(
    val days: List<LedgerDay> = emptyList(),
    /** Null until the clock has been read once; never a sentinel date. */
    val today: LocalDate? = null,
    val thisMonth: MonthSummary = MonthSummary(),
    val needsReview: Int = 0,
    /** Every account, including ones nothing has ever been spent from. */
    val accounts: List<Account> = emptyList(),
    /** The account the screen is narrowed to, null while it shows all of them. */
    val selectedAccount: Account? = null,
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = days.isEmpty() && !loading
}

@HiltViewModel
class LedgerViewModel
    @Inject
    constructor(
        repository: TransactionRepository,
        accounts: AccountRepository,
        ticker: LocalDateTicker,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        /**
         * The filter lives in saved state rather than in a field, because the bottom
         * bar navigates with `popUpTo(saveState = true)`: that clears the view model
         * scoped to the ledger entry, so a field would drop the filter on the way to
         * Reports and back. The saved-state bundle survives that and a rotation,
         * while a cold launch starts without one — which is the lifetime the filter
         * was asked for.
         */
        private val selectedAccountId: StateFlow<Long?> = savedState.getStateFlow(SELECTED_ACCOUNT_ID, null)

        /**
         * The current day is a source of truth in its own right, not a value read
         * once at construction. Without it the heading still says "today" over
         * yesterday's purchases the morning after the screen was first opened.
         */
        val uiState: StateFlow<LedgerUiState> =
            combine(
                repository.confirmed(),
                repository.needsReviewCount(),
                ticker.dates(),
                accounts.all(),
                selectedAccountId,
            ) { transactions, needsReview, today, allAccounts, selectedId ->
                // An account removed in Settings leaves a filter nothing answers for.
                // The screen goes back to showing everything, rather than an empty
                // list under the name of an account that is gone.
                val selected = allAccounts.firstOrNull { it.id == selectedId }
                val days = transactions.on(selected).groupByLocalDay(selected)
                LedgerUiState(
                    days = days,
                    today = today,
                    thisMonth = days.summaryOf(YearMonth.from(today), selected),
                    needsReview = needsReview,
                    accounts = allAccounts,
                    selectedAccount = selected,
                    loading = false,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = LedgerUiState(),
            )

        /** Null shows every account again. */
        fun onAccountSelected(id: Long?) {
            savedState[SELECTED_ACCOUNT_ID] = id
        }

        /**
         * Both ends of a transfer match: money that reached an account is money on
         * that account, and a screen that showed only the leaving end would lose
         * half of every transfer.
         */
        private fun List<Transaction>.on(account: Account?): List<Transaction> =
            if (account == null) this else filter { it.accountId == account.id || it.toAccountId == account.id }

        /**
         * Grouped in Kotlin rather than SQL because the rows are already sorted and
         * already in memory — this is the one screen that wants every transaction,
         * so a second query would re-read what the first one just returned. The
         * filter rides along for the same reason.
         */
        private fun List<Transaction>.groupByLocalDay(account: Account?): List<LedgerDay> =
            groupBy { it.localDate }
                .map { (date, forDay) ->
                    LedgerDay(
                        date = date,
                        total = forDay.map { it.balanceMovedOn(account) }.reduce(Money::plus),
                        transactions = forDay,
                    )
                }.sortedByDescending { it.date }

        /**
         * Every day of the calendar month counts, including one dated ahead of
         * today: a purchase the list shows is a purchase the headline above it has
         * counted.
         *
         * An empty figure is zero in the month's own currency, so a month whose
         * rows are all in one currency never has a Canadian zero added to it.
         */
        private fun List<LedgerDay>.summaryOf(
            month: YearMonth,
            account: Account?,
        ): MonthSummary {
            val rows = filter { YearMonth.from(it.date) == month }.flatMap { it.transactions }
            val zero = Money(0, rows.firstOrNull()?.amount?.currency ?: Money.CAD)

            fun sumOf(
                kind: TransactionKind,
                amount: (Transaction) -> Money,
            ): Money = rows.filter { it.kind == kind }.map(amount).fold(zero, Money::plus)

            return MonthSummary(
                income = sumOf(TransactionKind.INCOME) { it.amount },
                expenses = sumOf(TransactionKind.EXPENSE) { it.amount },
                transfers = sumOf(TransactionKind.TRANSFER) { it.balanceMovedOn(account) },
            )
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val SELECTED_ACCOUNT_ID = "selectedAccountId"
        }
    }

/**
 * What the transaction moves, read from where [account] stands.
 *
 * With no account it is [Transaction.signedAmount], where a transfer is worth
 * zero: it moves your own money between your own accounts, so the ledger as a
 * whole is unchanged. One account is not the whole ledger, though — money that
 * left for another account of yours has still left this one — so under a filter
 * a transfer is signed by which end the account is on. That is the arithmetic
 * the account's own balance is already built from in `AccountDao.allWithCounts`,
 * and that balance is what the filter menu offered to pick from; the two would
 * otherwise disagree about the same transfer, one tap apart.
 */
private fun Transaction.balanceMovedOn(account: Account?): Money =
    when {
        account == null || kind != TransactionKind.TRANSFER -> signedAmount
        accountId == account.id -> -amount
        else -> amount
    }
