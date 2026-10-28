// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.play

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The code that turns Premium on for one install, so Google's reviewers, who
 * cannot buy, start a trial or make an account, reach every paid feature.
 *
 * Only the code's SHA-256 is here: the code itself lives beside the upload key
 * in `~/.config/piastre/review-code` and is given to Play Console, never to the
 * repository. A plain hash is enough because the code is 80 random bits, which
 * no one guesses back from a public hash; the GPL source and the free F-Droid
 * build mean it guards nothing more than that (ADR-0025).
 *
 * The unlock is stored as the hash the code matched, and counts only while that
 * is still this build's hash, so a release that ships a new code revokes every
 * unlock made with the old one.
 */
@Singleton
internal class ReviewCodeUnlock(
    private val store: DataStore<Preferences>,
    private val codeSha256: String,
) {
    @Inject
    constructor(store: DataStore<Preferences>) : this(store, REVIEW_CODE_SHA256)

    val entered: Flow<Boolean> = store.data.map { it[ENTERED] == codeSha256 }.distinctUntilChanged()

    /** Stores the unlock if [code] is the review code, and says whether it was. */
    suspend fun redeem(code: String): Boolean {
        val matches = MessageDigest.isEqual(sha256(normalised(code)).toByteArray(), codeSha256.toByteArray())
        if (matches) store.edit { it[ENTERED] = codeSha256 }
        return matches
    }

    internal companion object {
        /** Rotated by `docs/releasing.md`, never by hand-editing the code into the repository. */
        const val REVIEW_CODE_SHA256 = "79b2776532f144f6376010f126dee78c82361824a28e57397f471df48bd39595"

        private val ENTERED = stringPreferencesKey("play_review_code_sha256")

        /**
         * The code as Crockford base32 reads it: case, spaces and hyphens do not
         * count, and the letters O, I and L are the digits they look like.
         */
        fun normalised(code: String): String =
            code
                .uppercase(Locale.ROOT)
                .filterNot { it.isWhitespace() || it == '-' }
                .replace('O', '0')
                .replace('I', '1')
                .replace('L', '1')

        fun sha256(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
