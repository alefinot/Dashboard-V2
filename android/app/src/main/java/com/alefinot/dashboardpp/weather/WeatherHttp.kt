package com.alefinot.dashboardpp.weather

import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The app's seam to the internet, and the only file in the module allowed to import an HTTP
 * client. It exists because a Dashboard++ unit has no radio of its own: the temperature, the sun
 * times and the place name on the panel's weather card are things the *phone* finds from
 * Open-Meteo and the place-name services, and hands over with `weather.set`. It is deliberately
 * not a second road to a unit — everything read FROM a unit still arrives over GATT, and
 * `SettingsTabReplacesTheWebPageTest.thereIsNoSecondWayToReachAUnitLeftInTheApp` fails the build
 * the day a second file starts importing `okhttp3`.
 *
 * `get` answers with the body, or with `null` when the call did not produce one. Every caller is
 * written against that `null`: a refresh that cannot reach the sky keeps the last frame it pushed
 * rather than inventing one, so a failure here is ordinary weather, not an exception. A caller
 * that treats `null` as exceptional is how a car in a tunnel ends up showing zeros.
 */
interface WeatherHttp {
    /** Null means "no answer": unresolvable host, non-2xx, empty body, or a timeout. */
    suspend fun get(url: String): String?
}

/**
 * The production [WeatherHttp].
 *
 * Timeouts, per the plan's line for this client: 10 s to connect, 15 s to read, and a 20 s
 * *call* timeout over the whole attempt. The per-phase numbers are what a cold cell connection
 * needs; the call timeout is what bounds one attempt, because two slow phases that each sit just
 * under their own limit would otherwise add up to a refresh that spent half a minute on one
 * provider that has stopped answering. Two attempts plus the retry wait is then the whole
 * transaction, and the coordinator's schedule stays in seconds rather than minutes.
 *
 * No HTTP cache and no redirects. Neither provider needs one today (both answer 200 on the URL
 * they are given), so accepting a redirect would only ever do one thing: send the car's
 * coordinates to a host this app did not choose. The plan says the same thing in one line —
 * "no redirects-followed-to-other-hosts" — and OkHttp's switch is all-or-nothing, which is the
 * stricter reading and the one that cannot be misconfigured later.
 *
 * One retry, after 2 s, and only for an [IOException]. That failure is the one worth asking
 * again: a radio handover drops a connection that would have worked a moment later. A provider
 * that answered 404, 429 or 503 *did* answer — the link is fine and the resource is not, and
 * re-sending it two seconds later is exactly the pattern Nominatim's usage policy calls faulty.
 * A third attempt is an outage, and the caller already reschedules the whole feed on its own.
 *
 * The `User-Agent` is set on the request here, so no caller can forget it. Nominatim's usage
 * policy asks for an identification of the application with a way to reach its author, and says
 * plainly that "stock User-Agents as set by http libraries will not do" — a weather feed that
 * sends a bare `OkHttp` is the case they are warning about.
 *
 * @param client injected so the tests can replace the whole network without a socket; the default
 *   is built from the same [userAgent] the caller would pass, which is why it is a default
 *   argument rather than a second constructor.
 */
class OkHttpWeatherHttp(
    private val userAgent: String,
    private val client: OkHttpClient = defaultClient(userAgent),
) : WeatherHttp {

    override suspend fun get(url: String): String? {
        val request = try {
            Request.Builder().url(url).header("User-Agent", userAgent).build()
        } catch (e: IllegalArgumentException) {
            // A URL this client cannot parse is a bug in the caller's URL builder, not a
            // transient failure: no amount of retrying will make it parse.
            return null
        }
        if (request.url.scheme != HTTPS_SCHEME) {
            // A typed city name and the car's coordinates go out here, so cleartext is refused
            // before a request is committed rather than reported after the provider answered.
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

    /** The body of a 2xx that had one; null when the response was usable but said nothing. */
    private fun exchange(request: Request): String? =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()
            if (body.isNullOrEmpty()) null else body
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

        /**
         * The identification string this app sends to every weather and place-name provider:
         * `Dashboardpp-Android/<versionName> (+<repo url>)`. The version is read from the
         * installed package so it cannot drift from the APK that is actually running, and the
         * repository is the contact — there is no secrets file in this project and no address to
         * invent, while the repository is where whoever built this can be found.
         */
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
                // A 3xx from a provider would move the car's coordinates to a host we did not
                // pick, so it is refused: the answer arrives as null and the feed keeps the frame
                // it already had. Both hosts answer 200 today, so nothing is given up.
                .followRedirects(false)
                .followSslRedirects(false)
                // No cache is configured, which in OkHttp means no cache at all — the response is
                // read from the network or not at all. The weather cache this app keeps is the
                // one in `WeatherSettings`, per unit, where the age rules can be written down.
                // The other half of the flaky-radio case: a pooled connection that went stale
                // during a handover is replaced instead of surfacing as a failed refresh, and
                // this is separate from our own retry, which also covers a dead server.
                .retryOnConnectionFailure(true)
                .build()
    }
}

/**
 * The contact URL inside the User-Agent. Its own object so the URL allow-list in the transport
 * guard has one obvious place to look, and so the same contact is not typed twice.
 */
internal object RepoContact {
    const val URL = "https://github.com/alefinot/Dashboard-V2"
}
