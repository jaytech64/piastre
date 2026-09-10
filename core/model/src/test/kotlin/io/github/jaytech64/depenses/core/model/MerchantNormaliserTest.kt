// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every string in the first block is one a real Google Wallet notification
 * actually carried, so this suite is exhaustive over the evidence rather than
 * over cases that seemed plausible.
 */
class MerchantNormaliserTest {
    @Test
    fun `a branch number after a double space is a branch, not a name`() {
        assertEquals("MARCHE ST JOSEPH", normalised("MARCHE ST JOSEPH  1234"))
        assertEquals("EPICERIE NORD", normalised("EPICERIE NORD  0042"))
    }

    @Test
    fun `two branches of one chain are one merchant`() {
        assertEquals(normalised("MARCHE ST JOSEPH  1234"), normalised("MARCHE ST JOSEPH  3011"))
    }

    @Test
    fun `a hash or a hyphen introduces a branch number too`() {
        assertEquals("RESTO L'ENTREPOT", normalised("RESTO L\u2019ENTREPOT #10593"))
        assertEquals("QUINCAILLERIE", normalised("QUINCAILLERIE # 17"))
        assertEquals("SUSHI EXPRESS", normalised("SUSHI EXPRESS - 1187"))
    }

    @Test
    fun `a corporate suffix is not part of the name`() {
        assertEquals("BELLEVUE PHARMA", normalised("BELLEVUE PHARMA INC"))
    }

    @Test
    fun `a name with nothing to strip is left exactly as it is`() {
        assertEquals("PHARMACIE DU COIN NDG", normalised("PHARMACIE DU COIN NDG"))
        assertEquals("BOULANGERIE VIEUX MOULIN", normalised("BOULANGERIE VIEUX MOULIN"))
        assertEquals("THE MATCHA", normalised("THE MATCHA"))
    }

    @Test
    fun `accents fold for matching and survive for display`() {
        val accented = MerchantNormaliser.normalise("Marché Bellevue")!!

        assertEquals("Marché Bellevue", accented.displayName)
        assertEquals("MARCHE BELLEVUE", accented.normalisedName)
        assertEquals(normalised("Marche Bellevue"), accented.normalisedName)
    }

    /** Wallet uses a typographic apostrophe; a keyboard does not. */
    @Test
    fun `both apostrophes are the same merchant`() {
        assertEquals(normalised("RESTO L\u2019ENTREPOT"), normalised("RESTO L'ENTREPOT"))
    }

    @Test
    fun `a single space before digits is part of the name`() {
        assertEquals("DEPANNEUR 24", normalised("DEPANNEUR 24"))
    }

    @Test
    fun `an aggregator prefix is not part of the name`() {
        assertEquals("CAFE OLIMPICO", normalised("SQ *CAFE OLIMPICO"))
        assertEquals("CAFE OLIMPICO", normalised("TST* CAFE OLIMPICO"))
    }

    @Test
    fun `a name that is only a branch number keeps what it had`() {
        assertEquals("#10593", MerchantNormaliser.normalise("#10593")?.displayName)
    }

    @Test
    fun `a blank merchant is no merchant`() {
        assertNull(MerchantNormaliser.normalise("   "))
    }

    private fun normalised(raw: String) = MerchantNormaliser.normalise(raw)?.normalisedName
}
