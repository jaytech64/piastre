// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class IssuePageTest {
    @Test
    fun `a suggestion opens the feature request form on the project's issues`() {
        val page = issuePage(IssueForm.Suggestion, build = "Piastre 1.4.0 · F-Droid")

        assertEquals("github.com", page.host)
        assertEquals("/jaytech64/piastre/issues/new", page.path)
        assertEquals("feature_request.yml", page.getQueryParameter("template"))
    }

    @Test
    fun `a bug opens the bug report form on the project's issues`() {
        val page = issuePage(IssueForm.Bug, build = "Piastre 1.4.0 · F-Droid")

        assertEquals("/jaytech64/piastre/issues/new", page.path)
        assertEquals("bug_report.yml", page.getQueryParameter("template"))
    }

    @Test
    fun `the form's version field arrives as the app wrote it`() {
        val page = issuePage(IssueForm.Bug, build = "Piastre 1.4.0 · Google Play")

        assertEquals("Piastre 1.4.0 · Google Play", page.getQueryParameter("build"))
    }

    @Test
    fun `every form the app opens has the field it fills`() {
        IssueForm.entries.forEach { form ->
            val template = File("../../.github/ISSUE_TEMPLATE/${form.template}")

            assertTrue(form.template, template.readText().contains("\n    id: build\n"))
        }
    }
}
