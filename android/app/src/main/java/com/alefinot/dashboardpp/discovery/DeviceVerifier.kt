package com.alefinot.dashboardpp.discovery

import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface VerifyResult {
    data class Verified(val ip: String, val version: String?) : VerifyResult
    object Failed : VerifyResult
}

/**
 * Confirms a candidate IP is really the Dashboard++ ESP32 by checking the
 * /api/perf JSON signature, then fetches the firmware version for the
 * settings screen.
 */
class DeviceVerifier {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    suspend fun verify(ip: String): VerifyResult {
        return try {
            val response = client.newCall(
                Request.Builder().url("http://$ip/api/perf").build()
            ).execute()
            val body = response.body?.string() ?: ""
            response.close()
            val ok = response.code in 200..299 &&
                body.contains("cpu_temp") &&
                body.contains("fps_current") &&
                body.contains("uptime_s")
            if (!ok) return VerifyResult.Failed
            val version = fetchVersion(ip)
            VerifyResult.Verified(ip, version)
        } catch (e: Exception) {
            VerifyResult.Failed
        }
    }

    private fun fetchVersion(ip: String): String? {
        return try {
            val response = client.newCall(
                Request.Builder().url("http://$ip/api/ota/check").build()
            ).execute()
            val body = response.body?.string() ?: ""
            response.close()
            if (response.code in 200..299) {
                val json = JSONObject(body)
                val v = json.optString("current_version")
                if (v.isNotEmpty()) v else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

}
