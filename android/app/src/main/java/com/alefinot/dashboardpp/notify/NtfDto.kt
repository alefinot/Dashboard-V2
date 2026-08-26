package com.alefinot.dashboardpp.notify

import org.json.JSONObject

/**
 * The §4.3 NOTIFICATION DTO — the exact keys the ESP's ingest reads:
 * `app`, `title`, `body`, `icon` (the monogram). The ESP `barPush` drops
 * it into the 8-deep alert FIFO (the bar's own rule) and draws it in the
 * persistent `y=0..32` strip.
 */
data class NtfDto(
    val app: String,
    val title: String,
    val body: String,
    val icon: String,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("app", app)
        o.put("title", title)
        o.put("body", body)
        o.put("icon", icon)
        return o.toString()
    }
}
