// SPDX-FileCopyrightText: 2026 Jérémy Mailhot
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.jaytech64.piastre.premium.play

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jaytech64.piastre.core.designsystem.Formatters
import io.github.jaytech64.piastre.core.model.Money
import io.github.jaytech64.piastre.core.model.Premium
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * One notification, a week before a trial this app started becomes a paid year,
 * saying when, at what price, and how to cancel (decision 12 of the Premium plan).
 *
 * An inexact alarm wakes the app for it and needs no permission; a restart
 * clears every alarm, so [TrialReminderReceiver] sets it again at boot. Every
 * conversation with Play checks too, so a missed alarm is caught the next time
 * the process runs. It is sent once, never for a cancelled trial, and never once
 * the trial has ended. A refused notification permission is respected: nothing
 * is marked sent, so the reminder goes out if the permission is granted in time.
 */
@Singleton
internal class TrialReminder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val trials: TrialRecords,
        private val clock: Clock,
        private val zoneId: Provider<ZoneId>,
    ) {
        fun schedule(endsAt: Instant) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            alarms.set(AlarmManager.RTC_WAKEUP, endsAt.minus(NOTICE).toEpochMilli(), alarm())
        }

        suspend fun scheduleRecorded() {
            trials.current()?.takeIf { !trials.reminded() }?.let { schedule(it.endsAt) }
        }

        suspend fun remindIfDue(premium: Premium) {
            val subscription = premium as? Premium.Subscribed ?: return
            val endsAt = subscription.trialEndsAt ?: return
            val now = clock.instant()
            val due = subscription.renews && now.isAfter(endsAt.minus(NOTICE)) && now.isBefore(endsAt)
            if (!due || trials.reminded()) return
            val trial = trials.current() ?: return
            if (post(endsAt, trial.yearlyPrice)) trials.markReminded()
        }

        /** False when notifications are off, so a later grant still gets the reminder out. */
        private fun post(
            endsAt: Instant,
            yearlyPrice: Money,
        ): Boolean {
            val notifications = NotificationManagerCompat.from(context)
            if (!notifications.areNotificationsEnabled()) return false
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.trial_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            val day = Formatters.date(endsAt.atZone(zoneId.get()).toLocalDate())
            val body = context.getString(R.string.trial_ends_body, Formatters.money(yearlyPrice))
            val manage =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(Intent.ACTION_VIEW, Uri.parse(PlayProducts.manageUrl(context.packageName))),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(context.getString(R.string.trial_ends_title, day))
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(manage)
                    .setAutoCancel(true)
                    .build()
            try {
                notifications.notify(NOTIFICATION_ID, notification)
            } catch (refused: SecurityException) {
                Log.w(TAG, "Android refused the trial reminder", refused)
                return false
            }
            return true
        }

        private fun alarm(): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, TrialReminderReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private companion object {
            /** A week: inside the 2 to 10 days Quebec asks of a trial-end notice, with room for a late alarm. */
            val NOTICE: Duration = Duration.ofDays(7)

            const val TAG = "TrialReminder"
            const val CHANNEL = "trial"
            const val NOTIFICATION_ID = 1
        }
    }
