// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.core.designsystem

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat

/**
 * A whole screen in Piastre's own colours, the launcher icon's navy and orange,
 * for the screens that introduce the app rather than serve in it.
 *
 * Unlike [PiastreTheme] it ignores the wallpaper and the phone's dark mode, so
 * it looks the same on every phone and its contrast holds wherever it is drawn:
 * every text colour clears 6.5:1 on the navy. The roles carry the brand this way:
 *
 * - `primary` is the coin, for the actions and the current step.
 * - `secondary` is the slice, for the headings within a step.
 * - `onSurfaceVariant` is the running text.
 *
 * The navy is dark in either mode, so the clock and battery over it are drawn
 * light for as long as the screen is shown, and go back to following the
 * system once it leaves.
 */
@Composable
fun PiastreBrandScreen(content: @Composable () -> Unit) {
    SystemBarIconsLight()
    val navy = colorResource(R.color.brand_navy)
    val colorScheme =
        darkColorScheme(
            primary = colorResource(R.color.brand_coin),
            onPrimary = navy,
            secondary = colorResource(R.color.brand_slice),
            onSecondary = navy,
            background = navy,
            onBackground = Color.White,
            surface = navy,
            onSurface = Color.White,
            onSurfaceVariant = colorResource(R.color.brand_on_navy_muted),
            outlineVariant = colorResource(R.color.brand_navy_raised),
        )
    MaterialTheme(colorScheme = colorScheme, typography = MaterialTheme.typography) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}

/** The coin and its slice, as the launcher icon draws them. It says nothing a screen reader needs. */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.brand_mark), contentDescription = null, modifier = modifier)
}

@Composable
private fun SystemBarIconsLight() {
    val window = checkNotNull(LocalActivity.current) { "A brand screen is only ever drawn by an activity" }.window
    val view = LocalView.current
    DisposableEffect(window, view) {
        val bars = WindowCompat.getInsetsController(window, view)
        val lightStatus = bars.isAppearanceLightStatusBars
        val lightNavigation = bars.isAppearanceLightNavigationBars
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false
        onDispose {
            bars.isAppearanceLightStatusBars = lightStatus
            bars.isAppearanceLightNavigationBars = lightNavigation
        }
    }
}
