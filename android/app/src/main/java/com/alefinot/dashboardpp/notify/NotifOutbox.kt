package com.alefinot.dashboardpp.notify

import android.service.notification.StatusBarNotification
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.Protocol
import com.alefinot.dashboardpp.nav.MapsNavParser

/**
 * The §6.1 outbox — the single place notifications are classified, deduped,
 * and pushed over BLE. The [NotificationReceiver] service hands each
 * [StatusBarNotification] here; we classify it (media / nav / alert),
 * dedupe (the 24 h / 30 s window), and push the matching frame over the
 * link. Bounded by the [Dedupe] cache (the §6.4 "bounded buffer").
 */
object NotifOutbox {
    private const val DEDUPE_WINDOW_MS = 30_000L
    private const val DEDUPE_PRUNE_MS = 24 * 60 * 60 * 1000L

    // Set by the owning ViewModel (the app process is shared).
    @Volatile
    var ble: BleLink? = null

    private val lastSend = HashMap<String, Long>()

    /**
     * Enqueue one notification: classify → dedupe → [BleLink.sendFrame].
     * (a) media → the bar's `barSetSong`; (b) nav → the `navPush`; (c)
     * alert (the bar's own rule) → the `barPush` (the 8-deep alert FIFO,
     * drop-oldest).
     *
     * The 30 s (package, title) dedupe applies to ALERTS only: a media /
     * nav frame is a STATE UPDATE (pause/stop, a fresh maneuver), not a
     * duplicate - dropping it would leave the ESP's song/nav state stale.
     */
    fun enqueue(n: StatusBarNotification) {
        val kind = classify(n)
        if (kind == Kind.ALERT && !shouldSend(n)) return
        val (type, json) = when (kind) {
            Kind.ALERT -> {
                val dto = parseNotification(n)
                Protocol.T_NOTIFICATION to dto.toJson()
            }
            Kind.MEDIA -> {
                val dto = MediaParser.parse(n)
                Protocol.T_MEDIA to dto.toJson()
            }
            Kind.NAV -> {
                val dto = MapsNavParser.parse(n)
                Protocol.T_NAV to dto.toJson()
            }
        }
        ble?.sendFrame(Protocol.encode(type, json))
    }

    /** The §6.4 classifier. */
    private fun classify(n: StatusBarNotification): Kind = when {
        MediaParser.isMedia(n) -> Kind.MEDIA
        MapsNavParser.isNav(n) -> Kind.NAV
        else -> Kind.ALERT
    }

    /**
     * The 24 h / 30 s dedupe (the §6.4 "last-24-h hash, 30 s window") -
     * alerts only (media/nav frames are state updates and bypass it, see
     * [enqueue]). A notification whose (package, title) was already pushed
     * within 30 s is dropped; the cache is pruned to 24 h so the map
     * stays bounded.
     *
     * The check-and-record sequence runs on the map's monitor: NLS
     * callbacks can arrive on multiple binder threads during a burst, and
     * an unsynchronized HashMap would corrupt under a concurrent
     * put / iterator-remove.
     */
    private fun shouldSend(n: StatusBarNotification): Boolean =
        synchronized(lastSend) {
            val title = n.extras.getCharSequence("title")?.toString() ?: ""
            val hash = (n.packageName ?: "") + "|" + title
            val now = System.currentTimeMillis()
            val last = lastSend[hash]
            if (last != null && now - last < DEDUPE_WINDOW_MS) return@synchronized false
            lastSend[hash] = now
            // Prune to 24 h.
            val it = lastSend.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value > DEDUPE_PRUNE_MS) it.remove()
            }
            true
        }

    private enum class Kind { ALERT, MEDIA, NAV }
}
