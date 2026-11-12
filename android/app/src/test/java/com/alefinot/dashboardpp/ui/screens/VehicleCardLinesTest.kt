package com.alefinot.dashboardpp.ui.screens

import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.vehicles.VehicleType
import com.alefinot.dashboardpp.viewmodel.ConnectMode
import com.alefinot.dashboardpp.viewmodel.ConnectionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a garage card is allowed to claim about a vehicle.
 *
 * A card is the only place the app writes a sentence about a car, and a sentence like
 * "LAST SEEN 2 DAYS AGO" next to "THIS PHONE ALREADY PAIRED WITH ANOTHER VEHICLE" is not
 * two equal pieces of text: the second is a measurement, the first is a guess about time.
 * So the wording is built here, in plain functions, where the boundary cases are testable
 * rather than visible once a year on a screen.
 *
 * The dates are counted in **UTC day boundaries**. That is a deliberate choice, not an
 * oversight: a car driven at 23:50 at home and looked at from a hotel in another zone the
 * next morning is still "yesterday", and a card that changed its story because the phone
 * moved zones would be a card reporting its own clock instead of the car.
 */
class VehicleCardLinesTest {

    private val day = 24L * 60 * 60 * 1000

    /**
     * A unit this phone once dialed, and can now never dial.
     *
     * The line is the price of deleting the Wi-Fi transport, and the card is the only
     * place left that can say it: there is no second route to offer, no "open the web
     * page instead", and no reason to dress the dead end up. `BAD` rather than `WARN`
     * because there is nothing on this phone that fixes it.
     */
    @Test
    fun `a unit too old for Bluetooth is named as such and not as never connected`() {
        val line = cardStatusLine(
            Vehicle(id = "v", name = "Old Bench", type = VehicleType.CAR, firmwareVersion = "1.4.6"),
            null,
            1_700_553_600_000L,
        )
        assertEquals(CardTone.BAD, line.tone)
        assertTrue(line.text, line.text.contains("NO BLUETOOTH ON THIS UNIT"))
        // "LAST SEEN" would be true and useless: the unit answered long enough to record a
        // version, and the reason it never answers now is the part worth printing.
        assertFalse(line.text, line.text.contains("LAST SEEN"))
        assertFalse(line.text, line.text.contains("NEVER CONNECTED"))
    }

    /**
     * The other half of the same rule: a card with **no** recorded version is not called
     * pre-Bluetooth. An unknown version is an unknown version, and the app would be
     * telling someone to go update a unit that may already be current.
     */
    @Test
    fun `a unit that never reported a version is not accused of being old`() {
        val never = cardStatusLine(
            Vehicle(id = "v", name = "New", type = VehicleType.CAR, firmwareVersion = null),
            null,
            1_700_553_600_000L,
        )
        assertEquals(CardTone.DIM, never.tone)
        assertEquals("NEVER CONNECTED", never.text)

        val met = cardStatusLine(
            Vehicle(
                id = "v", name = "Motorbike", type = VehicleType.MOTORCYCLE,
                firmwareVersion = null, lastConnectedAtMs = 1_700_553_600_000L - 86_400_000L,
            ),
            null,
            1_700_553_600_000L,
        )
        assertFalse(met.text, met.text.contains("NO BLUETOOTH"))
    }
    // 2024-10-26 13:40 UTC: mid-afternoon, so "30 hours ago" is yesterday rather
    // than two days ago by the luck of where the timestamp happened to fall.
    private val now = 1_729_950_000_000L

    // ── the spec line ────────────────────────────────────────────────────────
    @Test
    fun `the spec line names the kind, then the maker and model, then the year`() {
        val v = Vehicle(
            id = "v1", type = VehicleType.CAR, maker = "Toyota", model = "Corolla", year = "1998",
        )
        assertEquals("CAR · TOYOTA COROLLA · 1998", specLine(v))
    }

    @Test
    fun `a half-known vehicle does not leave dangling separators or blank gaps`() {
        // Every combination a user can realistically leave behind, and none of them may
        // read like a form that was half filled in.
        assertEquals("CAR", specLine(Vehicle(id = "v1")))
        assertEquals("CAR · TOYOTA", specLine(Vehicle(id = "v1", maker = "Toyota")))
        assertEquals(
            "CAR · TOYOTA COROLLA",
            specLine(Vehicle(id = "v1", maker = "Toyota", model = "Corolla")),
        )
        assertEquals("CAR · 1998", specLine(Vehicle(id = "v1", year = "1998")))
        assertEquals(
            "MOTORCYCLE · 1972",
            specLine(Vehicle(id = "v1", type = VehicleType.MOTORCYCLE, year = "1972")),
        )
        assertEquals(
            "E-BIKE · 2020",
            specLine(Vehicle(id = "v1", type = VehicleType.E_BIKE, year = "2020")),
        )
        // A custom type is the user's word, uppercased like the rest of the spec line,
        // not the OTHER label the dropdown used to choose it.
        assertEquals(
            "RICKSHAW · TOYOTA · 1972",
            specLine(
                Vehicle(
                    id = "v1", type = VehicleType.OTHER, customType = "Rickshaw",
                    maker = "Toyota", year = "1972",
                ),
            ),
        )
    }

    @Test
    fun `whitespace typed into the editor does not reach the card`() {
        assertEquals("CAR · TOYOTA", specLine(Vehicle(id = "v1", maker = "  Toyota  ")))
        assertEquals("CAR", specLine(Vehicle(id = "v1", maker = "   ", model = "", year = " ")))
    }

    // ── the plate line ───────────────────────────────────────────────────────
    @Test
    fun `a plate that is not there is no line at all`() {
        assertNull(plateLine(Vehicle(id = "v1")))
        assertNull(plateLine(Vehicle(id = "v1", plate = "   ")))
        assertEquals("B 1234 CD", plateLine(Vehicle(id = "v1", plate = " b 1234 CD ")))
    }

    // ── the notes line ───────────────────────────────────────────────────────
    @Test
    fun `a note that is not there is no line at all, and a note that is shows as written`() {
        assertNull(notesLine(Vehicle(id = "v1")))
        assertNull(notesLine(Vehicle(id = "v1", notes = "  \n  ")))
        // Verbatim, not in the app's voice: the editor gives the note no format to enforce,
        // so the card has none either - only the whitespace around it goes.
        assertEquals(
            "Chain oiled Apr '26, tyre pressure 2.2 bar",
            notesLine(Vehicle(id = "v1", notes = "  Chain oiled Apr '26, tyre pressure 2.2 bar ")),
        )
    }

    // ── how long ago the app last reached this vehicle ───────────────────────
    @Test
    fun `a vehicle the app has never connected to says so and does not invent a time`() {
        assertEquals("NEVER CONNECTED", lastSeenLine(null, now))
    }

    @Test
    fun `the recent end of the scale is the part a driver actually reads`() {
        assertEquals("LAST SEEN JUST NOW", lastSeenLine(now - 4_000, now))
        assertEquals("LAST SEEN JUST NOW", lastSeenLine(now - 59_000, now))
        // Same UTC day: a drive this morning is still "today" at lunchtime.
        assertEquals("LAST SEEN TODAY", lastSeenLine(now - 3 * 60 * 60 * 1000, now))
    }

    @Test
    fun `day boundaries are UTC and the day before is named, not counted`() {
        // 18:33 UTC minus 30 hours = the day before. Counting that as "1 days ago" is
        // both wrong English and less useful than the word.
        assertEquals("LAST SEEN YESTERDAY", lastSeenLine(now - 30 * 60 * 60 * 1000, now))
        assertEquals("LAST SEEN 2 DAYS AGO", lastSeenLine(now - 2 * day - 60_000, now))
        assertEquals("LAST SEEN 6 DAYS AGO", lastSeenLine(now - 6 * day, now))
        assertEquals("LAST SEEN 20 DAYS AGO", lastSeenLine(now - 20 * day, now))
    }

    @Test
    fun `the long end of the scale counts months and then years, singular or plural`() {
        // A card that has been sitting since last season reading "410 DAYS AGO" is asking
        // the user to do arithmetic.
        assertEquals("LAST SEEN 1 MONTH AGO", lastSeenLine(now - 35 * day, now))
        assertEquals("LAST SEEN 2 MONTHS AGO", lastSeenLine(now - 70 * day, now))
        assertEquals("LAST SEEN 1 YEAR AGO", lastSeenLine(now - 380 * day, now))
        assertEquals("LAST SEEN 3 YEARS AGO", lastSeenLine(now - 3 * 365 * day, now))
    }

    // ── what wins when several sentences are true ────────────────────────────
    @Test
    fun `a measurement always beats the derived guess about time`() {
        val v = Vehicle(id = "v1", bleAddress = "AA:BB:CC:DD:EE:01", lastConnectedAtMs = now - 2 * day)
        val reason = "ESP32 at 192.168.1.50 answered /api/perf, but that unit is not the " +
            "vehicle you selected."
        val failed = cardStatusLine(v, ConnectionUiState.Failed(reason), now)
        // Verbatim, not paraphrased: this is the exact sentence the connection code
        // produced, and the card has no business smoothing it over.
        assertEquals(reason, failed.text)
        assertEquals(CardTone.BAD, failed.tone)
    }

    @Test
    fun `a running ladder, a live session and an idle reason each own their line`() {
        val v = Vehicle(id = "v1", bleAddress = "AA:BB:CC:DD:EE:01", lastConnectedAtMs = now - day)
        val connecting = cardStatusLine(v, ConnectionUiState.Connecting(ConnectMode.SEARCH), now)
        assertEquals(ConnectionUiState.Connecting(ConnectMode.SEARCH).stageLine().uppercase(), connecting.text)
        assertEquals(CardTone.LIVE, connecting.tone)

        val connected = cardStatusLine(
            v,
            ConnectionUiState.Connected(
                address = "3C:71:0A:00:00:50", name = "Dashboard++", version = "1.4.6",
                mac = null, vehicleId = "v1",
            ),
            now,
        )
        // The line names the firmware, not the pairing. A Bluetooth address is this
        // phone's name for a bond: printing it where a driver reads their own car
        // implies it identifies the unit, and it does not.
        assertEquals("CONNECTED · V1.4.6", connected.text)
        assertEquals(CardTone.GOOD, connected.tone)

        val idle = cardStatusLine(v, ConnectionUiState.Idle("No vehicle selected yet."), now)
        assertEquals("No vehicle selected yet.", idle.text)
        assertEquals(CardTone.WARN, idle.tone)
    }

    @Test
    fun `with nothing running the card falls back to when it last answered`() {
        assertEquals(
            "LAST SEEN YESTERDAY",
            cardStatusLine(Vehicle(id = "v1", lastConnectedAtMs = now - 26 * 60 * 60 * 1000), null, now).text,
        )
        val never = cardStatusLine(Vehicle(id = "v1"), null, now)
        assertEquals("NEVER CONNECTED", never.text)
        assertEquals(CardTone.DIM, never.tone)
    }

    @Test
    fun `only the card that owns a session may offer to end it`() {
        val a = Vehicle(id = "a", name = "Corolla", bleAddress = "AA:BB:CC:DD:EE:01")
        val b = Vehicle(id = "b", name = "Van", bleAddress = "AA:BB:CC:DD:EE:02")
        val state = ConnectionUiState.Connected(
            address = "3C:71:0A:00:00:50", name = "Dashboard++", version = "1.4.6",
            mac = null, vehicleId = "a",
        )
        assertTrue(connectedCard(a, state))
        // The selection has moved to the van; the Corolla still owns the session, so it
        // keeps its DISCONNECT and the van must not offer one.
        assertFalse(connectedCard(b, state))
        assertFalse(connectedCard(a, ConnectionUiState.Idle(null)))
    }

    @Test
    fun `a silent reconnect never animates a card, a hunt the user asked for does`() {
        // The garage is the home screen: a cold start is the most-seen animation in the app,
        // and a ring that spins there would put motion in front of a page the user did not
        // ask to search. PROBE is two addresses and finishes in about a second - a ring that
        // fast is a flicker, not progress.
        assertFalse(showScanRing(ConnectionUiState.Connecting(ConnectMode.SILENT)))
        assertFalse(showScanRing(ConnectionUiState.Connecting(ConnectMode.PROBE)))
        assertTrue(showScanRing(ConnectionUiState.Connecting(ConnectMode.SEARCH)))
        assertFalse(showScanRing(ConnectionUiState.Connected("3C:71:0A:00:00:50", "n", "1.4.6", null, "a")))
        assertFalse(showScanRing(null))
    }

    @Test
    fun `every tone maps to a colour, and no two tones share one`() {
        // The status line carries its meaning in colour as much as in words, and a status
        // that is the same colour as another status is information the page stops
        // delivering. A colour-blind user or a sunlit screen is where this shows up.
        val colors = CardTone.values().map { statusColor(it) }
        assertEquals(CardTone.values().size, colors.distinct().size)
    }
}
