package com.alefinot.dashboardpp.viewmodel

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.alefinot.dashboardpp.storage.AppRoot
import com.alefinot.dashboardpp.trips.CachedSummary
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.trips.TripExport
import com.alefinot.dashboardpp.trips.TripFormat
import com.alefinot.dashboardpp.trips.TripIndex
import com.alefinot.dashboardpp.trips.TripReader
import com.alefinot.dashboardpp.trips.TripStore
import com.alefinot.dashboardpp.trips.TripSummary
import com.alefinot.dashboardpp.trips.TripTag
import com.alefinot.dashboardpp.trips.TripTagReader
import com.alefinot.dashboardpp.vehicles.VehicleStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One row in the Trips list. `summary` is null until it has been computed. */
data class TripRow(
    val file: File,
    val startMs: Long,
    val sizeBytes: Long,
    val summary: TripSummary?,
    /** Who the file says the drive belongs to, from its `#vehicle=` line. */
    val vehicleId: String? = null,
    val vehicleName: String? = null,
    /** The eFuse MAC that dashboard reported, when the file recorded one. */
    val eff: String? = null,
    /** Is that id still a card in the garage? False for a file whose card was deleted. */
    val inGarage: Boolean = false,
)

/** One chip in the Trips filter row. */
data class TripFilterOption(val value: String, val label: String)

/** Everything the detail screen draws. */
data class TripDetail(
    val file: File,
    val summary: TripSummary,
    val samples: List<com.alefinot.dashboardpp.trips.TripSample>,
)

class TripsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = TripStore(app)
    private val index = TripIndex(app)
    private val export = TripExport(app)
    private val settings = RecorderSettings(app)
    private val vehicles = VehicleStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _rows = MutableStateFlow<List<TripRow>>(emptyList())
    val rows: StateFlow<List<TripRow>> = _rows

    private val _filter = MutableStateFlow(RecorderSettings.FILTER_ALL)
    val filter: StateFlow<String> = _filter

    private val _options = MutableStateFlow<List<TripFilterOption>>(emptyList())
    val filterOptions: StateFlow<List<TripFilterOption>> = _options

    private val _visible = MutableStateFlow<List<TripRow>>(emptyList())
    val visibleRows: StateFlow<List<TripRow>> = _visible

    init {
        scope.launch {
            try {
                _filter.value = settings.tripFilter.first()
            } catch (e: Exception) {
                // An unreadable preference means no filter, which is the default.
            }
        }
        scope.launch {
            combine(_rows, _filter) { r, f -> applyFilter(r, f) }.collect { _visible.value = it }
        }
    }

    /** ALL, a vehicle id, or UNKNOWN. Remembered, so the list opens as you left it. */
    fun setFilter(value: String) {
        if (value == _filter.value) return
        _filter.value = value
        scope.launch {
            try {
                settings.setTripFilter(value)
            } catch (e: Exception) {
                // Worst case the filter is not remembered; the list still works.
            }
        }
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _storageBytes = MutableStateFlow(0L)

    val storageBytes: StateFlow<Long> = _storageBytes

    private val _shared = MutableStateFlow(false)

    val shared: StateFlow<Boolean> = _shared

    private val _elsewhere = MutableStateFlow<Elsewhere?>(null)

    val elsewhere: StateFlow<Elsewhere?> = _elsewhere

    /** How many drives live in a folder other than the one in use, and that folder's path. */
    data class Elsewhere(val count: Int, val path: String)

    data class TripCounts(val here: Int, val elsewhere: Elsewhere?)

    private val _tripCounts = MutableStateFlow(TripCounts(0, null))

    /** The split above, as of the last index pass; see [countsFor]. */
    val tripCounts: StateFlow<TripCounts> = _tripCounts

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            _busy.value = true
            val files = store.trips()
            val cached = try {
                index.cached().first()
            } catch (e: Exception) {
                emptyMap()
            }
            val names = try {
                vehicles.all().first().associate { it.id to it.name }
            } catch (e: Exception) {
                emptyMap()
            }
            val rows = files.map { f ->
                val hit = cached[f.absolutePath]
                val fresh = hit != null && TripIndex.fresh(f, hit)
                val tag = tagOf(f, hit, fresh)
                if (fresh) {
                    // A row the cache already agrees with keeps its summary.
                    index.reseat(f, hit!!, tag)
                }
                TripRow(
                    f,
                    TripStore.startMsOf(f.name) ?: f.lastModified(),
                    f.length(),
                    if (fresh) hit!!.summary else null,
                    vehicleId = tag?.vehicleId,
                    vehicleName = tag?.let { names[it.vehicleId] ?: it.name.ifBlank { null } },
                    eff = tag?.eff?.takeIf { it.isNotBlank() && it != TripFormat.UNKNOWN_EFF },
                    inGarage = tag?.vehicleId?.let { names.containsKey(it) } ?: false,
                )
            }
            _rows.value = rows
            _options.value = filterOptionsFor(rows, names, _filter.value)
            if (_filter.value != RecorderSettings.FILTER_ALL &&
                _filter.value != RecorderSettings.FILTER_UNKNOWN &&
                rows.none { it.vehicleId == _filter.value }
            ) {
                _filter.value = RecorderSettings.FILTER_ALL
            }
            _storageBytes.value = files.sumOf { it.length() }
            _shared.value = AppRoot.isSharedAvailable(getApplication())
            val counts = countsFor(files, store.dir())
            _tripCounts.value = counts
            _elsewhere.value = counts.elsewhere
            index.retain(files.map { it.absolutePath }.toSet())
            _busy.value = false
            rows.filter { it.summary == null }.forEach { row -> summarise(row) }
        }
    }

    private fun tagOf(file: File, hit: CachedSummary?, fresh: Boolean): TripTag? {
        if (hit != null && fresh && hit.tagKnown) return hit.tag
        return TripTagReader.read(file)
    }

    companion object {
        /** Which rows survive the filter. A vehicle id matches its own drives only. */
        fun applyFilter(rows: List<TripRow>, filter: String): List<TripRow> = when {
            filter == RecorderSettings.FILTER_ALL -> rows
            filter == RecorderSettings.FILTER_UNKNOWN -> rows.filter { it.vehicleId == null }
            else -> rows.filter { it.vehicleId == filter }
        }

        internal fun filterOptionsFor(
            rows: List<TripRow>,
            names: Map<String, String>,
            activeFilter: String = RecorderSettings.FILTER_ALL,
        ): List<TripFilterOption> {
            val ids = rows.mapNotNull { it.vehicleId }.distinct().toMutableList()
            var unknown = rows.any { it.vehicleId == null }
            if (activeFilter != RecorderSettings.FILTER_ALL &&
                applyFilter(rows, activeFilter).size < rows.size
            ) {
                if (activeFilter == RecorderSettings.FILTER_UNKNOWN) unknown = true
                else if (!ids.contains(activeFilter)) ids.add(activeFilter)
            }
            if (ids.size + (if (unknown) 1 else 0) < 2) return emptyList()
            val out = ArrayList<TripFilterOption>()
            out.add(TripFilterOption(RecorderSettings.FILTER_ALL, "ALL"))
            ids.forEach { id -> out.add(TripFilterOption(id, names[id] ?: NO_CARD_LABEL)) }
            if (unknown) out.add(TripFilterOption(RecorderSettings.FILTER_UNKNOWN, "UNKNOWN"))
            return out
        }

        /** A vehicle named in a file that no longer has a card in the garage. */
        private const val NO_CARD_LABEL = "(no card)"

        /** Exposed for the settings screen's "open the folder" affordance. */
        fun tripsDir(app: Application): File = TripStore(app).dir()

        /** The path of the folder a file sits in, resolved the way the comparison needs it. */
        private fun parentOf(f: File): String = f.parentFile?.let { p ->
            runCatching { p.canonicalPath }.getOrDefault(p.absolutePath)
        } ?: ""

        fun countsFor(files: List<File>, writingTo: File): TripCounts {
            val here = runCatching { writingTo.canonicalPath }.getOrDefault(writingTo.absolutePath)
            val strays = files.filter { parentOf(it) != here }
            return TripCounts(
                here = files.size - strays.size,
                elsewhere = strays.firstOrNull()?.let {
                    Elsewhere(strays.size, it.parentFile?.absolutePath ?: "")
                },
            )
        }

        fun elsewhereFor(files: List<File>, writingTo: File): Elsewhere? =
            countsFor(files, writingTo).elsewhere
    }

    private fun summarise(row: TripRow) {
        scope.launch(Dispatchers.IO) {
            val summary = TripReader.summarize(row.file.absolutePath) ?: return@launch
            try {
                index.put(
                    row.file.absolutePath,
                    row.file.lastModified(),
                    row.file.length(),
                    summary,
                    TripTagReader.read(row.file),
                )
            } catch (e: Exception) {
                // The cache is an optimisation; failing it costs only the re-read.
            }
            _rows.value = _rows.value.map {
                if (it.file == row.file) it.copy(summary = summary) else it
            }
        }
    }

    fun detail(row: TripRow): TripDetail? {
        val summary = row.summary ?: TripReader.summarize(row.file.absolutePath) ?: return null
        return TripDetail(row.file, summary, TripReader.samples(row.file.absolutePath))
    }

    fun delete(row: TripRow) {
        scope.launch(Dispatchers.IO) {
            val deleting = row.file.absolutePath == openTripPath()
            if (deleting) {
                _message.value = "CAN'T DELETE THE TRIP BEING RECORDED"
                return@launch
            }
            row.file.delete()
            index.retain(store.trips().map { it.absolutePath }.toSet())
            _message.value = null
            refresh()
        }
    }

    fun deleteAll() {
        scope.launch(Dispatchers.IO) {
            val open = openTripPath()
            var blocked = false
            store.trips().forEach { f ->
                if (f.absolutePath == open) blocked = true else f.delete()
            }
            try {
                index.retain(emptySet())
            } catch (e: Exception) {
                // ignore
            }
            _message.value = if (blocked) "KEPT THE TRIP BEING RECORDED" else null
            refresh()
        }
    }

    /** The file the recorder is writing, if any — it must never be deleted. */
    private fun openTripPath(): String? =
        com.alefinot.dashboardpp.trips.TripRuntime.state.value.takeIf { it.recording }?.tripPath

    fun shareCsv(row: TripRow): Intent = export.shareIntent(row.file, "text/csv")

    fun shareGpx(row: TripRow): Intent {
        export.prune()
        return export.shareIntent(export.gpx(row.file), "application/gpx+xml")
    }

    fun consumeMessage() {
        _message.value = null
    }
}
