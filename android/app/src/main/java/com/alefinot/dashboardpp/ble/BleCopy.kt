package com.alefinot.dashboardpp.ble

/**
 * What a Bluetooth failure is called, in the user's language.
 *
 * [BleLink] reports failures as short stable codes (`connect_timeout`,
 * `pairing_failed`, `service_missing`) because the code is what a log line and a
 * test can compare against, and the screen is what has to explain them. Those two
 * vocabularies meet here, in one place, rather than in whichever composable happened
 * to render the failure: the settings sheet, the garage card and the reconnect
 * ladder all describe the same dropped link, and three wordings of one code is how a
 * user learns that "Link failed" and "Could not pair" are the same event.
 *
 * The strings are deliberately specific about what to *do*. A Bluetooth failure has
 * three real causes — the unit is off or out of range, the phone is not allowed to
 * use the radio (which on Android 11 and older includes its location switch: with the
 * switch off, a scan answers nothing at all), or the pairing went wrong — and each
 * needs a different fix, so a
 * generic "connection failed" line is not a smaller mistake but a bigger one.
 */

/** Why a scan refused to run, from the reason [BleLink.scan] closed its flow with. */
fun scanFailureText(reason: String?): String = when (reason) {
    "bluetooth_off" -> "Bluetooth is off. Turn it on and scan again."
    "no_bluetooth" -> "This phone has no Bluetooth LE radio."
    "permission" -> "Bluetooth permission denied. Allow it in Android settings."
    "no_scanner" -> "Android would not start a BLE scan. Try again."
    "location_off" ->
        "Location is off. Turn location on and scan again."
    else -> "Bluetooth scan failed: ${reason ?: "unknown"}"
}

/**
 * Why a link attempt failed, from [BleLinkState.Failed.reason].
 *
 * `pairing_failed` is the one worth reading twice: it almost always means the
 * six-digit code on the dashboard was never typed on the phone before Android's
 * dialog closed, which is the single most common way a first connection to this
 * unit fails on a real bench.
 */
fun linkFailureText(reason: String): String = when (reason) {
    "permission" -> "Bluetooth permission denied. Allow it in Android settings."
    "bluetooth_off" -> "Bluetooth is off. Turn it on and scan again."
    "no_bluetooth" -> "This phone has no Bluetooth LE radio."
    "connect_timeout", "connect_failed", "bad_address" ->
        "The dashboard did not answer. Move closer and retry."
    "pairing_failed" ->
        "Pairing did not complete — type the 6-digit code the unit shows."
    "security_stalled" ->
        "The dashboard answered, but pairing did not complete — clear the pairing and pair again."
    "telem_subscribe_failed", "rpc_subscribe_failed" ->
        "Notification subscription refused — clear the pairing and pair again."
    "service_missing", "telem_missing", "rpc_missing" ->
        "Not the expected dashboard service. Update the unit's firmware."
    "disconnected" -> "Link dropped — dialling again."
    "not_connected" -> "There is no link to send that over."
    "timeout" -> "The dashboard did not answer in time."
    else -> "Link failed: $reason"
}
