package com.alefinot.dashboardpp.viewmodel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connect entry points ask permission of the reach rule before they touch the radio.
 *
 * The rule itself is [ConnectionCore.onReach] and it is unit-tested there. This file guards the
 * wiring, because the rule is worthless sitting in the core while a caller dials anyway — and
 * the bench proved what an unwired caller costs. On 2026-11-09 a tablet running an older build
 * paired with the same unit the phone could not reach, and the phone's log said why:
 *
 *     attempt start mode=SEARCH pinned=- attempts=0
 *     connect cancelled cause=StandaloneCoroutine was cancelled
 *     teardown cause=attempt cancelled
 *
 * over and over at ~250 ms. Each press of the card — which since 8eae1d57 *is* the connect
 * control, the whole card, so a impatient person puts ten clicks on it without meaning to — ran
 * `BleLink.connect()` again, and that call opens by tearing down whatever link it owns. The
 * unit's side of the same minute: `phone connected`, `auth ok`, `MTU now 517`, a few RPC frames,
 * then `phone disconnected (reason 0x13)` about every ten seconds, forever. The app was
 * hanging up on itself.
 *
 * So both doors (the card's `connectVehicle`, the shell's `huntNow`) must consult the core, and
 * must do it *before* cancelling the running attempt — a guard placed after the cancel is a
 * guard that already destroyed the thing it was protecting.
 */
class ACardPressIsAReachNotAResetTest {

    @Test
    fun connectVehicleAsksTheReachRuleBeforeItCancelsAnything() {
        val body = viewModel().substringAfter("fun connectVehicle(").substringBefore("\n    /**")
        assertTrue("connectVehicle must consult ConnectionCore.onReach", body.contains("ConnectionCore.onReach("))
        assertTrue(
            "and it must ask before cancelling the running attempt, or the cancel has already torn the link down",
            body.indexOf("ConnectionCore.onReach(") < body.indexOf("ladder?.cancel()"),
        )
        assertTrue(
            "a swallowed press is logged, so it never reads as a dead button",
            Regex("Log\\.d\\(DPP_CONN_LOG_TAG,\\s*\"connect ignored").containsMatchIn(body),
        )
    }

    @Test
    fun theShellsConnectButtonIsGuardedToo() {
        val body = viewModel().substringAfter("fun huntNow(").substringBefore("\n    /**")
        assertTrue("hunt must consult the same rule as the card", body.contains("ConnectionCore.onReach("))
        assertTrue(
            "and say so when it swallows",
            Regex("Log\\.d\\(DPP_CONN_LOG_TAG,\\s*\"hunt ignored").containsMatchIn(body),
        )
    }

    /**
     * The core cannot answer "is this the same card?" unless the running attempt remembers which
     * card it was started for. Both halves of that wiring are asserted as source: the state the
     * UI publishes carries the id, and the attempt loop records it.
     */
    @Test
    fun theRunningAttemptRemembersWhichCardItWasStartedFor() {
        val ui = read(File(sourceDir(), "UiState.kt"))
        val connecting = ui.substringAfter("data class Connecting(").substringBefore(") : ConnectionUiState")
        assertTrue("Connecting must carry the card it is reaching for", connecting.contains("vehicleId"))

        val vm = viewModel()
        assertTrue(
            "startAttempt must record the card, or a press on the same card looks like a new one",
            vm.substringAfter("private fun startAttempt(").substringBefore("\n    /**").contains("attemptVehicleId ="),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun viewModel(): String = read(File(sourceDir(), "ConnectionViewModel.kt"))

    private fun sourceDir(): File {
        val marker = "src/main/java/com/alefinot/dashboardpp/viewmodel"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (cand in listOf(File(dir, "android/app"), File(dir, "app"), dir)) {
                if (File(cand, marker).isDirectory) return File(cand, marker)
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("cannot reach the app module from " + System.getProperty("user.dir"))
    }

    private fun read(f: File): String {
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }
}
