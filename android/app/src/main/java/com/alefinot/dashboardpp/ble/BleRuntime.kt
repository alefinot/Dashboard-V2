package com.alefinot.dashboardpp.ble

import android.content.Context

object BleRuntime {

    @Volatile
    private var instance: BleLink? = null

    fun link(context: Context): BleLink =
        instance ?: synchronized(this) {
            instance ?: BleLink(context.applicationContext).also { instance = it }
        }
}
