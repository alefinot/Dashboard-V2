package com.alefinot.dashboardpp.nav

import android.service.notification.StatusBarNotification

/**
 * The §6.5 nav parser (step 2 — heap-gated on the ESP side). Recognizes a
 * Google Maps nav notification by package, and parses it into a [NavDto]
 * (the §4.3 NAV DTO the ESP's ingest reads). The ESP `navPush` stores it and
 * sets the `navChanged` dirty flag; the widget redraws in the `(8,124,112,52)`
 * region only when that flag is set. Off (the `on=false` flag) clears the
 * widget.
 */
object MapsNavParser {
    private val MAPS_PACKAGES = setOf(
        "com.google.android.apps.maps",
        "com.google.maps",
    )

    /** Is this a Google Maps nav notification (the §6.5 gate)? */
    fun isNav(n: StatusBarNotification): Boolean {
        val pkg = n.packageName?.lowercase() ?: return false
        return MAPS_PACKAGES.any { pkg.contains(it) }
    }

    /**
     * Maps' arrival/summary notifications ("You've arrived", "Arrived at
     * …") reuse the Maps package: parsed as a live maneuver they would
     * re-arm the widget after the trip is over. They carry no maneuver, so
     * they count as trip end: `on=false`.
     */
    private fun isArrival(act: String, road: String): Boolean {
        val hay = (act + " " + road).lowercase()
        return hay.contains("arrived") || hay.contains("arrival")
    }

    /**
     * Parse a Google Maps nav notification into a [NavDto]. The `act` is the
     * maneuver (the `title`), the `road` is the road name (the `text`), the
     * `eta` is the arrival time (the `bigText`); `d`/`u` are a heuristic
     * default (`0.0`/`""`) — the full nav extras are app-specific.
     * Arrival/summary notifications parse to `on=false` (trip end).
     */
    fun parse(n: StatusBarNotification): NavDto {
        val act = n.extras.getCharSequence("title")?.toString()
            ?: n.extras.getCharSequence("android.title")?.toString()
            ?: ""
        val road = n.extras.getCharSequence("text")?.toString()
            ?: n.extras.getCharSequence("bigText")?.toString()
            ?: ""
        val eta = n.extras.getCharSequence("bigText")?.toString()
            ?: n.extras.getCharSequence("text")?.toString()
            ?: ""
        if (isArrival(act, road)) {
            return NavDto(
                on = false,
                act = "",
                d = 0.0,
                u = "",
                road = "",
                eta = "",
                arrival = false,
            )
        }
        return NavDto(
            on = true,
            act = act,
            d = 0.0,
            u = "",
            road = road,
            eta = eta,
            arrival = false,
        )
    }
}
