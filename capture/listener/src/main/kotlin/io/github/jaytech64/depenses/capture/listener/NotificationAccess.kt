package io.github.jaytech64.depenses.capture.listener

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification access is granted and revoked entirely outside the app, so it is
 * never cached: every reader asks the system.
 *
 * A capture app that believes a stale "granted" goes silently deaf, which is the
 * worst failure this app has — it looks like you simply did not spend anything.
 */
@Singleton
class NotificationAccess
    @Inject
    constructor(
        private val context: Context,
    ) {
        fun isGranted(): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        fun componentName(serviceClass: Class<*>): ComponentName = ComponentName(context, serviceClass)
    }
