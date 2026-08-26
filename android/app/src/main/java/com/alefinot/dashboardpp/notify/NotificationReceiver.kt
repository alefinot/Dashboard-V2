package com.alefinot.dashboardpp.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The §6.1 [NotificationListenerService] — the app's single entry point into
 * the notification stream. The user grants `POST_NOTIFICATIONS` /
 * `ACCESS_NOTIFICATIONS` (the onboarding card, §10); once connected, the
 * system feeds every [StatusBarNotification] here, and we hand it to
 * [NotifOutbox] (classify → dedupe → the BLE link). There is no second
 * listener (the §6 "no second path" rule).
 */
class NotificationReceiver : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        AppMonogram.init(packageManager)
    }

    override fun onNotificationStatusChanged(pn: StatusBarNotification) {
        // The bar's own rule: the alert (or media, or nav) is enqueued and
        // pushed over BLE; the ESP draws it in the persistent bar.
        NotifOutbox.enqueue(pn)
    }

    override fun onNotificationRemoved(pn: StatusBarNotification) {
        // Maps dropping the nav notification = trip end / dismiss: the
        // widget must clear (the ESP has no expiry timer).
        NotifOutbox.onRemoved(pn)
    }
}
