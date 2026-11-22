// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.data

import io.github.jaytech64.piastre.core.model.CaptureHealth
import kotlinx.coroutines.flow.Flow

/**
 * Whether capture is working, for a screen whose words depend on it.
 *
 * `:capture:listener` holds the answer and binds this, so a feature can read the
 * verdict without seeing the listener, the permission or the system screens.
 */
interface CaptureHealthSource {
    val health: Flow<CaptureHealth>
}
