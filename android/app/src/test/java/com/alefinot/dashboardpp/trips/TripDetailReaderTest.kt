package com.alefinot.dashboardpp.trips

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One reading of a recorded drive: the headline, the route, the charts and the runs, in a single
 * pass over the file — and no samples kept afterwards.
 *
 * The page used to parse the same file twice before its first frame (once for the summary, once for
 * the samples) and then walk the samples a third time for the runs', chart's and route's sake. A
 * twelve-hour drive is ~43 000 rows, so that was 86 000 parses and ~43 000 live samples for a page
 * the owner opens to look at a map. These tests are the contract of the replacement: the numbers are
 * the same ones the old path produced, the picture is bounded, and nothing is cached between opens.
 */
class TripDetailReaderTest {

    private val files = mutableListOf<File>()

    @After
    fun cleanUp() {
        files.forEach { it.delete() }
    }

    /**
     * A trip file with a tag, a header and [rows] in order — the shape the recorder writes and
     * [TripFormat] reads, so the reader is exercised on the same bytes a phone would find.
     */
    private fun tripFile(rows: List<TripSample>): File {
        val f = File.createTempFile("dpp-trip-", ".csv")
        files.add(f)
        f.bufferedWriter().use { w ->
            w.write(TripFormat.tagLine("veh-1", "AA:BB:CC:DD:EE:FF", "Golf") + "\n")
            w.write(TripFormat.HEADER + "\n")
            for (row in rows) w.write(TripFormat.line(row) + "\n")
        }
        return f
    }

    private fun row(
        second: Int,
        spd: Double = 40.0,
        alt: Double = 120.0,
        ikml: Double = 12.0,
        fix: Int = 1,
    ) = TripSample(
        tsMs = 1_700_000_000_000L + second * 1_000L,
        snapshot = snap(
            up = 500L + second,
            spd = spd,
            odo = 1_000.0 + second * 0.01,
            trpKm = 1.0 + second * 0.01,
            trpL = 0.5 + second * 0.005,
            alt = alt,
            lat = if (fix == 0) 0.0 else 45.0 + second * 0.0001,
            lon = if (fix == 0) 0.0 else 9.0,
            fix = fix,
        ).copy(ikml = ikml, tmrSt = 0, tmrS = 0.0),
    )

    @Test
    fun onePassAnswersWithTheHeadlineTheRouteAndTheCharts() {
        val rows = (0 until 30).map { i ->
            row(
                second = i,
                spd = 20.0 + i,
                alt = if (i < 25) 100.0 + i else -1.0,
                ikml = if (i % 2 == 0) 12.0 else 0.0,
            )
        }

        val detail = TripDetailReader.read(tripFile(rows))

        assertNotNull(detail)
        detail!!
        assertEquals(30, detail.summary.samples)
        assertEquals(34.5, detail.summary.avgSpeed, 0.001)
        assertEquals(49.0, detail.summary.maxSpeed, 0.0)
        assertEquals(0.29, detail.summary.distanceKm, 1e-9)

        assertTrue("the drive's line is missing", detail.routes.isNotEmpty())
        assertEquals(rows.first().snapshot.lat, detail.routes[0].first().lat, 0.0)
        assertEquals(rows.last().snapshot.lon, detail.routes[0].last().lon, 0.0)

        // Every row the drive logged carries a speed; only the rows with a reading carry the other
        // two — `-1` and `0` are "no reading", which is what decides whether a chart exists at all.
        assertEquals(30, detail.series.speed.size)
        assertEquals(25, detail.series.altitude.size)
        assertEquals(15, detail.series.economy.size)
        assertTrue(detail.series.speed.size <= TripSeries.MAX_POINTS)
        assertEquals(49.0, detail.series.speed.max(), 0.0)
        assertEquals(124.0, detail.series.altitude.max(), 0.0)
    }

    @Test
    fun aRowWithoutAFixStillCountsButDrawsNothing() {
        val rows = listOf(row(0), row(1, fix = 0), row(2))

        val detail = TripDetailReader.read(tripFile(rows))!!

        // The row happened: it is in the summary and it has a speed on the chart.
        assertEquals(3, detail.summary.samples)
        assertEquals(3, detail.series.speed.size)
        // It happened somewhere the unit could not say, so the line must not invent a ground:
        // the two fixed positions are the whole route.
        assertEquals(2, detail.routes[0].size)
        assertEquals(45.0, detail.routes[0].first().lat, 0.0)
        assertEquals(45.0 + 2 * 0.0001, detail.routes[0].last().lat, 0.0)
    }

    @Test
    fun aDriveWithMoreLaunchesThanTheArchiveKeepsComesBackWithItsTenQuickest() {
        // Twelve launches in file order, slowest first: 14 s down to 3 s. The archive keeps the
        // ten quickest (12 s .. 3 s), which is not the ten most recent-looking rows.
        val rows = ArrayList<TripSample>()
        for (launch in 0 until 12) {
            val seconds = 14.0 - launch
            rows.add(
                row(launch * 2).copy(
                    snapshot = row(launch * 2).snapshot.copy(tmrSt = 1, tmrS = 1.0, spd = 20.0),
                ),
            )
            rows.add(
                row(launch * 2 + 1).copy(
                    snapshot = row(launch * 2 + 1).snapshot.copy(tmrSt = 2, tmrS = seconds, spd = 55.0),
                ),
            )
        }

        val detail = TripDetailReader.read(tripFile(rows))!!

        assertEquals(12, detail.runsSeen)
        assertEquals(10, detail.runs.size)
        // Oldest first, and the two that were dropped are the two slowest — the launch that
        // started last is the quickest and is still on the list.
        assertEquals((3..12).reversed().map { it.toDouble() }, detail.runs.map { it.seconds })
        assertEquals((3L..12L).toList(), detail.runs.map { it.id })
        assertEquals(3.0, detail.runs.last().seconds, 0.0)
        assertTrue("a run lost its ground", detail.runs.all { it.points.size >= 2 })
    }

    @Test
    fun aFileWithNoReadableRowIsUnreadableUnlessTheListAlreadyKnewTheDrive() {
        val empty = tripFile(emptyList())

        assertNull(
            "an empty file with no cached summary claimed a drive existed",
            TripDetailReader.read(empty),
        )

        val cached = TripSummary(
            startMs = 1_700_000_000_000L,
            endMs = 1_700_000_600_000L,
            samples = 600,
            durationMs = 600_000L,
            distanceKm = 4.2,
            espTripKm = 4.2,
            gnssKm = 4.1,
            maxSpeed = 61.0,
            avgSpeed = 38.0,
            fuelUsedL = 0.4,
            economyKml = 10.5,
            minVolts = 12.1,
            maxVolts = 14.2,
            minTemp = 88.0,
            maxTemp = 91.0,
            fixPct = 99,
            gaps = 0,
            gapSeconds = 0,
        )
        val detail = TripDetailReader.read(empty, cached)

        assertNotNull("the list's own numbers were thrown away with the unreadable rows", detail)
        detail!!
        assertEquals(cached, detail.summary)
        assertEquals(listOf(emptyList<TrackPoint>()), detail.routes)
        assertTrue(detail.runs.isEmpty())
        assertEquals(0, detail.runsSeen)
        assertTrue(detail.series.speed.isEmpty())
        assertTrue(detail.series.altitude.isEmpty())
        assertTrue(detail.series.economy.isEmpty())
    }

    @Test
    fun everyOpenReReadsTheFile() {
        val file = tripFile(listOf(row(0)))
        assertEquals(1, TripDetailReader.read(file)!!.summary.samples)

        file.appendText(TripFormat.line(row(1)) + "\n")

        // No cache: a drive that grew (or was replaced) on disk is shown as it is now, never as it
        // was when it was last opened.
        assertEquals(2, TripDetailReader.read(file)!!.summary.samples)
    }

    @Test
    fun theRouteIsBoundedToWhatThePhoneCanPaint() {
        // 8 000 positions, every one of them a real corner: no 2 m pass can call them redundant, so
        // the ladder's budget is the only thing keeping the map's per-draw cost down.
        val rows = (0 until 8_000).map { i ->
            row(i, fix = 1).copy(
                snapshot = row(i, fix = 1).snapshot.copy(
                    lat = 45.0 + if (i % 2 == 0) 0.0 else 0.001,
                    lon = 9.0 + i * 0.00002,
                ),
            )
        }

        val detail = TripDetailReader.read(tripFile(rows))!!

        assertEquals(RouteThinning.BUDGETS.toList(), detail.routes.map { it.size })
        for ((i, rung) in detail.routes.withIndex()) {
            assertTrue(
                "rung $i has ${rung.size} points, over its ${RouteThinning.BUDGETS[i]} budget",
                rung.size <= RouteThinning.BUDGETS[i],
            )
        }
    }
}
