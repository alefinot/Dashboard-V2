package com.alefinot.dashboardpp.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager

/**
 * Discovers the ESP32's advertised `_http._tcp.` mDNS service (firmware
 * advertises "dashboard-pp" via ESPmDNS).
 *
 * onServiceFound() does not carry the resolved address on most Android
 * stacks — the host address only arrives through resolveService().
 * A multicast lock is held while listening: some OEM Wi-Fi stacks drop
 * mDNS (multicast) packets without one.
 */
class NsdDiscoverer(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var started = false

    fun start(onFound: (ip: String, port: Int) -> Unit) {
        if (started) return
        started = true
        if (nsdManager == null) return // no NSD on this device — the subnet scan takes over

        multicastLock = try {
            val lock = wifiManager.createMulticastLock("dashboardpp-mdns")
            lock.setReferenceCounted(true)
            lock.acquire()
            lock
        } catch (e: Exception) {
            null
        }

        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, error: Int) {
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, error: Int) {
                stop()
            }

            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                // A queued callback can outlive stop() on some OEM stacks;
                // resolving a stopped session throws on those stacks.
                if (listener == null) return
                // Some stacks already carry the address — use it directly.
                val direct = info.host?.hostAddress
                if (direct != null) {
                    stop()
                    onFound(direct, info.port)
                    return
                }
                // Normal path: resolve to get the host address.
                try {
                    nsdManager?.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo?, error: Int) {}

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            if (listener == null) return // stopped mid-resolve
                            val ip = resolved.host?.hostAddress ?: return
                            stop()
                            onFound(ip, resolved.port)
                        }
                    })
                } catch (e: Exception) {
                    stop()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        listener = l
        try {
            nsdManager?.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            // NSD unavailable on this device/API level — the subnet scan takes over.
            stop()
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        started = false
        try {
            nsdManager?.stopServiceDiscovery(l)
        } catch (e: Exception) {
        }
        val lock = multicastLock
        multicastLock = null
        try {
            lock?.release()
        } catch (e: Exception) {
        }
    }
}
