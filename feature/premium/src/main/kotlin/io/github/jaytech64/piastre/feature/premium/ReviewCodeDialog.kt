// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.premium

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch

/**
 * Takes the review code Google's reviewers are given, and closes once it has
 * turned Premium on. Reached only by long-pressing the Premium page's title,
 * so the page a paying user sees is unchanged.
 *
 * The field asks for a password keyboard, shown in clear, so the keyboard
 * neither suggests the code back nor learns it.
 */
@Composable
internal fun ReviewCodeDialog(
    redeem: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val submit: () -> Unit = {
        scope.launch {
            if (redeem(typed)) onDismiss() else refused = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.premium_review_code_title)) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = {
                    typed = it
                    refused = false
                },
                label = { Text(stringResource(R.string.premium_review_code_field)) },
                isError = refused,
                supportingText =
                    if (refused) {
                        (
                            {
                                Text(
                                    stringResource(R.string.premium_review_code_refused),
                                )
                            }
                        )
                    } else {
                        null
                    },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { if (typed.isNotBlank()) submit() }),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = typed.isNotBlank()) {
                Text(stringResource(R.string.premium_review_code_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.premium_review_code_cancel)) }
        },
    )
}
