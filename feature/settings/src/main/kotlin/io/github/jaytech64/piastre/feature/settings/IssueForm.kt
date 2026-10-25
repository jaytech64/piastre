// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * An issue form on the project's GitHub page, and the Settings row that opens it.
 * [template] names its file in `.github/ISSUE_TEMPLATE/`.
 */
internal enum class IssueForm(
    val template: String,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    @param:StringRes val noBrowser: Int,
) {
    Suggestion(
        template = "feature_request.yml",
        title = R.string.settings_suggest,
        body = R.string.settings_suggest_body,
        noBrowser = R.string.settings_suggest_no_browser,
    ),
    Bug(
        template = "bug_report.yml",
        title = R.string.settings_report_bug,
        body = R.string.settings_report_bug_body,
        noBrowser = R.string.settings_report_bug_no_browser,
    ),
}

/**
 * An issue, written on the project's GitHub page in the browser: the app itself
 * sends nothing, and keeps no address to answer to. The form opens with the app's
 * version and store filled in, in plain sight and editable before it is submitted,
 * and nothing in it identifies the phone.
 * Without a browser, the row gives the address instead.
 */
@Composable
internal fun IssueRow(
    form: IssueForm,
    storeName: String,
) {
    val context = LocalContext.current
    val page = issuePage(form, build = "Piastre ${context.versionName()} · $storeName")
    val intent = Intent(Intent.ACTION_VIEW, page)
    if (intent.resolveActivity(context.packageManager) != null) {
        SettingRow(
            title = stringResource(form.title),
            body = stringResource(form.body),
            onClick = { context.startActivity(intent) },
        )
    } else {
        SettingRow(
            title = stringResource(form.title),
            body = stringResource(form.noBrowser, NEW_ISSUE_PAGE),
        )
    }
}

/**
 * The new-issue page on [form], with its `build` field filled in: GitHub fills an
 * issue form's field from the query parameter named for its id, so every template
 * keeps a field with the id `build`.
 */
internal fun issuePage(
    form: IssueForm,
    build: String,
): Uri =
    Uri
        .parse(NEW_ISSUE_PAGE)
        .buildUpon()
        .appendQueryParameter("template", form.template)
        .appendQueryParameter("build", build)
        .build()

private fun Context.versionName(): String = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

private const val NEW_ISSUE_PAGE = "https://github.com/jaytech64/piastre/issues/new"
