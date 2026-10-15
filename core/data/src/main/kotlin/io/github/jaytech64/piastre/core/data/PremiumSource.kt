// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

/**
 * What the store this build was made for lets the app ask of the person using it.
 *
 * Only `:app` knows which store that is: its `play` and `foss` flavours each
 * bring one `:premium:*` module that binds this, so no feature can tell the two
 * builds apart, and Google's billing library reaches the Play build's classpath
 * and no other.
 */
interface PremiumSource {
    /**
     * The web page a donation is made on, or null where the build must not ask:
     * Play's payments policy bars a Play app from pointing to another way to pay.
     */
    val donationPage: String?
}
