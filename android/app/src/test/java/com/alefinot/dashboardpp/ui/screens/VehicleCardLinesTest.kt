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

class VehicleCardLinesTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `a unit too old for Bluetooth is named as such and not as never connected`() {
        val line = cardStatusLine(
            Vehicle(id = "v", name = "Old Bench", type = VehicleType.CAR, firmwareVersion = "1.4.6"),
            null,
            1_700_553_600_000L,
        )
        assertEquals(CardTone.BAD, line.tone)
        assertTrue(line.text, line.text.contains("NO BLUETOOTH ON THIS UNIT"))
        assertFalse(line.text, line.text.contains("LAST SEEN"))
        assertFalse(line.text, line.text.contains("NEVER CONNECTED"))
    }

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
        assertEquals("LAST SEEN YESTERDAY", lastSeenLine(now - 30 * 60 * 60 * 1000, now))
        assertEquals("LAST SEEN 2 DAYS AGO", lastSeenLine(now - 2 * day - 60_000, now))
        assertEquals("LAST SEEN 6 DAYS AGO", lastSeenLine(now - 6 * day, now))
        assertEquals("LAST SEEN 20 DAYS AGO", lastSeenLine(now - 20 * day, now))
    }

    @Test
    fun `the long end of the scale counts months and then years, singular or plural`() {
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
        assertFalse(connectedCard(b, state))
        assertFalse(connectedCard(a, ConnectionUiState.Idle(null)))
    }

    @Test
    fun `a silent reconnect never animates a card, a hunt the user asked for does`() {
        assertFalse(showScanRing(ConnectionUiState.Connecting(ConnectMode.SILENT)))
        assertFalse(showScanRing(ConnectionUiState.Connecting(ConnectMode.PROBE)))
        assertTrue(showScanRing(ConnectionUiState.Connecting(ConnectMode.SEARCH)))
        assertFalse(showScanRing(ConnectionUiState.Connected("3C:71:0A:00:00:50", "n", "1.4.6", null, "a")))
        assertFalse(showScanRing(null))
    }

    @Test
    fun `every tone maps to a colour, and no two tones share one`() {
        val colors = CardTone.values().map { statusColor(it) }
        assertEquals(CardTone.values().size, colors.distinct().size)
    }
}
