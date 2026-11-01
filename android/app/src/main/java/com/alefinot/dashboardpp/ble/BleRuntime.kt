package com.alefinot.dashboardpp.ble

import android.content.Context

/**
 * One link for the whole process.
 *
 * A GATT connection belongs to the phone, not to a screen: the settings panel
 * opens and closes, and the trip recorder and the live tab will both want the
 * same 1 Hz stream. Keeping the [BleLink] here is what lets them share one
 * connection instead of racing for the unit's single radio.
 */
object BleRuntime {

    @Volatile
    private var instance: BleLink? = null

    fun link(context: Context): BleLink =
        instance ?: synchronized(this) {
            instance ?: BleLink(context.applicationContext).also { instance = it }
        }
}
