package com.alefinot.dashboardpp.weather

import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

interface WeatherHttp {
    /** Null means "no answer": unresolvable host, non-2xx, empty body, or a timeout. */
    suspend fun get(url: String): String?
}

class OkHttpWeatherHttp(
    private val userAgent: String,
    private val client: OkHttpClient = defaultClient(userAgent),
) : WeatherHttp {

    override suspend fun get(url: String): String? {
        val request = try {
            Request.Builder().url(url).header("User-Agent", userAgent).build()
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (request.url.scheme != HTTPS_SCHEME) {
            return null
        }

        var attempt = 0
        while (true) {
            attempt++
            try {
                return exchange(request)
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS) return null
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun exchange(request: Request): String? =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string()
                if (body.isNullOrEmpty()) null else body
            }
        }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 10_000L

        /** Bounds one whole attempt — phases and connection acquisition included. */
        const val CALL_TIMEOUT_MS = 20_000L

        /** The gap between the first attempt and the only retry. */
        const val RETRY_DELAY_MS = 2_000L

        private const val MAX_ATTEMPTS = 2
        private const val HTTPS_SCHEME = "https"

        fun userAgent(context: Context): String =
            userAgentFor(
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                },
            )

        /** The formatting half of [userAgent], split out because it is the half worth testing. */
        internal fun userAgentFor(versionName: String?): String =
            "Dashboardpp-Android/${versionName ?: "dev"} (+${RepoContact.URL})"

        /** The client a production caller gets: our timeouts, and OkHttp's own connection reuse. */
        fun defaultClient(userAgent: String): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .build()
    }
}

internal object RepoContact {
    const val URL = "https://github.com/alefinot/Dashboard-V2"
}
