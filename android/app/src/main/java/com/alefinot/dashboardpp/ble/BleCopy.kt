package com.alefinot.dashboardpp.ble

/** Why a scan refused to run, from the reason [BleLink.scan] closed its flow with. */
fun scanFailureText(reason: String?): String = when (reason) {
    "bluetooth_off" -> "Bluetooth is off. Turn it on and scan again."
    "no_bluetooth" -> "This phone has no Bluetooth LE radio."
    "permission" -> "Bluetooth permission denied. Allow it in Android settings."
    "no_scanner" -> "Android would not start a BLE scan. Try again."
    "location_off" ->
        "Android 11 and older give no BLE scan results at all while the location switch is off, " +
            "even with permission granted. Nothing has asked the dashboard yet: turn location on " +
            "and scan again."
    else -> "Bluetooth scan failed: ${reason ?: "unknown"}"
}

fun linkFailureText(reason: String): String = when (reason) {
    "permission" -> "Bluetooth permission denied. Allow it in Android settings."
    "bluetooth_off" -> "Bluetooth is off. Turn it on and scan again."
    "no_bluetooth" -> "This phone has no Bluetooth LE radio."
    "connect_timeout", "connect_failed", "bad_address" ->
        "The dashboard did not answer. Move closer, check it is powered, then retry."
    "pairing_failed" ->
        "Pairing did not complete. The unit must show a 6-digit code and it must be " +
            "typed on this phone before the dialog closes."
    "telem_subscribe_failed", "rpc_subscribe_failed" ->
        "The unit refused the notification subscription. Clear the pairing in Android " +
            "settings and pair again."
    "service_missing", "telem_missing", "rpc_missing" ->
        "This is not the expected dashboard service. Update the unit's firmware."
    "disconnected" -> "The link dropped. The unit advertises again; the app dials it again."
    "not_connected" -> "There is no link to send that over."
    "timeout" -> "The dashboard did not answer in time."
    else -> "Link failed: $reason"
}
