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
    /** The chart series, already bounded to [TripSeries.MAX_POINTS]. */
    val series: TripSeries,
)

/**
 * The three series the detail page charts, bucketed once when the file is read.
 *
 * `speed` covers every row the drive logged; `altitude` and `economy` drop the rows that carry no
 * reading for them (`-1` and `0` respectively), which is what makes "two or more points" the
 * question of whether a chart exists at all.
 */
data class TripSeries(
    val speed: List<Double>,
    val altitude: List<Double>,
    val economy: List<Double>,
) {
    companion object {
        /**
         * Point budget for the hand-drawn charts. A bucketed 600-point polyline already exceeds
         * the pixel width of the widest phone screen, so anything past this only costs paint time;
         * the bucketing keeps peaks rather than averaging them away.
         */
        const val MAX_POINTS = 600
    }
}

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
        val speed = DoubleBuffer()
        val altitude = DoubleBuffer()
        val economy = DoubleBuffer()
        val replay = RunReplay()
        var any = false

        TripReader.forEachLine(file.absolutePath) { line ->
            if (!TripFormat.skippable(line)) {
                TripFormat.parseLine(line)?.let { sample ->
                    acc.add(sample)
                    any = true
                    val s = sample.snapshot
                    if (s.hasPosition) route.add(TrackPoint(s.lat, s.lon))
                    speed.add(s.spd)
                    if (s.alt >= 0.0) altitude.add(s.alt)
                    if (s.ikml > 0.0) economy.add(s.ikml)
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
                speed = bucketMax(speed.toArray(), TripSeries.MAX_POINTS).toList(),
                altitude = bucketMax(altitude.toArray(), TripSeries.MAX_POINTS).toList(),
                economy = bucketMax(economy.toArray(), TripSeries.MAX_POINTS).toList(),
            ),
        )
    }
}

/**
 * A growable `double[]` for the reader's series.
 *
 * The reader appends one value per row and only ever reads the result once, so a boxed
 * `ArrayList<Double>` would cost ~43 000 allocations per series for no gain. Doubling growth is
 * the same trade the driver's own track makes.
 */
private class DoubleBuffer(initial: Int = 256) {
    private var data = DoubleArray(initial)
    private var size = 0

    fun add(value: Double) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): DoubleArray = if (size == data.size) data else data.copyOf(size)
}
