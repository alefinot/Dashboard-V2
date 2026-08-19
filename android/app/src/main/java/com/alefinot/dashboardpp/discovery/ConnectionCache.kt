package com.alefinot.dashboardpp.discovery

import android.content.Context

/**
 * Remembers the last successfully connected Dashboard++ IP so the next app
 * launch can try it before falling back to discovery.
 */
class ConnectionCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("dashboardpp", Context.MODE_PRIVATE)

    fun lastIp(): String? {
        return prefs.getString("last_ip", null)
    }

    fun setIp(ip: String) {
        prefs.edit().putString("last_ip", ip).apply()
    }

    fun clear() {
        prefs.edit().remove("last_ip").apply()
    }
}
