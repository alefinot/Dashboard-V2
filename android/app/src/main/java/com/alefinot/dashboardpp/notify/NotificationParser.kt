package com.alefinot.dashboardpp.notify

import android.service.notification.StatusBarNotification

/**
 * Parse a [StatusBarNotification] into an [NtfDto] — the §4.3 NOTIFICATION
 * DTO the ESP's ingest reads (`app`, `title`, `body`, `epoch`, `icon`).
 * The `epoch` is the alert's `when` (ms); the `icon` is the monogram (the
 * first letter of the package) the bar draws in its circle. The bar's own
 * rule (§6.4) drops it into the 8-deep alert FIFO and draws it in the
 * persistent `y=0..32` strip.
 */
fun parseNotification(pn: StatusBarNotification): NtfDto {
    val title = pn.extras.getCharSequence("title")?.toString()
        ?: pn.extras.getCharSequence("android.title")?.toString()
        ?: ""
    val body = pn.extras.getCharSequence("text")?.toString()
        ?: pn.extras.getCharSequence("bigText")?.toString()
        ?: ""
    val pkg = pn.packageName ?: ""
    val icon = if (pkg.isNotEmpty()) pkg.first().uppercaseChar().toString() else ""
    return NtfDto(
        app = pkg,
        title = title,
        body = body,
        epoch = pn.`when`,
        icon = icon,
    )
}
