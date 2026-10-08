// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.depenses.core.designsystem

import androidx.annotation.DrawableRes

/**
 * The icons a category can carry, each under the name stored for it, in the
 * order the picker shows them.
 *
 * The name is the Material Symbols one and is what the database and an export
 * hold, so an entry is never renamed: a category that carries it would go back
 * to its initials. `tools/import-category-icons.sh` brings in the drawable for
 * a name added here.
 */
val CATEGORY_ICONS: Map<String, Int> =
    linkedMapOf(
        "shopping_cart" to R.drawable.category_icon_shopping_cart,
        "restaurant" to R.drawable.category_icon_restaurant,
        "fastfood" to R.drawable.category_icon_fastfood,
        "local_cafe" to R.drawable.category_icon_local_cafe,
        "bakery_dining" to R.drawable.category_icon_bakery_dining,
        "local_bar" to R.drawable.category_icon_local_bar,
        "liquor" to R.drawable.category_icon_liquor,
        "home" to R.drawable.category_icon_home,
        "apartment" to R.drawable.category_icon_apartment,
        "bolt" to R.drawable.category_icon_bolt,
        "water_drop" to R.drawable.category_icon_water_drop,
        "mode_heat" to R.drawable.category_icon_mode_heat,
        "wifi" to R.drawable.category_icon_wifi,
        "phone_iphone" to R.drawable.category_icon_phone_iphone,
        "chair" to R.drawable.category_icon_chair,
        "cleaning_services" to R.drawable.category_icon_cleaning_services,
        "construction" to R.drawable.category_icon_construction,
        "yard" to R.drawable.category_icon_yard,
        "local_laundry_service" to R.drawable.category_icon_local_laundry_service,
        "directions_car" to R.drawable.category_icon_directions_car,
        "local_gas_station" to R.drawable.category_icon_local_gas_station,
        "ev_station" to R.drawable.category_icon_ev_station,
        "build" to R.drawable.category_icon_build,
        "local_parking" to R.drawable.category_icon_local_parking,
        "directions_bus" to R.drawable.category_icon_directions_bus,
        "train" to R.drawable.category_icon_train,
        "local_taxi" to R.drawable.category_icon_local_taxi,
        "pedal_bike" to R.drawable.category_icon_pedal_bike,
        "flight" to R.drawable.category_icon_flight,
        "medical_services" to R.drawable.category_icon_medical_services,
        "medication" to R.drawable.category_icon_medication,
        "local_pharmacy" to R.drawable.category_icon_local_pharmacy,
        "dentistry" to R.drawable.category_icon_dentistry,
        "visibility" to R.drawable.category_icon_visibility,
        "fitness_center" to R.drawable.category_icon_fitness_center,
        "spa" to R.drawable.category_icon_spa,
        "content_cut" to R.drawable.category_icon_content_cut,
        "shopping_bag" to R.drawable.category_icon_shopping_bag,
        "checkroom" to R.drawable.category_icon_checkroom,
        "devices" to R.drawable.category_icon_devices,
        "storefront" to R.drawable.category_icon_storefront,
        "redeem" to R.drawable.category_icon_redeem,
        "movie" to R.drawable.category_icon_movie,
        "theater_comedy" to R.drawable.category_icon_theater_comedy,
        "sports_esports" to R.drawable.category_icon_sports_esports,
        "music_note" to R.drawable.category_icon_music_note,
        "menu_book" to R.drawable.category_icon_menu_book,
        "subscriptions" to R.drawable.category_icon_subscriptions,
        "sports_hockey" to R.drawable.category_icon_sports_hockey,
        "downhill_skiing" to R.drawable.category_icon_downhill_skiing,
        "hiking" to R.drawable.category_icon_hiking,
        "camping" to R.drawable.category_icon_camping,
        "beach_access" to R.drawable.category_icon_beach_access,
        "hotel" to R.drawable.category_icon_hotel,
        "luggage" to R.drawable.category_icon_luggage,
        "photo_camera" to R.drawable.category_icon_photo_camera,
        "celebration" to R.drawable.category_icon_celebration,
        "child_care" to R.drawable.category_icon_child_care,
        "school" to R.drawable.category_icon_school,
        "toys" to R.drawable.category_icon_toys,
        "pets" to R.drawable.category_icon_pets,
        "family_restroom" to R.drawable.category_icon_family_restroom,
        "payments" to R.drawable.category_icon_payments,
        "work" to R.drawable.category_icon_work,
        "account_balance" to R.drawable.category_icon_account_balance,
        "savings" to R.drawable.category_icon_savings,
        "credit_card" to R.drawable.category_icon_credit_card,
        "receipt_long" to R.drawable.category_icon_receipt_long,
        "request_quote" to R.drawable.category_icon_request_quote,
        "trending_up" to R.drawable.category_icon_trending_up,
        "percent" to R.drawable.category_icon_percent,
        "currency_exchange" to R.drawable.category_icon_currency_exchange,
        "security" to R.drawable.category_icon_security,
        "gavel" to R.drawable.category_icon_gavel,
        "volunteer_activism" to R.drawable.category_icon_volunteer_activism,
        "category" to R.drawable.category_icon_category,
        "star" to R.drawable.category_icon_star,
        "favorite" to R.drawable.category_icon_favorite,
        "mail" to R.drawable.category_icon_mail,
        "more_horiz" to R.drawable.category_icon_more_horiz,
    )

/** The drawable for the icon named [name], or null for no name and for one this build does not know. */
@DrawableRes
fun categoryIconRes(name: String?): Int? = name?.let(CATEGORY_ICONS::get)
