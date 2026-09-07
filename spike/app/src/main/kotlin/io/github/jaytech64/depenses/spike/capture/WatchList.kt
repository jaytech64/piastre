package io.github.jaytech64.depenses.spike.capture

import android.content.Context

/**
 * Packages whose notification *content* may be written to disk.
 *
 * Everything else is only ever counted, never read. Seeded with the Google Wallet
 * candidates; anything else you discover in the Apps tab is added by hand.
 */
class WatchList(context: Context) {

    private val preferences = context.getSharedPreferences("watch_list", Context.MODE_PRIVATE)

    fun watched(): Set<String> = preferences.getStringSet(KEY, null) ?: SEEDED

    fun isWatched(packageName: String): Boolean = packageName in watched()

    fun setWatched(packageName: String, watched: Boolean) {
        val updated = watched().toMutableSet()
        if (watched) updated.add(packageName) else updated.remove(packageName)
        preferences.edit().putStringSet(KEY, updated).apply()
    }

    private companion object {
        const val KEY = "packages"

        /** Verified present on the target device: com.google.android.apps.walletnfcrel. */
        val SEEDED = setOf(
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user",
        )
    }
}
