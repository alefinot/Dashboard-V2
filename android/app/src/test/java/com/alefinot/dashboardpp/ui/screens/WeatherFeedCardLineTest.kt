package com.alefinot.dashboardpp.ui.screens

import com.alefinot.dashboardpp.weather.Place
import com.alefinot.dashboardpp.weather.PositionSource
import com.alefinot.dashboardpp.weather.WeatherSettings
import com.alefinot.dashboardpp.weather.WeatherStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one line the Weather Feed card shows about a feed the owner cannot see any other
 * way. The card itself is Compose and untestable here; this is the sentence it draws,
 * which is where the honest wording matters.
 */
class WeatherFeedCardLineTest {

    private val t0 = 1_762_000_000_000L

    @Test
    fun `a feed that has never reached the unit says so instead of showing nothing`() {
        assertEquals(
            "not fed yet",
            weatherStatusLine(WeatherStatus()),
        )
        assertEquals(
            "not fed yet",
            weatherStatusLine(WeatherStatus(lastPushAt = WeatherSettings.NO_EPOCH), t0),
        )
    }

    @Test
    fun `a pass in progress is shown as itself, because the switch was just pressed`() {
        assertEquals(
            "asking the weather service now...",
            weatherStatusLine(WeatherStatus(busy = true, lastPushAt = t0), t0),
        )
    }

    @Test
    fun `the line answers when, from where, and under what name`() {
        val line = weatherStatusLine(
            WeatherStatus(
                lastPushAt = t0 - 7 * 60_000L,
                source = PositionSource.FIX,
                label = "Munich",
            ),
            t0,
        )
        assertEquals("fed Munich | unit GNSS | 7 min ago", line)
    }

    @Test
    fun `the position source is named the way the owner named it in settings`() {
        assertTrue(
            weatherStatusLine(WeatherStatus(lastPushAt = t0, source = PositionSource.CITY, label = "Munich"), t0)
                .contains("typed city"),
        )
        assertTrue(
            weatherStatusLine(WeatherStatus(lastPushAt = t0, source = PositionSource.CACHE, label = "x"), t0)
                .contains("last known position"),
        )
        // A feed that knows no position at all is a feed that has nothing to show, and
        // the line must not imply that the unit reported one.
        assertTrue(
            weatherStatusLine(WeatherStatus(lastPushAt = t0), t0).contains("unknown position"),
        )
    }

    @Test
    fun `a name the providers did not supply is still a line`() {
        assertEquals(
            "fed no name | unit GNSS | 0 min ago",
            weatherStatusLine(WeatherStatus(lastPushAt = t0, source = PositionSource.FIX), t0),
        )
    }

    @Test
    fun `the saved city is shown with the administrative names that tell its twins apart`() {
        assertEquals(
            "Munich, Bavaria, Germany",
            placeLabel(Place("Munich", "Bavaria", "Germany", 48.13743, 11.57549)),
        )
        assertEquals(
            "Munich, North Dakota",
            placeLabel(Place("Munich", "North Dakota", null, 48.12, 98.4)),
        )
        assertEquals("Cairo", placeLabel(Place("Cairo", null, null, 30.0, 31.0)))
    }
}
