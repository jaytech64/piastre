package io.github.jaytech64.depenses.spike.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * Does as little as possible: the system binds this on its own schedule and kills
 * it freely, so it hands every notification straight to the store and returns.
 */
class NotificationCaptureService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        CaptureStore.get(applicationContext).onNotificationPosted(sbn)
    }
}

object NotificationAccess {

    fun isGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /**
     * Android sometimes fails to rebind a listener after an app update, which is
     * the single most common way a capture app goes silently deaf.
     */
    fun requestRebind(context: Context) {
        NotificationListenerService.requestRebind(
            ComponentName(context, NotificationCaptureService::class.java),
        )
    }
}
