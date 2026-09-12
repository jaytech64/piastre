// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.data

import io.github.jaytech64.depenses.core.model.Payment
import io.github.jaytech64.depenses.core.model.PaymentMethod

/** The method column a payment is stored under. */
internal fun Payment?.method(): PaymentMethod? =
    when (this) {
        Payment.Cash -> PaymentMethod.CASH
        is Payment.ByCard -> PaymentMethod.CARD
        null -> null
    }

/** The card a payment points at, when it is one. */
internal fun Payment?.cardId(): Long? = (this as? Payment.ByCard)?.cardId
