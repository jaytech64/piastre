// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jaytech64.piastre.core.model.CaptureHealth
import kotlinx.coroutines.launch

/** What the first run goes through, in the order it does. */
private enum class OnboardingStep {
    WELCOME,
    NOTIFICATION_ACCESS,
    WALLET_NOTIFICATIONS,
}

/**
 * The first screens, and the only place the app asks for anything.
 *
 * Notification access reads as an alarming permission — and it is one — so the
 * step that asks for it says what is read and what is kept before sending anyone
 * to the system screen that grants it. Under Law 25 that explanation is the point
 * of the step, not decoration on a button.
 *
 * Google Wallet's own notifications get a step to themselves because access
 * alone captures nothing while they are off, and that is the one setting the app
 * cannot read: the step can only say so and open the screen.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: CaptureHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val steps = OnboardingStep.entries
    val pager = rememberPagerState { steps.size }
    val step = steps[pager.currentPage]

    fun show(target: OnboardingStep) {
        scope.launch { pager.animateScrollToPage(target.ordinal) }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    // Granting happens in another app, so the only signal that it worked is the
    // health state changing while this screen is in the background. Moving on is
    // tied to having just been sent there, and saved, because Android may well
    // have dropped this process by the time the answer comes back.
    val granted = state.health?.let { it !is CaptureHealth.AccessRevoked } == true
    var sentToGrant by rememberSaveable { mutableStateOf(false) }
    // Keyed on the grant alone: clearing the flag must not restart the effect,
    // which would cancel the very scroll it has just begun.
    LaunchedEffect(granted) {
        if (granted && sentToGrant) {
            sentToGrant = false
            pager.animateScrollToPage(OnboardingStep.WALLET_NOTIFICATIONS.ordinal)
        }
    }

    // Not known for a frame, long gone by the time anyone reaches the step it decides.
    val walletInstalled = state.walletInstalled != false

    BackHandler(enabled = step != OnboardingStep.WELCOME) { show(steps[step.ordinal - 1]) }

    Column(
        Modifier
            .fillMaxSize()
            // The only screen outside the Scaffold, so it insets itself: the app
            // draws edge to edge and the title otherwise sits under the clock.
            .safeDrawingPadding()
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // A step's text scrolls and its buttons do not: at the largest system font
        // the text runs off a phone screen, and an "allow" button you have to go
        // looking for is how people end up granting without reading.
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                when (steps[page]) {
                    OnboardingStep.WELCOME -> WelcomeStep()
                    OnboardingStep.NOTIFICATION_ACCESS -> NotificationAccessStep(granted)
                    OnboardingStep.WALLET_NOTIFICATIONS -> WalletNotificationsStep(walletInstalled)
                }
            }
        }

        StepFooter(
            step = step,
            granted = granted,
            walletInstalled = walletInstalled,
            onShow = ::show,
            onGrant = {
                sentToGrant = true
                context.startActivity(viewModel.notificationAccessIntent())
            },
            onOpenWallet = { context.startActivity(viewModel.walletNotificationsIntent()) },
            onDone = onDone,
        )
    }
}

/** The buttons of the step on screen and the dots under them, which stay put while the steps slide past. */
@Composable
private fun StepFooter(
    step: OnboardingStep,
    granted: Boolean,
    walletInstalled: Boolean,
    onShow: (OnboardingStep) -> Unit,
    onGrant: () -> Unit,
    onOpenWallet: () -> Unit,
    onDone: () -> Unit,
) {
    val next = stringResource(R.string.onboarding_continue)
    val done = stringResource(R.string.onboarding_done)
    Column(
        Modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (step) {
            OnboardingStep.WELCOME -> {
                StepActions(next, { onShow(OnboardingStep.NOTIFICATION_ACCESS) })
            }

            OnboardingStep.NOTIFICATION_ACCESS -> {
                if (granted) {
                    StepActions(next, { onShow(OnboardingStep.WALLET_NOTIFICATIONS) })
                } else {
                    StepActions(
                        main = stringResource(R.string.onboarding_grant),
                        onMain = onGrant,
                        other = stringResource(R.string.onboarding_later),
                        onOther = { onShow(OnboardingStep.WALLET_NOTIFICATIONS) },
                    )
                }
            }

            OnboardingStep.WALLET_NOTIFICATIONS -> {
                if (walletInstalled) {
                    StepActions(stringResource(R.string.onboarding_wallet_open), onOpenWallet, done, onDone)
                } else {
                    StepActions(done, onDone)
                }
            }
        }
        StepDots(current = step.ordinal, count = OnboardingStep.entries.size)
    }
}

@Composable
private fun WelcomeStep() {
    StepTitle(stringResource(R.string.onboarding_title))
    Promise(
        title = stringResource(R.string.onboarding_capture_title),
        body = stringResource(R.string.onboarding_capture_body),
    )
    Promise(
        title = stringResource(R.string.onboarding_private_title),
        body = stringResource(R.string.onboarding_private_body),
    )
    Promise(
        title = stringResource(R.string.onboarding_control_title),
        body = stringResource(R.string.onboarding_control_body),
    )
}

@Composable
private fun NotificationAccessStep(granted: Boolean) {
    StepTitle(stringResource(R.string.onboarding_access_title))
    Promise(
        title = stringResource(R.string.onboarding_access_reads_title),
        body = stringResource(R.string.onboarding_access_reads_body),
    )
    Promise(
        title = stringResource(R.string.onboarding_access_keeps_title),
        body = stringResource(R.string.onboarding_access_keeps_body),
    )
    if (granted) {
        Text(
            stringResource(R.string.onboarding_access_granted),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun WalletNotificationsStep(walletInstalled: Boolean) {
    if (walletInstalled) {
        StepTitle(stringResource(R.string.onboarding_wallet_title))
        StepParagraph(stringResource(R.string.onboarding_wallet_why))
        StepParagraph(stringResource(R.string.onboarding_wallet_check))
        StepParagraph(stringResource(R.string.onboarding_wallet_blind))
    } else {
        StepTitle(stringResource(R.string.onboarding_wallet_absent_title))
        StepParagraph(stringResource(R.string.onboarding_wallet_absent_body))
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium)
}

@Composable
private fun StepParagraph(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Promise(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        StepParagraph(body)
    }
}

/** A step's buttons, one under the other so a long French label never has to share a line. */
@Composable
private fun StepActions(
    main: String,
    onMain: () -> Unit,
    other: String? = null,
    onOther: () -> Unit = {},
) {
    Button(onClick = onMain, modifier = Modifier.fillMaxWidth()) {
        Text(main, textAlign = TextAlign.Center)
    }
    if (other != null) {
        TextButton(onClick = onOther) {
            Text(other)
        }
    }
}

/** Where the first run stands. It shows and is not pressed, so a screen reader hears one sentence instead of dots. */
@Composable
private fun StepDots(
    current: Int,
    count: Int,
) {
    val position = stringResource(R.string.onboarding_step_of, current + 1, count)
    Row(
        Modifier.clearAndSetSemantics { contentDescription = position },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 24.dp else 8.dp, label = "step dot")
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .background(
                        color =
                            if (index == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        shape = CircleShape,
                    ),
            )
        }
    }
}
