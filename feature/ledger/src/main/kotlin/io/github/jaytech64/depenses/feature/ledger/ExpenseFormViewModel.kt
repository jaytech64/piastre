// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.feature.ledger

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.depenses.core.data.AccountRepository
import io.github.jaytech64.depenses.core.data.CategoryGroupRepository
import io.github.jaytech64.depenses.core.data.CategoryRepository
import io.github.jaytech64.depenses.core.data.MerchantRepository
import io.github.jaytech64.depenses.core.data.RecurringRepository
import io.github.jaytech64.depenses.core.data.TemplateRepository
import io.github.jaytech64.depenses.core.data.TransactionEditor
import io.github.jaytech64.depenses.core.data.TransactionRepository
import io.github.jaytech64.depenses.core.designsystem.Formatters
import io.github.jaytech64.depenses.core.model.Account
import io.github.jaytech64.depenses.core.model.AccountType
import io.github.jaytech64.depenses.core.model.Merchant
import io.github.jaytech64.depenses.core.model.MerchantNormaliser
import io.github.jaytech64.depenses.core.model.Money
import io.github.jaytech64.depenses.core.model.Recurrence
import io.github.jaytech64.depenses.core.model.RecurringTransaction
import io.github.jaytech64.depenses.core.model.Rhythm
import io.github.jaytech64.depenses.core.model.Transaction
import io.github.jaytech64.depenses.core.model.TransactionKind
import io.github.jaytech64.depenses.core.model.TransactionSource
import io.github.jaytech64.depenses.core.model.TransactionTemplate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Provider

/** The navigation argument naming the transaction to edit. Absent when adding one. */
const val EXPENSE_ID_ARG = "expenseId"

/** The navigation argument naming the recurring transaction to edit, in place of [EXPENSE_ID_ARG]. */
const val RECURRING_ID_ARG = "recurringId"

/** The navigation argument naming the template to edit, in place of [EXPENSE_ID_ARG]. */
const val TEMPLATE_ID_ARG = "templateId"

/** [TEMPLATE_ID_ARG] for a template not saved yet. No stored template has it: their ids start at 1. */
const val NEW_TEMPLATE_ID = 0L

/** Which end of a transfer a choice is about. Anything else has only a source. */
enum class AccountSlot { SOURCE, DESTINATION }

/**
 * What has been typed so far. [amountText] is kept as text rather than as a
 * [Money] because half-typed input is not an amount yet, and a field that
 * silently rounds what you are still writing is worse than one that waits.
 */
data class ExpenseFormUiState(
    val amountText: String = "",
    val merchantText: String = "",
    val date: LocalDate,
    val time: LocalTime,
    val note: String = "",
    val kind: TransactionKind = TransactionKind.EXPENSE,
    /**
     * The account the money came from, went into for income, or left on a transfer.
     * Null when nothing was said. Kept across a change of kind: every kind has one.
     */
    val accountId: Long? = null,
    /**
     * True once the account is yours: picked from the list, or whatever the
     * transaction or schedule being edited already had. Until then, naming a
     * merchant fills in the account you last used there.
     */
    val accountChosen: Boolean = false,
    /** Where a transfer lands. Null on anything else. */
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    /**
     * True once the category is yours: picked from the list, or the one the
     * transaction being edited already had. Until then, naming a merchant fills
     * the category in with the one that merchant gives.
     */
    val categoryChosen: Boolean = false,
    /** Whether the offer's first box is ticked: the place's other transactions with no category. */
    val reachOthers: Boolean = false,
    /**
     * Whether the offer's second box is ticked, or null while you have not touched
     * it — it then shows whether the place already gives the chosen category.
     */
    val fromNowOnChoice: Boolean? = null,
    /** A transaction a notification recorded: its account is the bank's to say, so it is shown, not offered. */
    val isCaptured: Boolean = false,
    /**
     * How often the transaction comes back, or null when it does not. Offered only
     * on a transaction typed by hand that is not already a schedule's: see
     * [canRepeat].
     */
    val rhythm: Rhythm? = null,
    /** The last day it may come back on, or null when it goes on. */
    val lastDate: LocalDate? = null,
    /**
     * False on a captured transaction, which Wallet will capture again next time,
     * and on one a schedule already covers, which would then be written twice.
     */
    val canRepeat: Boolean = true,
    /** The rows a repeating save would write for days already past, while that is being asked. */
    val backfill: Backfill? = null,
    /** The recurring transaction being edited, as it was on disk, or null when the form is about a transaction. */
    val schedule: ScheduleAsStored? = null,
    val capturedAccountLabel: String? = null,
    /** True when the form is about a template rather than a transaction: it then has a name, and no day. */
    val isTemplate: Boolean = false,
    /** A template's name; on a transaction, the name of the template saving it will also make. */
    val templateName: String = "",
    /** Whether the *Save as template* box is ticked. */
    val savesAsTemplate: Boolean = false,
    /** The template the form was last filled from, so saving can note it was used. */
    val appliedTemplateId: Long? = null,
    val isEditing: Boolean = false,
    /** True while the transaction being edited is read off disk, so the form never shows "now" in its place. */
    val loading: Boolean = false,
    val saving: Boolean = false,
    /** Flips once, when the change is on disk. The screen leaves on it. */
    val done: Boolean = false,
) {
    val amount: Money? get() = Formatters.parseMoney(amountText)

    val canSave: Boolean get() = !saving && !loading && if (isTemplate) templateIsWhole else transactionIsWhole

    /**
     * A transaction needs an amount; a transfer also needs two accounts, and two
     * different ones; one that repeats cannot stop before it starts; and one also
     * saved as a template needs that template's name.
     */
    private val transactionIsWhole: Boolean
        get() =
            amount != null && transferIsSettled && !endsBeforeItStarts &&
                (!savesAsTemplate || templateName.isNotBlank())

    /**
     * A template needs only a name. Its amount can be left out, but not half
     * typed, and what it does say about a transfer cannot be the typo a
     * transaction refuses.
     */
    private val templateIsWhole: Boolean
        get() =
            templateName.isNotBlank() &&
                (amountText.isBlank() || amount != null) &&
                !(kind == TransactionKind.TRANSFER && accountId != null && accountId == toAccountId)

    /** An end date set before the transaction's own day. */
    val endsBeforeItStarts: Boolean get() = rhythm != null && lastDate != null && lastDate < date

    val isSchedule: Boolean get() = schedule != null

    /** A template starts a transaction: one already written is corrected field by field. */
    val offersTemplates: Boolean get() = !isEditing && !isTemplate

    /** Any transaction can be kept as a template, captured or not. A schedule or a template is not a transaction. */
    val offersSavingAsTemplate: Boolean get() = !isSchedule && !isTemplate

    /**
     * The day the rhythm counts from. A schedule left on its next day with the same
     * rhythm goes on counting from its first day, so a bill on the 31st that next
     * falls on the 30th is still worded, and still comes back, as the 31st. Moved,
     * or with a new rhythm, it counts from [date]: see `RecurringRepository.change`.
     */
    val countsFrom: LocalDate
        get() = schedule?.takeIf { it.nextDue == date && it.recurrence.rhythm == rhythm }?.recurrence?.firstDate ?: date

    /** When it comes back, or null when it does not. */
    val recurrence: Recurrence?
        get() = rhythm?.takeUnless { endsBeforeItStarts }?.let { Recurrence(it, countsFrom, lastDate) }

    /** Only a transfer has a destination; switching away from one drops it. */
    val destinationToSave: Long? get() = toAccountId.takeIf { kind == TransactionKind.TRANSFER }

    /**
     * A transfer into the account it came from is not a transaction, it is a typo,
     * so Save stays out of reach until the two accounts are both set and differ.
     */
    private val transferIsSettled: Boolean
        get() =
            kind != TransactionKind.TRANSFER ||
                (accountId != null && toAccountId != null && accountId != toAccountId)
}

/**
 * One form for adding a transaction and for correcting one, because the two ask
 * the same questions — and a second form would drift from the first the day one
 * of them learns something. A recurring transaction and a template open in it
 * too, for the same reason.
 */
@HiltViewModel
class ExpenseFormViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val transactions: TransactionRepository,
        private val editor: TransactionEditor,
        merchants: MerchantRepository,
        private val accounts: AccountRepository,
        private val categories: CategoryRepository,
        private val categoryGroups: CategoryGroupRepository,
        private val recurring: RecurringRepository,
        private val templates: TemplateRepository,
        private val clock: Clock,
        private val zoneId: Provider<ZoneId>,
    ) : ViewModel() {
        private val editingId: Long? = savedStateHandle.get<Long>(EXPENSE_ID_ARG)

        private val scheduleId: Long? = savedStateHandle.get<Long>(RECURRING_ID_ARG)

        private val templateId: Long? = savedStateHandle.get<Long>(TEMPLATE_ID_ARG)

        private val storedTemplateId: Long? = templateId?.takeIf { it != NEW_TEMPLATE_ID }

        private val isStored = editingId != null || scheduleId != null || storedTemplateId != null

        private val writer = FormWriter(transactions, editor, recurring, templates, zoneId::get)

        /** The transaction as it was on disk before this edit, or null when adding. */
        private var original: Transaction? = null

        private val _uiState =
            MutableStateFlow(
                clock.withZone(zoneId.get()).let { local ->
                    ExpenseFormUiState(
                        date = LocalDate.now(local),
                        time = LocalTime.now(local),
                        isEditing = isStored,
                        loading = isStored,
                        isTemplate = templateId != null,
                        canRepeat = templateId == null,
                    )
                },
            )
        val uiState: StateFlow<ExpenseFormUiState> = _uiState.asStateFlow()

        /**
         * Every merchant already known, so the field can offer the name you used
         * last time instead of letting you invent a second spelling of it — the ones
         * you use most for the kind being entered first, so an income leads with who
         * pays you and an expense with where you spend.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val knownMerchants: StateFlow<List<Merchant>> =
            _uiState
                .map { it.kind }
                .distinctUntilChanged()
                .flatMapLatest { kind -> merchants.byUse(kind, LocalDate.now(zoneId.get())) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        val knownAccounts: StateFlow<List<Account>> =
            accounts
                .all(LocalDate.now(clock.withZone(zoneId.get())))
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        /**
         * The account last used at each merchant for the kind being entered, held
         * with that kind so a list still loading for the previous kind is never
         * read as this one's.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val lastAccounts: StateFlow<LastAccounts?> =
            _uiState
                .map { it.kind }
                .distinctUntilChanged()
                .flatMapLatest { kind ->
                    transactions
                        .lastAccountAtEachMerchant(kind, LocalDate.now(clock.withZone(zoneId.get())))
                        .map { LastAccounts(kind, it) }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** How often it comes back, and until when. */
        val repeat = RepeatChoice(_uiState)

        /** The category, the list it is picked from, and the offer beside it. */
        val category =
            CategoryPicker(
                form = _uiState,
                merchants = knownMerchants,
                accounts = knownAccounts,
                categories = categories,
                categoryGroups = categoryGroups,
                editingId = editingId,
                today = { LocalDate.now(zoneId.get()) },
                scope = viewModelScope,
                sharing = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            )

        /** The templates the form can be filled from, and a template's own name. */
        val template =
            TemplateChoice(
                form = _uiState,
                templates = templates,
                following = { category.following(it).withLastAccount(lastAccounts.value) },
                suggestedName = { it.suggestedTemplateName(category.known.value, knownAccounts.value) },
                scope = viewModelScope,
                sharing = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            )

        // A transaction that is no longer there has nothing to edit, so the screen
        // simply closes rather than offering to save into a row that is gone.
        init {
            scheduleId?.let { id ->
                viewModelScope.launch {
                    val schedule = recurring.byId(id)
                    _uiState.update { state ->
                        schedule?.let { state.filledFrom(it, today()) } ?: state.copy(loading = false, done = true)
                    }
                }
            }
            storedTemplateId?.let { id ->
                viewModelScope.launch {
                    val template = templates.byId(id)
                    _uiState.update { state ->
                        template?.let { state.filledFrom(it) } ?: state.copy(loading = false, done = true)
                    }
                }
            }
            editingId?.let { id ->
                viewModelScope.launch {
                    val transaction = transactions.byId(id)
                    original = transaction
                    _uiState.update { state ->
                        transaction?.let { state.filledFrom(it) } ?: state.copy(loading = false, done = true)
                    }
                }
            }
            viewModelScope.launch { lastAccounts.collect { last -> _uiState.update { it.withLastAccount(last) } } }
        }

        fun onAmountChange(text: String) = _uiState.update { it.copy(amountText = text) }

        fun onMerchantChange(text: String) =
            _uiState.update {
                category.following(it.copy(merchantText = text).withLastAccount(lastAccounts.value))
            }

        fun onNoteChange(text: String) = _uiState.update { it.copy(note = text) }

        fun onDateChange(date: LocalDate) = _uiState.update { it.copy(date = date) }

        fun onTimeChange(time: LocalTime) = _uiState.update { it.copy(time = time) }

        fun onKindChange(kind: TransactionKind) =
            _uiState.update {
                category.following(it.copy(kind = kind).withLastAccount(lastAccounts.value))
            }

        fun onAccountChange(
            slot: AccountSlot,
            id: Long?,
        ) = when (slot) {
            AccountSlot.SOURCE -> _uiState.update { it.copy(accountId = id, accountChosen = true) }
            AccountSlot.DESTINATION -> _uiState.update { category.following(it.copy(toAccountId = id)) }
        }

        /** Adds the account missing from the list and picks it, so adding one never means leaving the form. */
        fun addAccount(
            name: String,
            type: AccountType,
            last4: String,
            openingBalance: Money?,
            slot: AccountSlot,
        ) {
            viewModelScope.launch {
                val id = accounts.add(name, type, last4, openingBalance)
                onAccountChange(slot, id)
            }
        }

        /**
         * Reports completion through [ExpenseFormUiState.done] rather than a
         * callback, so the screen navigates away on a fact that is already on disk
         * instead of on a promise made from inside a coroutine it does not own.
         *
         * A transaction set to repeat from a day already past would write a row for
         * every day since, so the first save asks, with the count, and writes
         * nothing; saving again with [pastDaysAgreed] writes them.
         */
        fun save(pastDaysAgreed: Boolean = false) {
            val state = _uiState.value
            if (!state.canSave || (!pastDaysAgreed && repeat.asksAboutPastDays(today()))) return
            val reach =
                category.offer.value
                    ?.reach
                    .takeUnless { state.isSchedule }
            _uiState.update { it.copy(saving = true, backfill = null) }
            viewModelScope.launch {
                writer.write(state, original, FormTarget(scheduleId, templateId), reach)
                _uiState.update { it.copy(saving = false, done = true) }
            }
        }

        /**
         * Only offered while editing: a transaction not yet saved has nothing to
         * remove. On a recurring transaction it stops it, and what it wrote stays;
         * on a template it removes the template, and what it filled stays.
         */
        fun delete() {
            val editing = original
            if (!isStored) return
            _uiState.update { it.copy(saving = true) }
            viewModelScope.launch {
                writer.remove(editing, FormTarget(scheduleId, storedTemplateId))
                _uiState.update { it.copy(saving = false, done = true) }
            }
        }

        private val today: () -> LocalDate = { LocalDate.now(clock.withZone(zoneId.get())) }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

/** [byMerchant] as [TransactionRepository.lastAccountAtEachMerchant] gives it for [kind]. */
internal data class LastAccounts(
    val kind: TransactionKind,
    val byMerchant: Map<String, Long>,
)

/**
 * The form with the account last used at the merchant it names, while the account
 * is not yours yet — none, when that merchant has no account to go by. A transfer
 * names no merchant, so its account stays as it is, and so does every account
 * while [last] is still loading or was read for another kind.
 */
internal fun ExpenseFormUiState.withLastAccount(last: LastAccounts?): ExpenseFormUiState {
    if (accountChosen || kind == TransactionKind.TRANSFER || last?.kind != kind) return this
    val merchant = MerchantNormaliser.normalise(merchantText)?.normalisedName
    return copy(accountId = merchant?.let(last.byMerchant::get))
}

/** A recurring transaction as the form found it, which is what its rhythm keeps counting from. */
data class ScheduleAsStored(
    val recurrence: Recurrence,
    val nextDue: LocalDate?,
)

/**
 * The form filled from a recurring transaction, dated on the next day it falls
 * on — or, once it has ended, today, from where it can be started again.
 */
private fun ExpenseFormUiState.filledFrom(
    schedule: RecurringTransaction,
    today: LocalDate,
): ExpenseFormUiState =
    copy(
        amountText = Formatters.amountInput(schedule.amount),
        merchantText = schedule.merchantName.orEmpty(),
        date = schedule.nextDue ?: today,
        note = schedule.note.orEmpty(),
        kind = schedule.kind,
        accountId = schedule.accountId,
        accountChosen = true,
        toAccountId = schedule.toAccountId,
        categoryId = schedule.categoryId,
        categoryChosen = true,
        rhythm = schedule.recurrence.rhythm,
        lastDate = schedule.recurrence.lastDate,
        canRepeat = false,
        schedule = ScheduleAsStored(schedule.recurrence, schedule.nextDue),
        loading = false,
    )

/** The form filled from a template: everything it holds, and no day. */
private fun ExpenseFormUiState.filledFrom(template: TransactionTemplate): ExpenseFormUiState =
    copy(
        templateName = template.name,
        amountText = template.amount?.let(Formatters::amountInput).orEmpty(),
        merchantText = template.merchantName.orEmpty(),
        note = template.note.orEmpty(),
        kind = template.kind,
        accountId = template.accountId,
        accountChosen = true,
        toAccountId = template.toAccountId,
        categoryId = template.categoryId,
        categoryChosen = true,
        loading = false,
    )

/**
 * The form filled from a transaction as stored, read in the zone it was made in —
 * the same clock the ledger row shows it under.
 */
private fun ExpenseFormUiState.filledFrom(transaction: Transaction): ExpenseFormUiState {
    val local = transaction.occurredAt.atZone(transaction.zoneId)
    return copy(
        amountText = Formatters.amountInput(transaction.amount),
        merchantText = transaction.displayMerchant.orEmpty(),
        date = local.toLocalDate(),
        time = local.toLocalTime(),
        note = transaction.note.orEmpty(),
        kind = transaction.kind,
        accountId = transaction.accountId,
        accountChosen = true,
        toAccountId = transaction.toAccountId,
        categoryId = transaction.categoryId,
        categoryChosen = transaction.categoryId != null,
        isCaptured = transaction.source == TransactionSource.NOTIFICATION,
        canRepeat = transaction.source == TransactionSource.MANUAL && transaction.recurringId == null,
        capturedAccountLabel = Formatters.accountName(transaction.accountName, transaction.cardLast4),
        loading = false,
    )
}

internal fun ExpenseFormUiState.occurredAt(zone: ZoneId): Instant = date.atTime(time).atZone(zone).toInstant()

/**
 * The typed date and time, read in the zone the [original] transaction was made
 * in.
 *
 * Its offset is preferred when the wall-clock time is ambiguous, so an untouched
 * transaction made in the hour the clocks fall back does not jump an hour on save.
 */
internal fun ExpenseFormUiState.occurredAtLike(original: Transaction): Instant =
    ZonedDateTime
        .ofLocal(date.atTime(time), original.zoneId, original.occurredAt.atZone(original.zoneId).offset)
        .toInstant()
