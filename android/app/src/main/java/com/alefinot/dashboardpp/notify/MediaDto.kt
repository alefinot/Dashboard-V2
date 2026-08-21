package com.alefinot.dashboardpp.notify

import org.json.JSONObject

/**
 * The §4.3 MEDIA DTO — the exact keys the ESP's ingest reads: `artist`,
 * `song`, `icon`, `active` (the bar's `act` field — true = playing). The ESP
 * `barSetSong` stores it (or clears it, when `active=false`); the bar shows
 * the song only while it is playing (§5.4 of the plan — the song stays while
 * it plays, and the bar's `BAR_TIMEOUT_MS` is conditional on the song being
 * active, so alerts get their 7 s timeout only then).
 */
data class MediaDto(
    val artist: String,
    val song: String,
    val icon: String,
    val active: Boolean,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("artist", artist)
        o.put("song", song)
        o.put("icon", icon)
        o.put("active", active)
        return o.toString()
    }
}
