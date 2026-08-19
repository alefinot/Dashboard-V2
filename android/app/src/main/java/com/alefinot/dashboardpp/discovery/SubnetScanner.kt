package com.alefinot.dashboardpp.discovery

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Probes the local subnet for the Dashboard++ ESP32 by GET /api/perf on
 * every host. First host whose response matches the ESP32's perf JSON
 * signature wins.
 *
 * Probes run on Dispatchers.IO behind a semaphore: the Default executor
 * (fixed-size, and shared with the connection view-model's own
 * coroutines) must not be occupied by 250+ blocking HTTP calls.
 */
class SubnetScanner(context: Context) {
    private val appContext = context.applicationContext

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.MILLISECONDS)
        .readTimeout(300, TimeUnit.MILLISECONDS)
        .build()

    private var root: Job? = null

    fun start(onProgress: (Float) -> Unit, onFound: (String) -> Unit) {
        root?.cancel()
        root = null
        val job = SupervisorJob()
        root = job
        val scope = CoroutineScope(Dispatchers.IO + job)
        scope.launch {
            val probes = probePlan()
            val total = probes.size
            val completed = AtomicInteger(0)
            val found = AtomicBoolean(false)
            val semaphore = Semaphore(40)
            val jobs = probes.map { ip ->
                scope.launch {
                    semaphore.acquire()
                    try {
                        if (!found.get() && probe(ip)) {
                            if (found.compareAndSet(false, true)) {
                                onFound(ip)
                            }
                        }
                    } finally {
                        val done = completed.incrementAndGet()
                        onProgress(done.toFloat() / total)
                        semaphore.release()
                    }
                }
            }
            jobs.joinAll()
        }
    }

    fun cancel() {
        root?.cancel()
        root = null
    }

    /**
     * Hosts to probe: every local subnet the phone currently sits on
     * (Wi-Fi, Wi-Fi hotspot, USB tethering, Ethernet — Android's hotspot
     * subnet is 192.168.42.0/24), plus the SoftAP address 192.168.4.1 so
     * SoftAP mode works even when nothing else can be read.
     */
    private fun probePlan(): List<String> {
        val ips = linkedSetOf(Net.SOFT_AP_IP)
        val seen = linkedSetOf<Long>()
        for ((ip, prefix) in Net.localIpv4Subnets(appContext)) {
            val base: Long
            val hosts: IntRange
            when (prefix) {
                23 -> {
                    base = ip and 0xFFFFFE00L
                    hosts = 1..510
                }

                else -> {
                    base = ip and 0xFFFFFF00L
                    hosts = 1..254
                }
            }
            if (!seen.add(base)) continue
            for (host in hosts) {
                ips.add(hostIp(base, host))
            }
        }
        return ips.toList()
    }

    private fun hostIp(base: Long, host: Int): String {
        val full = base + host
        return "${(full shr 24) and 0xFFL}.${(full shr 16) and 0xFFL}.${(full shr 8) and 0xFFL}.${full and 0xFFL}"
    }

    private suspend fun probe(ip: String): Boolean {
        return try {
            val response = probeClient.newCall(
                Request.Builder().url("http://$ip/api/perf").build()
            ).execute()
            val ok = response.code in 200..299
            val body = response.body?.string() ?: ""
            response.close()
            ok && body.contains("cpu_temp") && body.contains("fps_current")
        } catch (e: Exception) {
            false
        }
    }
}
