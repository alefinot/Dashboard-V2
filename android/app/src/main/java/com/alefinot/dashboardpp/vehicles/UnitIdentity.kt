package com.alefinot.dashboardpp.vehicles

import java.util.Locale

object UnitIdentity {

    /** The key of a unit that has never named itself and never answered on a pairing. */
    const val UNKNOWN = "unknown"

    fun key(mac: String?, bleAddress: String?): String {
        VehicleMatch.normalizeMac(mac)?.let { return "mac:$it" }
        val address = bleAddress?.trim()?.takeIf { it.isNotEmpty() }?.let(::normCase)
        return if (address != null) "ble:$address" else UNKNOWN
    }

    private fun normCase(value: String): String = value.uppercase(Locale.US)
}
