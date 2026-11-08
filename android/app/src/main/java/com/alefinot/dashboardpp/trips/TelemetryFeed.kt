package com.alefinot.dashboardpp.trips

import com.alefinot.dashboardpp.api.Snapshot

object TelemetryFeed {

    /** How long a `Ready` link may stay quiet before the app asks once. */
    const val SILENT_MS = 5_000L

    fun accepts(lastUp: Long, frameUp: Long): Boolean = lastUp < 0L || frameUp != lastUp

    fun dueForPoll(lastFrameMs: Long, nowMs: Long, pollInFlight: Boolean): Boolean =
        !pollInFlight && (lastFrameMs == 0L || nowMs - lastFrameMs >= SILENT_MS)

    fun mergeProd(lastUp: Long, reply: Snapshot?): Snapshot? =
        reply?.takeIf { accepts(lastUp, it.up) }
}
