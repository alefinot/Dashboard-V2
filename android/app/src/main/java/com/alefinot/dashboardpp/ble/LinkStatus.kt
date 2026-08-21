package com.alefinot.dashboardpp.ble

import org.json.JSONObject

/**
 * The ESP STATUS payload, flattened to what the health chip renders (§4.3).
 * The ESP sends it on connect + on PING:
 * ```json
 * {"t":"st","heap":405000,"fps":57,"wifi":true,"bt":true,"nq":0,"ver":"2.0.0"}
 * ```
 * [heap] is the live free-heap byte count; [fps] the current average frame
 * rate; [wifi]/[bt] link flags; [nq] the bar notification-queue depth
 * (0 = empty); [ver] the firmware version.
 */
data class LinkStatus(
    val heap: Long,
    val fps: Int,
    val wifi: Boolean,
    val bt: Boolean,
    val nq: Int,
    val ver: String,
) {
    companion object {
        fun fromJson(o: JSONObject?): LinkStatus? {
            if (o == null) return null
            return LinkStatus(
                heap = o.optLong("heap"),
                fps = o.optInt("fps"),
                wifi = o.optBoolean("wifi", false),
                bt = o.optBoolean("bt", false),
                nq = o.optInt("nq"),
                ver = o.optString("ver"),
            )
        }
    }
}
