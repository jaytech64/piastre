// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * A suggestion, written in the mail app and sent from there: the app itself
 * sends nothing. The message opens on bracketed text to replace with the idea,
 * then a block to keep: [SUGGESTION_TAG], the app's version and build, and for someone
 * who owns Premium on Play the order number that claims its priority. All of it
 * is in plain sight before sending, and none of it identifies the phone.
 * Without a mail app, the row gives the address instead.
 */
@Composable
internal fun SuggestRow(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val orderNumber by viewModel.orderNumber.collectAsStateWithLifecycle()
    val body =
        suggestionBody(
            placeholder = stringResource(R.string.suggest_placeholder),
            keepBlock = stringResource(R.string.suggest_keep_block),
            build = stringResource(R.string.suggest_build, SUGGESTION_TAG, context.versionName()),
            store = viewModel.storeName,
            order = orderNumber?.let { stringResource(R.string.suggest_order) to it },
        )
    val subject = stringResource(R.string.suggest_subject, SUGGESTION_TAG)
    val mail = suggestionMail(subject, body)
    if (mail.resolveActivity(context.packageManager) != null) {
        SettingRow(
            title = stringResource(R.string.settings_suggest),
            body = stringResource(R.string.settings_suggest_body),
            onClick = { context.startActivity(mail) },
        )
    } else {
        SettingRow(
            title = stringResource(R.string.settings_suggest),
            body = stringResource(R.string.settings_suggest_no_mail, SUGGESTION_ADDRESS),
        )
    }
}

/**
 * The message twice over, because mail apps disagree on where they read it: some
 * take the fields from the `mailto:` address itself, others only from the extras.
 * Gmail reads either and does not double the text.
 */
private fun suggestionMail(
    subject: String,
    body: String,
): Intent {
    val address = "mailto:$SUGGESTION_ADDRESS?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
    return Intent(Intent.ACTION_SENDTO, Uri.parse(address))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(SUGGESTION_ADDRESS))
        .putExtra(Intent.EXTRA_SUBJECT, subject)
        .putExtra(Intent.EXTRA_TEXT, body)
}

private fun Context.versionName(): String = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

/** An alias that forwards to the maintainer's mail, and can be retired for a new one in a later release. */
private const val SUGGESTION_ADDRESS = "suggestions@piastre.aleeas.com"

/**
 * In the subject and the kept block, never translated, for the maintainer's mail
 * filter to require: a message without it did not come from this row. The
 * number moves to PSG-2 if spam ever learns it.
 */
private const val SUGGESTION_TAG = "PSG-1"

/**
 * The message, laid out for a phone's width: the idea first, then a fenced
 * block of `##` lines that a reader keeps and a mail filter checks. The block is
 * marked on the left only: a mail app draws its own font, so spaces padded to
 * close a right edge line up in one app and go jagged in the next. Its text
 * lines stay under thirty characters so nothing wraps at the usual size; [RULE]
 * is longer only because a mail app draws a dash narrow.
 */
internal fun suggestionBody(
    placeholder: String,
    keepBlock: String,
    build: String,
    store: String,
    order: Pair<String, String>?,
): String {
    val fence = listOf(RULE, RULE)
    val about = listOf(build, store) + order?.toList().orEmpty()
    val block = fence + keepBlock.lines().map(::marked) + fence + about.map(::marked) + fence
    return (listOf(placeholder, "", "") + block).joinToString("\n")
}

private fun marked(line: String) = "##  $line"

private const val RULE = "##--------------------------------------------"
