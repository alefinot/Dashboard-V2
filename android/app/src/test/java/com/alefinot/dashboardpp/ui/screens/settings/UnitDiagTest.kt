package com.alefinot.dashboardpp.ui.screens.settings

import com.alefinot.dashboardpp.ble.BleJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The performance monitor's whole job is to tell the truth about a unit that is running, so the
 * parsing and the formatting are tested here rather than eyeballed on a bench: the field names
 * come from `methodPerf` / `methodHealth` / `methodLive` / `methodBoot` in `src/bt_rpc.cpp`, and
 * a field this app mis-reads is a number somebody then acts on.
 *
 * The fixtures are the firmware's own format strings with plausible values, not invented shapes.
 */
class UnitDiagTest {

    /** `methodPerf`, transcribed from the `btCatf` calls in bt_rpc.cpp. */
    private val perfJson = """
        {"cpu_freq":240,"cpu_temp":52.5,"cpu_dynamic":0,"cpu_usage":38.4,
         "uptime_s":4970,"mac":"94:B5:55:F8:B8:5A","free_heap":104857,"min_free_heap":71234,
         "stack_free":{"SensorTaskCore1":4096,"GpsTaskCore0":3072,"BtTaskCore0":2048},
         "mem_saver":0,"heap_size":294912,"psram_size":0,"psram_free":0,
         "flash_total":4194304,"flash_free":131072,
         "partitions":[
           {"label":"nvs","type":1,"subtype":2,"size":20480,"addr":180224,"used":12288},
           {"label":"otadata","type":1,"subtype":0,"size":8192,"addr":118784,"used":8192},
           {"label":"app0","type":0,"subtype":16,"size":1900544,"addr":262144,"used":1544799},
           {"label":"app1","type":0,"subtype":17,"size":1900544,"addr":2162688,"used":0},
           {"label":"spiffs","type":1,"subtype":130,"size":450560,"addr":4063232,"used":0}],
         "flash_used":1565279,
         "nvs_bytes":4096,"nvs_entries_used":185,"nvs_entries_available":30,"nvs_entries_total":215,
         "fps_current":59.7,"fps_average":58.9,"fps_target":60,"refresh_ms":16,"spi_speed":60000000,
         "ambient_light":446,"resolution":"480x320",
         "ble_connected":1,"ble_mtu":517,"ble_pairing":0,"ble_state":2}
    """.trimIndent()

    private fun perf() = UnitDiag.perf(fields(perfJson))

    private fun fields(json: String): Map<String, Any?> {
        val parsed = BleJson.obj(json)
        assertNotNull("the fixture itself must parse: $json", parsed)
        return parsed!!
    }

    @Test
    fun perfCarriesTheDisplayAndCpuFiguresTheOldMonitorShowed() {
        val p = perf()
        assertNotNull(p)
        p!!
        assertEquals(59.7, p.fpsCurrent, 1e-9)
        assertEquals(58.9, p.fpsAverage, 1e-9)
        assertEquals(60, p.fpsTarget)
        assertEquals(16L, p.refreshMs)
        assertEquals("480x320", p.resolution)
        assertEquals(446, p.ambientLight)
        assertEquals(60_000_000L, p.spiHz)
        assertEquals(240, p.cpuMHz)
        assertEquals(52.5, p.cpuTempC, 1e-9)
        assertEquals(38.4, p.cpuUsagePct, 1e-9)
        assertEquals(4970L, p.uptimeS)
        assertEquals("94:B5:55:F8:B8:5A", p.mac)
    }

    @Test
    fun perfCarriesMemoryFlashAndTheSettingsBudget() {
        val p = perf()!!
        assertEquals(104857L, p.freeHeap)
        assertEquals(71234L, p.minFreeHeap)
        assertEquals(294912L, p.heapSize)
        assertEquals(4194304L, p.flashTotal)
        assertEquals(1565279L, p.flashUsed)
        assertEquals(185L, p.nvsEntriesUsed)
        assertEquals(215L, p.nvsEntriesTotal)
        // The budget that actually runs out first on these units is entries, not bytes.
        assertEquals(30L, p.nvsEntriesAvailable)
    }

    /**
     * The unit answers `ble_connected`, `mem_saver`, `cpu_dynamic` and `ble_pairing` with `%d`,
     * so they arrive as numbers, never as JSON booleans. Reading them with `as? Boolean` yields
     * null, and a monitor that reports a LINKED unit as offline is worse than no monitor at all.
     */
    @Test
    fun theUnitSendsItsFlagsAsNumbersNotBooleans() {
        val p = perf()!!
        assertTrue("a connected link must read as connected", p.bleConnected)
        assertFalse("a fixed-frequency build must not read as dynamic", p.cpuDynamic)
        assertFalse("mem saver off must read as off", p.memSaver)
        assertFalse("no pairing in progress must read as idle", p.blePairing)

        val on = UnitDiag.perf(fields(perfJson.replace("\"mem_saver\":0", "\"mem_saver\":1")))!!
        assertTrue(on.memSaver)
    }

    @Test
    fun theLinkBlockReplacesTheWifiBlockTheOldMonitorHad() {
        val p = perf()!!
        assertEquals(517, p.bleMtu)
        assertEquals(2, p.bleState)
        assertEquals("LINKED", DiagFormat.bleState(p.bleState))
        assertEquals("ADVERTISING", DiagFormat.bleState(1))
        assertEquals("OFF", DiagFormat.bleState(0))
        // An unknown code is printed as what it is, never guessed at.
        assertEquals("state 7", DiagFormat.bleState(7))
    }

    /**
     * Task stacks are reported per task and named by the firmware; a fixed list of three names
     * here would silently drop the fourth the day a task is added (and the low-water mark is
     * the number that catches a stack overflow before it happens).
     */
    @Test
    fun stackHighWaterMarksKeepTheNamesTheFirmwareGivesThem() {
        val p = perf()!!
        assertEquals(
            listOf("BtTaskCore0", "GpsTaskCore0", "SensorTaskCore1"),
            p.stacks.map { it.label }.sorted(),
        )
        assertEquals(
            mapOf("SensorTaskCore1" to 4096, "GpsTaskCore0" to 3072, "BtTaskCore0" to 2048),
            p.stacks.associate { it.label to it.wordsFree },
        )
    }

    @Test
    fun partitionsKeepTheirOrderTheirSizesAndTheirUse() {
        val p = perf()!!
        assertEquals(
            listOf("nvs", "otadata", "app0", "app1", "spiffs"),
            p.partitions.map { it.label },
        )
        val app0 = p.partitions.first { it.label == "app0" }
        assertEquals(1900544L, app0.size)
        assertEquals(1544799L, app0.used)
        assertEquals(262144L, app0.addr)
        // A slot that holds nothing is still a slot; the monitor draws it as 0, not missing.
        assertEquals(0L, p.partitions.first { it.label == "app1" }.used)
    }

    @Test
    fun aShortReplyLosesNothingItShouldNot() {
        // An older firmware, or one that ran out of the shared reply buffer mid-body, streams
        // fewer keys. The screen still has to draw what came.
        val p = UnitDiag.perf(mapOf("fps_current" to 21.5, "free_heap" to 1024.0))
        assertNotNull(p)
        p!!
        assertEquals(21.5, p.fpsCurrent, 1e-9)
        assertEquals(1024L, p.freeHeap)
        assertEquals(0, p.fpsTarget)
        assertEquals("", p.resolution)
        assertEquals(emptyList<Any>(), p.partitions)
        assertEquals(emptyList<Any>(), p.stacks)
        assertFalse(p.bleConnected)
    }

    @Test
    fun nothingAtAllIsNoReadingRatherThanAZeroReading() {
        // An empty field map means the call failed. The caller must be able to tell that from
        // a unit that is genuinely reporting 0 fps, or the screen shows a healthy-looking zero.
        assertNull(UnitDiag.perf(emptyMap()))
        assertNull(UnitDiag.health(emptyMap()))
        assertNull(UnitDiag.live(emptyMap()))
        assertNull(UnitDiag.boot(emptyMap()))
    }

    @Test
    fun healthReportsTheHighAndLowWaterMarksTheHeapCardNeeds() {
        val h = UnitDiag.health(
            fields(
                """{"heap":104857,"maxalloc":45056,"minheap":71234,"mem_saver":1,
                   "nvs_available":30,"nvs_used":185,"nvs_total":215,"uptime":4970}""",
            ),
        )
        assertNotNull(h)
        h!!
        assertEquals(104857L, h.heap)
        assertEquals(45056L, h.maxAlloc)
        assertEquals(71234L, h.minHeap)
        assertTrue(h.memSaver)
        assertEquals(185L, h.nvsUsed)
        assertEquals(215L, h.nvsTotal)
        assertEquals(4970L, h.uptimeS)
    }

    @Test
    fun liveReportsTheInputsTheHudIsDrawingRightNow() {
        val l = UnitDiag.live(
            fields(
                """{"ambient":446,"odo":1234.56,"fuel_raw":2048,"fuel_ohm":78.5,
                   "fuel_st":0,"volts":12.61,"temp":88.0}""",
            ),
        )
        assertNotNull(l)
        l!!
        assertEquals(446, l.ambient)
        assertEquals(1234.56, l.odometerKm, 1e-6)
        assertEquals(2048, l.fuelRaw)
        assertEquals(78.5, l.fuelOhm, 1e-9)
        assertEquals(0, l.fuelState)
        assertEquals(12.61, l.volts, 1e-9)
        assertEquals(88.0, l.engineTempC, 1e-9)
    }

    /**
     * `boot` is the one that answers "did it crash?": the reset reason, the boot counter, and
     * the heap figures carried across the reboot. A monitor without it cannot tell a unit that
     * has been up for two days from one that has been quietly restarting every ten minutes.
     */
    @Test
    fun bootReportsWhyTheUnitIsRunning() {
        val b = UnitDiag.boot(
            fields(
                """{"reset":"crash","bootCount":7,"storm":1,"lastRebootTag":"abort",
                   "heapAtLastReboot":9012,"minHeapLastBoot":5000,"minHeapSinceBoot":6000,
                   "maxAllocHeap":45056,"freeHeap":104857}""",
            ),
        )
        assertNotNull(b)
        b!!
        assertEquals("crash", b.resetReason)
        assertEquals(7L, b.bootCount)
        assertTrue("a boot storm must be flagged, not left to be inferred", b.storm)
        assertEquals("abort", b.lastRebootTag)
        assertEquals(9012L, b.heapAtLastReboot)
        assertEquals(5000L, b.minHeapLastBoot)
        assertEquals(6000L, b.minHeapSinceBoot)
        assertEquals(45056L, b.maxAlloc)
        assertEquals(104857L, b.freeHeap)
    }

    @Test
    fun numbersAreSaidTheWayTheOldMonitorSaidThem() {
        assertEquals("0 B", DiagFormat.bytes(0))
        assertEquals("512 B", DiagFormat.bytes(512))
        assertEquals("1.0 KB", DiagFormat.bytes(1024))
        assertEquals("102.4 KB", DiagFormat.bytes(104857))
        assertEquals("1.53 MB", DiagFormat.bytes(1600000))
        assertEquals("4.00 MB", DiagFormat.bytes(4194304))

        assertEquals("0s", DiagFormat.uptime(0))
        assertEquals("59s", DiagFormat.uptime(59))
        assertEquals("1m 0s", DiagFormat.uptime(60))
        assertEquals("1h 22m 50s", DiagFormat.uptime(4970))

        assertEquals("40.4%", DiagFormat.pct(118 * 1024, 292 * 1024))
        assertEquals("--", DiagFormat.pct(1, 0))
    }

    @Test
    fun aFailedReplyIsNotReportedAsAUnitReportingZeroes() {
        // A BLE error and a unit that genuinely has 12345 bytes free arrive at the screen the same
        // way unless this is explicit, and the second is a fact while the first is not.
        assertNull(UnitDiag.perf(emptyMap()))
        assertNull(UnitDiag.health(emptyMap()))
        assertNull(UnitDiag.live(emptyMap()))
        assertNull(UnitDiag.boot(emptyMap()))
    }

    @Test
    fun theFuelInputStateIsSaidTheWayTheFirmwareMeansIt() {
        // The bare ADC number means nothing at the bench: 4095 is either a full tank or a wire that
        // came off, and only the input state says which. Codes are the firmware's FUEL_INPUT_*.
        assertEquals("input off — nothing wired", DiagFormat.fuelState(0))
        assertEquals("in range", DiagFormat.fuelState(1))
        assertTrue(DiagFormat.fuelState(2).contains("OPEN CIRCUIT"))
        assertTrue(DiagFormat.fuelState(3).contains("SHORTED"))
        assertEquals("state 7", DiagFormat.fuelState(7))
    }

    @Test
    fun aRebootNobodyAskedForIsTheOneThingThatHasToStandOut() {
        // The labels are the firmware's own (resetReasonStr in src/bootinfo.cpp): it prints every
        // reason in the core's enum and falls back to RST_<n> for one it does not know.
        assertFalse(DiagFormat.resetLooksUnasked("POWERON"))
        assertFalse(DiagFormat.resetLooksUnasked("SW (clean restart)"))
        assertFalse(DiagFormat.resetLooksUnasked("EXT"))
        assertTrue(DiagFormat.resetLooksUnasked("PANIC (crash/abort)"))
        assertTrue(DiagFormat.resetLooksUnasked("TASK_WDT"))
        assertTrue(DiagFormat.resetLooksUnasked("INT_WDT"))
        assertTrue(DiagFormat.resetLooksUnasked("WDT (other)"))
        assertTrue(DiagFormat.resetLooksUnasked("BROWNOUT"))
        assertTrue(DiagFormat.resetLooksUnasked("PWR_GLITCH"))
        assertTrue(DiagFormat.resetLooksUnasked("CPU_LOCKUP"))
        // A reason this app has never heard of is not assumed innocent.
        assertTrue(DiagFormat.resetLooksUnasked("UNKNOWN"))
        assertTrue(DiagFormat.resetLooksUnasked("RST_17"))
    }

    @Test
    fun usedOfSomethingIsTotalMinusFree() {
        // The unit reports free heap and heap size; the bar the old page drew was the other way
        // round. Getting this backwards shows a healthy unit as nearly full.
        assertEquals(190055L, DiagFormat.usedOf(294912, 104857))
        assertEquals(0L, DiagFormat.usedOf(100, 100))
        assertEquals(0L, DiagFormat.usedOf(100, 140))
    }

    @Test
    fun theTrendsOnlyLookBackSoFarAndNoFurther() {
        val trends = PerfTrends(cap = 3)
        listOf(10.0, 4.0, 7.0, 9.0).forEach { trends.add(perfOf(fps = it, freeHeap = (it * 1000).toLong())) }
        val fps = trends.series("fps")
        assertNotNull("the window must answer for a name the screen tracks", fps)
        assertEquals(3, fps!!.count)
        assertEquals(9.0, fps!!.last, 1e-9)
        assertEquals(4.0, fps!!.min, 1e-9)
        assertEquals(9.0, fps!!.max, 1e-9)
        assertEquals((7.0 + 9.0 + 4.0) / 3.0, fps!!.average, 1e-9)

        val heap = trends.series("heap")!!
        assertEquals(4000.0, heap.min, 1e-9)
        assertEquals(9000.0, heap.max, 1e-9)
    }

    @Test
    fun everyTrendTheScreenNamesHasASeries() {
        val trends = PerfTrends(cap = 2)
        trends.add(perfOf(fps = 1.0, freeHeap = 2L))
        val want = mapOf(
            "fps" to 1.0,
            "heap" to 2.0,
            "minheap" to 1.0,
            "cpu" to 1.0,
            "cputemp" to 1.0,
        )
        for ((name, expected) in want) {
            val s = trends.series(name)
            assertNotNull("$name must have a series, the screen names it", s)
            assertEquals("$name counted one sample", 1, s!!.count)
            assertEquals("$name keeps the value it was given", expected, s.last, 1e-9)
        }
        // A name the screen invents must not answer with an empty series that draws as zeros.
        assertNull(trends.series("nonsense"))
    }

    @Test
    fun aNameTheScreenDoesNotTrackAnswersWithNullRatherThanWithZeros() {
        val trends = PerfTrends(cap = 5)
        assertNull("no samples yet is not the same as a series that reads zero", trends.series("fps"))
    }

    private fun perfOf(fps: Double, freeHeap: Long): PerfReadout = PerfReadout(
        fpsCurrent = fps,
        freeHeap = freeHeap,
        minFreeHeap = freeHeap - 1,
        cpuUsagePct = fps,
        cpuTempC = fps,
    )

    /**
     * The monitor is opened from the Tools page and closed again; a screen that only exists in
     * the source is how a feature ends up unreachable. Read from source, because the module has
     * no Compose test harness.
     */
    @Test
    fun theToolsPageOpensItAndNothingButTheLinkFeedsIt() {
        val page = moduleSource("DashboardSettingsScreen.kt")
        assertTrue(
            "the Tools page never opens the performance monitor",
            Regex("PerfMonitorScreen\\s*\\(").containsMatchIn(codeOf(page)),
        )
        assertTrue(
            "the Tools page does not name the entry where a user will look for it",
            Regex("\"PERFORMANCE MONITOR\"").containsMatchIn(page),
        )
        val screen = codeOf(moduleSource("settings/PerfMonitorScreen.kt"))
        for (banned in listOf("http://", "https://", "android.webkit", "EspApi", "HttpURLConnection")) {
            assertFalse("the monitor still reaches the unit by $banned", screen.contains(banned))
        }
        // The four methods the plan names as its sources.
        for (method in listOf("perf", "health", "live", "boot")) {
            assertTrue(
                "the monitor never asks the unit for $method",
                Regex("action\\(\\s*\"$method\"").containsMatchIn(screen),
            )
        }
    }

    /**
     * An auto-refresh loop that outlives the screen keeps spending link round trips after the
     * user has left, and a loop with no delay at all can starve the settings read it shares the
     * link with. Both are locked here: the loop is keyed to the cadence, and it waits.
     */
    @Test
    fun theAutoReadLoopIsKeyedToItsCadenceAndWaitsBetweenReads() {
        val screen = codeOf(moduleSource("settings/PerfMonitorScreen.kt"))
        assertTrue(
            "the read loop is not a LaunchedEffect keyed to the cadence",
            Regex("LaunchedEffect\\s*\\([^)]*cadence").containsMatchIn(screen),
        )
        assertTrue("the read loop never waits", Regex("delay\\s*\\(").containsMatchIn(screen))
        assertFalse(
            "an unmanaged scope means the loop survives leaving the screen",
            Regex("GlobalScope").containsMatchIn(screen),
        )
        // The wait has to be inside the loop, and the loop has to end on cancellation rather than on
        // a flag somebody remembers to flip.
        assertNotNull(
            "the auto-read loop must wait inside a loop that ends on cancellation",
            Regex("while\\s*\\([^)]*isActive[^)]*\\)[\\s\\S]{0,240}?delay\\s*\\(").find(screen),
        )
    }

    /**
     * A monitor that keeps reading after the link drops shows a frozen set of numbers as if they
     * were live. The screen has to say what it is holding: the last read, and whether the unit
     * is still there.
     */
    @Test
    fun aStaleReadIsLabelledAsStale() {
        val screen = moduleSource("settings/PerfMonitorScreen.kt")
        assertTrue("the screen does not track the link state", Regex("link\\.state").containsMatchIn(screen))
        assertTrue(
            "the screen never says when it last read the unit",
            Regex("lastRead|last read").containsMatchIn(screen),
        )
        assertTrue(
            "a failed read has to be shown, not swallowed",
            Regex("error").containsMatchIn(screen),
        )
    }

    private fun moduleSource(name: String): String {
        val f = java.io.File(screensDir(), name)
        assertTrue("cannot find $f", f.isFile)
        return f.readText()
    }

    private fun screensDir(): java.io.File =
        java.io.File(moduleDir(), "src/main/java/com/alefinot/dashboardpp/ui/screens")

    private fun moduleDir(): java.io.File {
        val marker = "src/main/java/com/alefinot/dashboardpp/ui/screens"
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir"))
        while (dir != null) {
            for (cand in listOf(
                java.io.File(dir, "android/app"),
                java.io.File(dir, "app"),
                dir,
            )) {
                if (java.io.File(cand, marker).isDirectory) return cand
            }
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "cannot reach the app module walking up from " + System.getProperty("user.dir"),
        )
    }

    private fun codeOf(src: String): String = src
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("//[^\\n]*"), " ")
}
