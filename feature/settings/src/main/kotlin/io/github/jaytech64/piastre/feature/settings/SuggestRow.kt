// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * A suggestion, written as an issue on the project's GitHub page in the browser:
 * the app itself sends nothing, and keeps no address to answer to. The form opens
 * with the app's version and store filled in, in plain sight and editable before
 * it is submitted, and nothing in it identifies the phone.
 * Without a browser, the row gives the address instead.
 */
@Composable
internal fun SuggestRow(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val page = suggestionPage(build = "Piastre ${context.versionName()} · ${viewModel.storeName}")
    val intent = Intent(Intent.ACTION_VIEW, page)
    if (intent.resolveActivity(context.packageManager) != null) {
        SettingRow(
            title = stringResource(R.string.settings_suggest),
            body = stringResource(R.string.settings_suggest_body),
            onClick = { context.startActivity(intent) },
        )
    } else {
        SettingRow(
            title = stringResource(R.string.settings_suggest),
            body = stringResource(R.string.settings_suggest_no_browser, SUGGESTION_PAGE),
        )
    }
}

/**
 * The new-issue page on the feature request form, with its `build` field filled
 * in: GitHub fills an issue form's field from the query parameter named for its id
 * in `.github/ISSUE_TEMPLATE/feature_request.yml`, so the two names change together.
 */
internal fun suggestionPage(build: String): Uri =
    Uri
        .parse(SUGGESTION_PAGE)
        .buildUpon()
        .appendQueryParameter("template", "feature_request.yml")
        .appendQueryParameter("build", build)
        .build()

private fun Context.versionName(): String = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

private const val SUGGESTION_PAGE = "https://github.com/jaytech64/piastre/issues/new"
