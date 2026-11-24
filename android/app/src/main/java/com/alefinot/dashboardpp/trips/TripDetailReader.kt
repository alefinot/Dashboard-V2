package com.alefinot.dashboardpp.trips

import java.io.File

/**
 * Everything one recorded drive hands its detail page, built in a single pass over its file.
 *
 * The page used to ask for three things in three different ways: a summary (from the cache, or a
 * full scan when the cache had none), every sample (a second full parse), and the runs (a third
 * walk over those samples). A twelve-hour drive is ~43 000 rows, and `parseLine` builds ~25
 * substrings per row, so opening one cost two parses of the whole file before the first frame and
 * kept ~43 000 `TripSample`s alive for as long as the page was on screen.
 *
 * This is the same drive in one walk: the summary accumulator, the route, the three chart series
 * and the runs all read the row while it is already in hand, and nothing but the finished picture
 * survives. The file is re-read on every open — by design, so a drive deleted or replaced on disk
 * can never be shown from a stale copy (maintainer, 2026-11-24).
 */
data class TripDetail(
    val file: File,
    val summary: TripSummary,
    /**
     * The drive's line at every quality the page may draw, finest first. See [RouteThinning] for
     * why the page cannot simply hand the map every recorded position.
     */
    val routes: List<List<TrackPoint>>,
    /**
     * The drive's **ten quickest** launches, oldest first (see [RunSelection.QUICKEST]).
     *
     * Computed here, once per file open, rather than in the screen: the screen recomposes for every
     * tap and every style pick, and a replay of a 43 k-row file has no business running under a
     * finger. The list's identity is what the map's overlay guard runs on, and this is the one
     * place that can promise it.
     */
    val runs: List<AccelRun>,
    /** How many launches the file held; [runs] is at most ten of them. */
    val runsSeen: Int,
    /**
     * The drive's chart channels, **as logged**: values in file order, each with the whole second of
     * the drive it was taken at. See [TripSeries].
     */
    val series: TripSeries,
)

/**
 * The three channels the detail page's trend chart can draw.
 *
 * `speed` covers every row the drive logged; `altitude` and `economy` hold only the rows that
 * carried a reading for them (`alt >= 0` and `ikml > 0` respectively) — which is why each channel
 * carries its **own** seconds array: on the old shared index axis, drawing a time label under
 * ALTITUDE would have been a lie, since dropped rows silently shifted everything after them.
 *
 * Raw on purpose. Until 2026-11-24 the reader collapsed each series to 600 points here, which
 * bounded memory and made zooming impossible: the page had one fixed-resolution copy of the drive
 * and could not show it finer than the resolution it had thrown away. Reduction now happens per
 * view, in [com.alefinot.dashboardpp.trips.trendEnvelope], where the plot's width and the current
 * window are known. What it costs is ~8 bytes per sample while the page is open (a twelve-hour
 * drive is ~43 000 samples ≈ 345 KB a channel) against the peak the parse already paid.
 */
class TripSeries(
    val speed: TrendChannel,
    val altitude: TrendChannel,
    val economy: TrendChannel,
)

/** Reading a sealed (or still-open) trip file into everything its detail page draws. */
object TripDetailReader {

    /**
     * One pass over [file].
     *
     * @param summary the list's cached summary, used only when the file yields no readable row at
     * all (storage revoked mid-open, a file truncated to nothing). Without it the page says
     * `TRIP UNREADABLE`, which is the truth about a file it could not read a single row of.
     * @return null when there is nothing at all to show.
     */
    fun read(
        file: File,
        summary: TripSummary? = null,
        budgets: IntArray = RouteThinning.BUDGETS,
    ): TripDetail? {
        val acc = TripAccumulator()
        val route = ArrayList<TrackPoint>(4_096)
        val speed = SeriesBuffer()
        val altitude = SeriesBuffer()
        val economy = SeriesBuffer()
        val replay = RunReplay()
        var any = false

        TripReader.forEachLine(file.absolutePath) { line ->
            if (!TripFormat.skippable(line)) {
                TripFormat.parseLine(line)?.let { sample ->
                    acc.add(sample)
                    any = true
                    val s = sample.snapshot
                    if (s.hasPosition) route.add(TrackPoint(s.lat, s.lon))
                    speed.add(s.spd, sample.tsMs)
                    if (s.alt >= 0.0) altitude.add(s.alt, sample.tsMs)
                    if (s.ikml > 0.0) economy.add(s.ikml, sample.tsMs)
                    // Streamed rather than collected: the replay only ever needs the row in front
                    // of it, and a 43 k-row file has no reason to stay in memory for its runs.
                    replay.row(sample)
                }
            }
        }

        val headline = if (any) acc.summary() else summary
        if (headline == null) return null
        return TripDetail(
            file = file,
            summary = headline,
            routes = if (any) RouteThinning.ladder(route, budgets) else listOf(emptyList()),
            runs = replay.runs(),
            runsSeen = replay.seen,
            series = TripSeries(
                speed = speed.channel(),
                altitude = altitude.channel(),
                economy = economy.channel(),
            ),
        )
    }
}

/**
 * A growable value+second pair for one chart channel.
 *
 * The reader appends one value per row and only ever reads the result once, so a boxed
 * `ArrayList<Pair<Float, Int>>` would cost ~43 000 allocations for no gain. Doubling growth is the
 * same trade the driver's own track makes. `Float`/`Int` rather than `Double`/`Long`: these arrays
 * exist only to be drawn, and their resolution is a pixel and a second.
 */
private class SeriesBuffer(initial: Int = 256) {
    private var values = FloatArray(initial)
    private var times = IntArray(initial)
    private var size = 0
    private var started = false
    private var startMs = 0L
    private var lastSec = 0

    fun add(value: Double, tsMs: Long) {
        if (size == values.size) {
            values = values.copyOf(values.size * 2)
            times = times.copyOf(values.size)
        }
        if (!started) {
            started = true
            startMs = tsMs
        }
        // Whole seconds from the drive's start, never going backwards: `trendEnvelope` binary
        // searches this array, so a phone clock that stepped back mid-drive (an NTP correction)
        // would otherwise hide a sample in a window it does not belong to.
        val sec = maxOf(lastSec, ((tsMs - startMs) / 1000L).toInt())
        values[size] = value.toFloat()
        times[size] = sec
        lastSec = sec
        size++
    }

    /** The finished channel; trims the doubling slack off the end. */
    fun channel(): TrendChannel =
        if (size == values.size) {
            TrendChannel(values, times)
        } else {
            TrendChannel(values.copyOf(size), times.copyOf(size))
        }
}
