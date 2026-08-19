package com.alefinot.dashboardpp.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    const val SOFT_AP_SSID = "Dashboard_Config"
    const val SOFT_AP_IP = "192.168.4.1"

    /**
     * SSID of the network the phone is currently on, or null when it
     * cannot be read (deprecated WifiInfo path — no permission on
     * newer Android).
     */
    fun currentSsid(context: Context): String? {
        return try {
            val wifi =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifi.connectionInfo ?: return null
            val ssid = info.ssid?.trim('"') ?: return null
            if (ssid.isEmpty() || ssid == "unknown") return null
            ssid
        } catch (e: Exception) {
            null
        }
    }

    fun isSoftAp(ssid: String?): Boolean = ssid == SOFT_AP_SSID

    /** True when the default network is a Wi-Fi link (not cellular/ethernet). */
    fun onWifiNetwork(context: Context): Boolean {
        return try {
            val cm =
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(ConnectivityManager.TYPE_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * True when the phone can reach a local LAN: Wi-Fi is connected, or
     * some interface holds a local IPv4 address — Wi-Fi hotspot (wlan1),
     * USB tethering (usb0), Ethernet. The ESP32 joins the phone's hotspot
     * as a DHCP client, so a hotspot-only setup is a valid local network.
     */
    fun hasLocalLan(context: Context): Boolean {
        return onWifiNetwork(context) || localIpv4Subnets(context).isNotEmpty()
    }

    /**
     * (ip, prefix) pairs for every local IPv4 address currently on the
     * phone. The default network's address uses its real LinkProperties
     * prefix; other interfaces (hotspot, USB tethering) assume /24 —
     * Android's hotspot subnet is always 192.168.42.0/24.
     */
    fun localIpv4Subnets(context: Context): List<Pair<Long, Int>> {
        val out = linkedSetOf<Pair<Long, Int>>()
        // Default network first — authoritative prefix from LinkProperties.
        try {
            val cm =
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            if (net != null) {
                val lp = cm.getLinkProperties(net)
                val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
                if (v4 != null) {
                    val ip = ipToLong(v4.address as Inet4Address)
                    val p = v4.prefixLength
                    if (isLocalIp(ip) && p in 1..31) out.add(ip to p)
                }
            }
        } catch (e: Exception) {
        }
        // Every interface — hotspot (wlan1), USB tethering (usb0), Ethernet…
        try {
            for (ifc in NetworkInterface.getNetworkInterfaces()) {
                try {
                    if (!ifc.isUp()) continue
                } catch (e: Exception) {
                    continue
                }
                try {
                    for (a in ifc.getInetAddresses()) {
                        if (a is Inet4Address) {
                            val ip = ipToLong(a)
                            if (isLocalIp(ip) && out.none { it.first == ip }) {
                                out.add(ip to 24)
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
        return out.toList()
    }

    fun isValidIpv4(s: String): Boolean {
        val parts = s.split(".")
        if (parts.size != 4) return false
        return parts.all {
            if (it.isEmpty()) return false
            val n = it.toIntOrNull() ?: return false
            n in 0..255
        }
    }

    /** True for RFC1918 (10/8, 172.16/12, 192.168/16) and link-local 169.254/16. */
    fun isLocalIp(ip: Long): Boolean {
        val o1 = ((ip shr 24) and 0xFF).toInt()
        val o2 = ((ip shr 16) and 0xFF).toInt()
        return o1 == 10 ||
            (o1 == 172 && o2 in 16..31) ||
            (o1 == 192 && o2 == 168) ||
            (o1 == 169 && o2 == 254)
    }

    private fun ipToLong(addr: Inet4Address): Long {
        val b = addr.address
        return (b[0].toLong() and 0xFF) * 0x1000000 +
            (b[1].toLong() and 0xFF) * 0x10000 +
            (b[2].toLong() and 0xFF) * 0x100 +
            (b[3].toLong() and 0xFF)
    }
}
