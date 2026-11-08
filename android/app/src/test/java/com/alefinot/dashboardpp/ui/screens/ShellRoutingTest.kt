package com.alefinot.dashboardpp.ui.screens

import com.alefinot.dashboardpp.trips.TripsEnabled
import com.alefinot.dashboardpp.viewmodel.AttemptStage
import com.alefinot.dashboardpp.viewmodel.ConnectMode
import com.alefinot.dashboardpp.viewmodel.ConnectionCore
import com.alefinot.dashboardpp.viewmodel.ConnectionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellRoutingTest {

    @Test
    fun `cold start is the vehicle page with nothing connected`() {
        val view = ShellRouting.view(ConnectionUiState.Idle(null))
        assertNull(view.conn)
        assertNull(view.reason)
        assertTrue(view.findIsRetrySearch)
    }

    @Test
    fun `every state renders the same shell, and none of them gets a screen of its own`() {
        for (state in listOf(
            ConnectionUiState.Idle(null),
            ConnectionUiState.Connecting(ConnectMode.SILENT),
            ConnectionUiState.Connecting(ConnectMode.SEARCH),
            ConnectionUiState.Connecting(ConnectMode.PROBE),
            ConnectionUiState.Failed("No Dashboard++ answered."),
        )) {
            assertNull("$state", ShellRouting.view(state).conn)
        }
    }

    @Test
    fun `a running attempt says which attempt is running`() {
        val lines = ConnectMode.values().map {
            ShellRouting.view(ConnectionUiState.Connecting(it)).reason
        }
        for (line in lines) assertTrue(line.orEmpty().isNotBlank())
        assertEquals(lines.size, lines.distinct().size)
    }

    @Test
    fun `the pairing stage says what to do regardless of which mode is running`() {
        for (mode in ConnectMode.values()) {
            val line = ShellRouting.view(
                ConnectionUiState.Connecting(mode, AttemptStage.PAIR),
            ).reason!!
            assertTrue(line, line.contains("code shown on the dashboard"))
            val scan = ShellRouting.view(
                ConnectionUiState.Connecting(mode, AttemptStage.SCAN),
            ).reason!!
            assertTrue(scan, scan.contains("Scanning for dashboards"))
            // No line about a link claims a network: there is no network to claim.
            assertFalse("$mode/$line", line.contains("network"))
            assertFalse("$mode/$scan", scan.contains("network"))
        }
    }

    @Test
    fun `a connected vehicle is the shell, with a connection and no reason`() {
        val state = ConnectionUiState.Connected(
            "3C:71:0A:00:00:50", "Dashboard++", "1.4.6", "A4:CF:12:3B:9E:07", "v1", 517,
        )
        val view = ShellRouting.view(state)
        assertEquals(state, view.conn)
        assertNull(view.reason)
        assertFalse(view.findIsRetrySearch)
    }

    @Test
    fun `a ladder that ran out is the shell, without a connection, and it hands over the reason`() {
        val view = ShellRouting.view(ConnectionUiState.Failed("No Dashboard++ answered."))
        assertNull(view.conn)
        assertTrue(view.reason.orEmpty().isNotBlank())
        assertFalse(view.findIsRetrySearch)
    }

    @Test
    fun `a connection that drops mid-session keeps the shell and hands over the reason`() {
        val reason = "Lost connection to 192.168.1.116."
        val view = ShellRouting.view(ConnectionUiState.Failed(reason))
        assertNull(view.conn)
        assertEquals(reason, view.reason)
    }

    @Test
    fun `nothing being reached for is the shell too, and its FIND DEVICE retries the network`() {
        val detail = ConnectionCore.NO_VEHICLE_SELECTED
        val view = ShellRouting.view(ConnectionUiState.Idle(detail))
        assertEquals(detail, view.reason)
        assertTrue(view.findIsRetrySearch)
    }

    @Test
    fun `the recorder only runs with a device to record from`() {
        assertTrue(ShellRules.recorderWants(true, tripsAvailable = true, recordingEnabled = true))
        // The offline shell must never start the service, whatever the switches say.
        assertFalse(ShellRules.recorderWants(false, tripsAvailable = true, recordingEnabled = true))
        assertFalse(ShellRules.recorderWants(false, tripsAvailable = false, recordingEnabled = false))
        // And the two switches still mean what they meant.
        assertFalse(ShellRules.recorderWants(true, tripsAvailable = false, recordingEnabled = true))
        assertFalse(ShellRules.recorderWants(true, tripsAvailable = true, recordingEnabled = false))
    }

    @Test
    fun `the ESP slot dims when there is no device, and nothing else does`() {
        assertTrue(ShellRules.glyphDimmed(AppTab.WEB, connected = false, tripsAvailable = true))
        assertFalse(ShellRules.glyphDimmed(AppTab.WEB, connected = true, tripsAvailable = true))
        // The trips pin already dims for firmware with no trip store - unchanged.
        assertTrue(ShellRules.glyphDimmed(AppTab.TRIPS, connected = true, tripsAvailable = false))
        assertFalse(ShellRules.glyphDimmed(AppTab.TRIPS, connected = false, tripsAvailable = true))
        assertFalse(ShellRules.glyphDimmed(AppTab.LIVE, connected = false, tripsAvailable = true))
        assertFalse(ShellRules.glyphDimmed(AppTab.SETTINGS, connected = false, tripsAvailable = true))
    }

    @Test
    fun `every page is reachable offline`() {
        for (tab in listOf(AppTab.TRIPS, AppTab.LIVE, AppTab.WEB, AppTab.SETTINGS)) {
            assertTrue(ShellRules.pageReachable(tab, connected = false, tripsAvailable = true))
            assertTrue(ShellRules.pageReachable(tab, connected = true, tripsAvailable = true))
        }
        assertFalse(ShellRules.pageReachable(AppTab.TRIPS, connected = true, tripsAvailable = false))
    }

    @Test
    fun `the shell only moves someone off a page the firmware cannot serve`() {
        assertFalse(ShellRules.pageUnserved(tripsAvailable = true, connected = true))
        assertFalse(ShellRules.pageUnserved(tripsAvailable = true, connected = false))
        assertFalse(ShellRules.pageUnserved(tripsAvailable = false, connected = false))
        assertTrue(ShellRules.pageUnserved(tripsAvailable = false, connected = true))
    }

    @Test
    fun `the ESP page is warmed only while it is still unopened`() {
        assertTrue(ShellRules.shouldWarm(connected = true, seen = false, warmed = false))
        assertFalse(ShellRules.shouldWarm(connected = false, seen = false, warmed = false))
        assertFalse(ShellRules.shouldWarm(connected = true, seen = true, warmed = false))
        assertFalse(ShellRules.shouldWarm(connected = true, seen = false, warmed = true))
    }

    @Test
    fun `local history does not need a device`() {
        assertTrue(TripsEnabled.forFirmware(null))
    }
}
