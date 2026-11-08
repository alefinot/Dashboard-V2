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

class WeatherHttpTest {

    /** A client whose whole network is the script, injected in place of the default one. */
    private fun httpWith(vararg answers: Answer): Pair<OkHttpWeatherHttp, Network> {
        val network = Network(answers.toList())
        val client = OkHttpClient.Builder().addInterceptor(network).build()
        return OkHttpWeatherHttp(OkHttpWeatherHttp.userAgentFor("2.0.2"), client) to network
    }

    @Test
    fun `the blocking call never runs on the thread that asked`() {
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
        for (code in listOf(400, 403, 404, 429, 500, 503)) {
            val (http, network) = httpWith(Answer(code = code, body = "<html>error</html>"))
            assertNull("$code must read as no answer", runBlocking { http.get(FORECAST_URL) })
            assertEquals("$code must not be retried", 1, network.asked.size)
        }
    }

    @Test
    fun `an empty body answers null`() {
        val (http, network) = httpWith(Answer(body = ""))
        assertNull(runBlocking { http.get(FORECAST_URL) })
        assertEquals(1, network.asked.size)
    }

    @Test
    fun `an IOException is retried once after two seconds and then answers null`() {
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
        val named = OkHttpWeatherHttp.userAgentFor("2.0.2")
        assertTrue(named, named.startsWith("Dashboardpp-Android/2.0.2"))
        assertTrue(named, named.contains(RepoContact.URL))
        val unnamed = OkHttpWeatherHttp.userAgentFor(null)
        assertTrue(unnamed, unnamed.startsWith("Dashboardpp-Android/dev"))
    }

    @Test
    fun `the default client is built for a phone on a cell connection`() {
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

private class Network(private val answers: List<Answer>) : Interceptor {
    val asked = mutableListOf<Request>()
    val threads = mutableListOf<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        asked += request
        threads += Thread.currentThread().name
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
