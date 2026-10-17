// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jaytech64.piastre.core.data.CategoryGroupRepository
import io.github.jaytech64.piastre.core.data.CategoryRepository
import io.github.jaytech64.piastre.core.data.CsvLabels
import io.github.jaytech64.piastre.core.data.DataErasure
import io.github.jaytech64.piastre.core.data.ExportFormat
import io.github.jaytech64.piastre.core.data.ExportResult
import io.github.jaytech64.piastre.core.data.ExportWriter
import io.github.jaytech64.piastre.core.data.ImportOutcome
import io.github.jaytech64.piastre.core.data.ImportProblem
import io.github.jaytech64.piastre.core.data.ImportReader
import io.github.jaytech64.piastre.core.data.ImportReading
import io.github.jaytech64.piastre.core.data.PremiumRepository
import io.github.jaytech64.piastre.core.data.PremiumSource
import io.github.jaytech64.piastre.core.data.RawEventRepository
import io.github.jaytech64.piastre.core.data.RecurringRepository
import io.github.jaytech64.piastre.core.data.SettingsRepository
import io.github.jaytech64.piastre.core.data.TemplateRepository
import io.github.jaytech64.piastre.core.model.Premium
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val captureEnabled: Boolean = true,
    val rawEventCount: Int = 0,
    /** Recurring transactions with a day still to come. */
    val recurringCount: Int = 0,
    val templateCount: Int = 0,
    val categoryCount: Int = 0,
    val groupCount: Int = 0,
    val loading: Boolean = true,
)

/** What the last destructive or exporting action did, until another one happens. */
sealed interface LastAction {
    data class Exported(
        val transactions: Int,
    ) : LastAction

    data class ExportFailed(
        val reason: String?,
    ) : LastAction

    data class Imported(
        val transactions: Int,
        val alreadyHere: Int,
    ) : LastAction

    data class ImportRefused(
        val problem: ImportProblem,
    ) : LastAction

    data object RawLogCleared : LastAction

    data object Erased : LastAction
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsRepository,
        private val exports: ExportWriter,
        private val imports: ImportReader,
        private val erasure: DataErasure,
        rawEvents: RawEventRepository,
        recurring: RecurringRepository,
        templates: TemplateRepository,
        categories: CategoryRepository,
        categoryGroups: CategoryGroupRepository,
        premiumSource: PremiumSource,
        premiumRepository: PremiumRepository,
    ) : ViewModel() {
        /** Where the Support row leads, or null in a build that must not ask. */
        val donationPage: String? = premiumSource.donationPage

        /** Which build a suggestion comes from, by its store's name. */
        val storeName: String = premiumRepository.storeName

        /** The Premium order a suggestion quotes to claim its priority, or null for none. */
        val orderNumber: StateFlow<String?> =
            premiumRepository.orderNumber.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                null,
            )

        /** Null until the store has answered once; the F-Droid build answers Unlocked, and shows no Premium row. */
        val premium: StateFlow<Premium?> =
            premiumRepository.premium.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

        val uiState: StateFlow<SettingsUiState> =
            combine(
                settings.captureEnabled,
                rawEvents.count(),
                recurring.activeCount(),
                categories.all(),
                categoryGroups.all(),
            ) { enabled, count, active, categoryList, groupList ->
                SettingsUiState(
                    captureEnabled = enabled,
                    rawEventCount = count,
                    recurringCount = active,
                    categoryCount = categoryList.size,
                    groupCount = groupList.size,
                    loading = false,
                )
            }.combine(templates.count()) { state, templateCount -> state.copy(templateCount = templateCount) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = SettingsUiState(),
                )

        private val lastActionState = MutableStateFlow<LastAction?>(null)

        /**
         * Kept on screen rather than flashed as a snackbar: "did that export
         * actually write anything" is a question you ask after looking away, and
         * an answer that has already faded is no answer.
         */
        val lastAction: StateFlow<LastAction?> = lastActionState.asStateFlow()

        private val pendingImportState = MutableStateFlow<ImportReading.Ready?>(null)

        /** A file read and summarised, waiting for you to confirm it; null when there is none. */
        val pendingImport: StateFlow<ImportReading.Ready?> = pendingImportState.asStateFlow()

        fun setCaptureEnabled(enabled: Boolean) {
            viewModelScope.launch { settings.setCaptureEnabled(enabled) }
        }

        fun suggestedFileName(format: ExportFormat): String = exports.suggestedFileName(format)

        fun export(
            uri: Uri,
            format: ExportFormat,
            labels: CsvLabels,
        ) {
            viewModelScope.launch {
                lastActionState.value =
                    when (val result = exports.writeTo(uri, format, labels)) {
                        is ExportResult.Written -> LastAction.Exported(result.transactions)
                        is ExportResult.Failed -> LastAction.ExportFailed(result.reason)
                    }
            }
        }

        /** Reads and summarises the file, and writes nothing until [confirmImport]. */
        fun readImport(uri: Uri) {
            viewModelScope.launch {
                when (val reading = imports.read(uri)) {
                    is ImportReading.Ready -> pendingImportState.value = reading
                    is ImportReading.Refused -> lastActionState.value = LastAction.ImportRefused(reading.problem)
                }
            }
        }

        fun confirmImport() {
            val reading = pendingImportState.value ?: return
            pendingImportState.value = null
            viewModelScope.launch {
                lastActionState.value =
                    when (val outcome = imports.apply(reading)) {
                        is ImportOutcome.Imported -> {
                            LastAction.Imported(outcome.summary.newTransactions, outcome.summary.alreadyHere)
                        }

                        is ImportOutcome.Refused -> {
                            LastAction.ImportRefused(outcome.problem)
                        }
                    }
            }
        }

        fun cancelImport() {
            pendingImportState.value = null
        }

        fun clearRawLog() {
            viewModelScope.launch {
                erasure.clearRawLog()
                lastActionState.value = LastAction.RawLogCleared
            }
        }

        fun eraseEverything() {
            viewModelScope.launch {
                erasure.eraseEverything()
                lastActionState.value = LastAction.Erased
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
