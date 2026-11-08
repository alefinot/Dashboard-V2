package com.alefinot.dashboardpp.weather

import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's one outbound HTTP seam (weather push plan, `weather/WeatherHttp.kt`).
 *
 * There is no MockWebServer on the test classpath and none is added: an application-level
 * [Interceptor] that never calls `chain.proceed` short-circuits the call inside OkHttp itself,
 * so the fake records the request the client actually built and answers it without a socket.
 * That leaves the tests pointed at the rules we own - https only, one retry after 2 s, "no
 * answer" is a normal return value - and not at TLS, DNS or redirect handling, which are
 * OkHttp's to test.
 *
 * The rule these hold is the one the weather pipeline is written against: a refresh that cannot
 * reach the sky returns `null` and the unit keeps the frame it was already showing. A `null`
 * that throws instead, or that invents a body, is how a car driving through a tunnel would end
 * up with a weather panel full of zeros.
 */
class WeatherHttpTest {

    /** A client whose whole network is the script, injected in place of the default one. */
    private fun httpWith(vararg answers: Answer): Pair<OkHttpWeatherHttp, Network> {
        val network = Network(answers.toList())
        val client = OkHttpClient.Builder().addInterceptor(network).build()
        return OkHttpWeatherHttp(OkHttpWeatherHttp.userAgentFor("2.0.2"), client) to network
    }

    @Test
    fun `the blocking call never runs on the thread that asked`() {
        // Android answers a blocking socket on the main thread with
        // NetworkOnMainThreadException - a crash in a debug build, a swallowed note in a
        // release one. Every host that wakes the weather coordinator lives on Main (the
        // ViewModel's scope, the foreground service), so this seam owns its own thread and
        // a call site never has to remember to hop.
        val callerName = "http-caller-under-test"
        val network = Network(listOf(Answer(body = """{"current":{"temperature_2m":11.5}}""")))
        val client = OkHttpClient.Builder().addInterceptor(network).build()
        val http = OkHttpWeatherHttp(OkHttpWeatherHttp.userAgentFor("2.0.2"), client)
        val caller = Executors.newSingleThreadExecutor { Thread(it, callerName) }.asCoroutineDispatcher()
        try {
            runBlocking { withContext(caller) { http.get("https://api.open-meteo.com/v1/forecast?latitude=1") } }
        } finally {
            caller.close()
        }
        assertEquals(1, network.asked.size)
        val ranOn = network.threads.single()
        org.junit.Assert.assertNotEquals("the socket ran on the caller's thread", callerName, ranOn)
        assertTrue(
            "expected an io-pool thread, got $ranOn",
            ranOn.startsWith("DefaultDispatcher-worker"),
        )
    }

    @Test
    fun `the scheme asked for is always https`() {
        // Plain http is refused before a request is committed, not after the provider answers:
        // these are third-party services, and a typed city name going out over cleartext is the
        // part worth refusing outright instead of trusting every URL builder to get it right.
        val (plain, plainNetwork) = httpWith(Answer(body = "{\"temperature_2m\":3.5}"))
        assertNull(runBlocking { plain.get("http://api.open-meteo.com/v1/forecast?latitude=1") })
        assertEquals(
            "a plain-http URL must never reach the network",
            0,
            plainNetwork.asked.size,
        )

        val (secure, secureNetwork) = httpWith(Answer())
        runBlocking { secure.get("https://geocoding-api.open-meteo.com/v1/search?name=Munich") }
        assertEquals(1, secureNetwork.asked.size)
        assertEquals("https", secureNetwork.asked.single().url.scheme)
    }

    @Test
    fun `a malformed url answers null without asking the network`() {
        // A URL this client cannot parse is a bug in the URL builder, not a transient failure;
        // retrying it would spend two provider requests to learn the same thing.
        val (http, network) = httpWith(Answer())
        assertNull(runBlocking { http.get("open-meteo without a scheme") })
        assertEquals(0, network.asked.size)
    }

    @Test
    fun `a 2xx body comes back verbatim`() {
        val body = "{\"current\":{\"temperature_2m\":-1.4,\"weather_code\":3},\"tz\":\"Europe/" +
            "Berlin\"}"
        val (http, network) = httpWith(Answer(body = body))
        assertEquals(body, runBlocking { http.get(FORECAST_URL) })
        assertEquals(1, network.asked.size)
    }

    @Test
    fun `a non-2xx answers null`() {
        // A provider that answers with an error did answer: the link is fine, the resource is
        // not there. Re-sending a 404 or a 429 two seconds later is the traffic pattern
        // Nominatim's usage policy calls faulty, so one attempt is the whole attempt.
        for (code in listOf(400, 403, 404, 429, 500, 503)) {
            val (http, network) = httpWith(Answer(code = code, body = "<html>error</html>"))
            assertNull("$code must read as no answer", runBlocking { http.get(FORECAST_URL) })
            assertEquals("$code must not be retried", 1, network.asked.size)
        }
    }

    @Test
    fun `an empty body answers null`() {
        // 200 with no bytes is not a document. Parsing it would be a guess, and the caller
        // could not tell "the provider said nothing" from "the provider said an empty object".
        val (http, network) = httpWith(Answer(body = ""))
        assertNull(runBlocking { http.get(FORECAST_URL) })
        assertEquals(1, network.asked.size)
    }

    @Test
    fun `an IOException is retried once after two seconds and then answers null`() {
        // The failure worth asking again is the radio: a cell handover drops a connection that
        // would have worked a second later. Two seconds is long enough for that and short
        // enough to stay inside one refresh cycle. This test spends the wait on purpose.
        val (http, network) = httpWith(
            Answer(ioFailure = IOException("connection reset")),
            Answer(ioFailure = IOException("still no route")),
        )
        val started = System.currentTimeMillis()
        assertNull(runBlocking { http.get(FORECAST_URL) })
        val elapsed = System.currentTimeMillis() - started
        assertEquals(
            "a transport failure is asked for exactly one more time",
            2,
            network.asked.size,
        )
        assertTrue(
            "the retry has to wait, not hammer the provider; elapsed was $elapsed ms",
            elapsed >= OkHttpWeatherHttp.RETRY_DELAY_MS,
        )
    }

    @Test
    fun `the retry is not repeated a third time`() {
        // Three failures in a row is an outage, not a hiccup, and the caller already reschedules
        // the whole feed - a seam that keeps trying only adds load to a provider that is not
        // answering. The script offers a third failure so a third attempt is possible: the count
        // is what proves it was not taken.
        val (http, network) = httpWith(
            Answer(ioFailure = IOException("timeout 1")),
            Answer(ioFailure = IOException("timeout 2")),
            Answer(ioFailure = IOException("timeout 3")),
        )
        assertNull(runBlocking { http.get(FORECAST_URL) })
        assertEquals(2, network.asked.size)
    }

    @Test
    fun `the User-Agent names this app`() {
        // Nominatim's usage policy is explicit that "stock User-Agents as set by http libraries
        // will not do". The seam sets the header on the request itself, so no caller can forget
        // it and no provider ever sees a bare `OkHttp`.
        val (http, network) = httpWith(Answer())
        runBlocking { http.get(FORECAST_URL) }
        val agent = network.asked.single().header("User-Agent")
        assertTrue("no User-Agent on the request at all", agent != null)
        assertTrue(
            "the User-Agent must name this app, not the HTTP library: $agent",
            agent!!.startsWith("Dashboardpp-Android/"),
        )
        assertTrue(
            "the User-Agent must carry a way to reach the author: $agent",
            agent.contains(RepoContact.URL),
        )
    }

    @Test
    fun `the identification string falls back when the package cannot be read`() {
        // The version comes from the installed package so it cannot drift from what is actually
        // running; a package manager that will not answer must still produce an identity, not
        // none, because a request with no identity is the one Nominatim blocks.
        val named = OkHttpWeatherHttp.userAgentFor("2.0.2")
        assertTrue(named, named.startsWith("Dashboardpp-Android/2.0.2"))
        assertTrue(named, named.contains(RepoContact.URL))
        val unnamed = OkHttpWeatherHttp.userAgentFor(null)
        assertTrue(unnamed, unnamed.startsWith("Dashboardpp-Android/dev"))
    }

    @Test
    fun `the default client is built for a phone on a cell connection`() {
        // The plan's line for this client, pinned: 10 s connect / 15 s read / 20 s call, no
        // redirects, no cache. The call timeout is the one that bounds an attempt - two phases
        // that each sit just under their own limit would otherwise add up to a refresh stuck on
        // a provider that stopped answering. Pinned here so a later "make it bigger" edit has to
        // argue for it, and so the redirect/cache posture cannot drift silently.
        val client = OkHttpWeatherHttp.defaultClient(OkHttpWeatherHttp.userAgentFor("2.0.2"))
        assertEquals(OkHttpWeatherHttp.CONNECT_TIMEOUT_MS, client.connectTimeoutMillis.toLong())
        assertEquals(OkHttpWeatherHttp.READ_TIMEOUT_MS, client.readTimeoutMillis.toLong())
        assertEquals(OkHttpWeatherHttp.WRITE_TIMEOUT_MS, client.writeTimeoutMillis.toLong())
        assertEquals(OkHttpWeatherHttp.CALL_TIMEOUT_MS, client.callTimeoutMillis.toLong())
        assertTrue(client.retryOnConnectionFailure)
        assertFalse(
            "a redirect would move the car's coordinates to a host this app did not choose",
            client.followRedirects || client.followSslRedirects,
        )
        assertNull(
            "the only weather cache is the per-unit one in WeatherSettings, where its age rules " +
                "are written down - an OkHttp cache would age them invisibly",
            client.cache,
        )
    }
}

/** One scripted outcome per attempt; `ioFailure` is thrown instead of answering. */
private class Answer(
    val code: Int = 200,
    val body: String = "{\"latitude\":48.13743}",
    val ioFailure: IOException? = null,
)

/**
 * The stand-in for the whole internet: records each request the client commits to sending, then
 * replays the script. It never calls `chain.proceed`, so no route is selected and no socket is
 * opened - which is also why the recorded request is the real one, header and scheme included.
 */
private class Network(private val answers: List<Answer>) : Interceptor {
    val asked = mutableListOf<Request>()
    val threads = mutableListOf<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        asked += request
        threads += Thread.currentThread().name
        // Clamped, not indexed: an implementation that retried more often than the script holds
        // would otherwise fail on the array bound instead of on the count assertion.
        val answer = answers[minOf(asked.size - 1, answers.lastIndex)]
        answer.ioFailure?.let { throw it }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(answer.code)
            .message(if (answer.code in 200..299) "OK" else "Not usable")
            .body(answer.body.toResponseBody(JSON))
            .build()
    }
}

/** The content type the providers answer with; irrelevant to the seam, needed to build a body. */
private val JSON: MediaType = "application/json".toMediaType()

/** A real forecast URL shape, so the tests read like the calls they describe. */
private const val FORECAST_URL =
    "https://api.open-meteo.com/v1/forecast?latitude=48.13743&longitude=11.57549" +
        "&current=temperature_2m&timezone=auto&timeformat=unixtime"
