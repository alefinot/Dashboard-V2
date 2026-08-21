package com.alefinot.dashboardpp.nav

import org.json.JSONObject

/**
 * The §4.3 NAV DTO — the exact keys the ESP's ingest reads: `on` (bool),
 * `act` (the maneuver / turn instruction, e.g. "Turn right"), `d` (the
 * distance), `u` (the unit, e.g. "km"/"mi"), `road` (the road name), `eta`
 * (the "HH:MM" arrival time), `arr` (the arrival flag). The ESP `navPush`
 * stores it and sets the `navChanged` dirty flag; the widget redraws in the
 * `(8,124,112,52)` region only when that flag is set (step 2, heap-gated).
 */
data class NavDto(
    val on: Boolean,
    val act: String,
    val d: Double,
    val u: String,
    val road: String,
    val eta: String,
    val arrival: Boolean,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("on", on)
        o.put("act", act)
        o.put("d", d)
        o.put("u", u)
        o.put("road", road)
        o.put("eta", eta)
        o.put("arr", arrival)
        return o.toString()
    }
}
