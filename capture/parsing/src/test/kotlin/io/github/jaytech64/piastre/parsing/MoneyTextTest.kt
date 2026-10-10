// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.parsing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyTextTest {
    @Test
    fun `reads en-CA amounts`() {
        assertEquals(745, MoneyText.parseAmountMinor("7.45"))
        assertEquals(123456, MoneyText.parseAmountMinor("1,234.56"))
        assertEquals(100000000, MoneyText.parseAmountMinor("1,000,000.00"))
    }

    @Test
    fun `reads fr-CA amounts whatever space ICU chose`() {
        // The grouping separator is the detail that silently breaks a naive regex:
        // none of these are U+0020, and all of them occur in the wild.
        assertEquals(123456, MoneyText.parseAmountMinor("1\u202F234,56")) // narrow no-break
        assertEquals(123456, MoneyText.parseAmountMinor("1\u00A0234,56")) // no-break
        assertEquals(123456, MoneyText.parseAmountMinor("1\u2009234,56")) // thin
        assertEquals(123456, MoneyText.parseAmountMinor("1\u2007234,56")) // figure
        assertEquals(123456, MoneyText.parseAmountMinor("1 234,56")) // ordinary
        assertEquals(123456, MoneyText.parseAmountMinor("1234,56")) // none at all
    }

    @Test
    fun `keeps the cents that a naive toDouble would drop`() {
        // The specific regression this class exists to prevent.
        assertEquals(123456, MoneyText.parseAmountMinor("1\u202F234,56"))
        assertEquals(3819, MoneyText.parseAmountMinor("38,19"))
    }

    @Test
    fun `accepts a whole amount with no cents`() {
        assertEquals(2000, MoneyText.parseAmountMinor("20"))
    }

    @Test
    fun `refuses to guess at shapes that are not money`() {
        assertNull(MoneyText.parseAmountMinor(""))
        assertNull(MoneyText.parseAmountMinor("abc"))
        assertNull(MoneyText.parseAmountMinor("12.345")) // three decimals
        assertNull(MoneyText.parseAmountMinor("12.3")) // one decimal
        assertNull(MoneyText.parseAmountMinor(",56"))
    }

    @Test
    fun `normalises every space variant`() {
        assertEquals("a b", MoneyText.normaliseSpaces("a\u202Fb"))
        assertEquals("a b", MoneyText.normaliseSpaces("a\u00A0b"))
    }
}
