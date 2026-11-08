package com.alefinot.dashboardpp.trips

import android.content.Context
import com.alefinot.dashboardpp.storage.AppRoot
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TripStore(context: Context) {
    private val appContext = context.applicationContext

    /** The trips folder the recorder writes to: the root the grant points at today. */
    fun dir(): File = AppRoot.tripsDir(appContext)

    fun dirs(): List<File> {
        val shared = AppRoot.tripsDir(appContext)
        val private = AppRoot.privateTripsDir(appContext)
        return listOf(shared, private).distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    /** Newest first, as the Trips list wants them; a name in both roots is listed once. */
    fun trips(): List<File> {
        val seen = HashSet<String>()
        return dirs()
            .flatMap { d ->
                d.listFiles { f -> f.isFile && f.name.startsWith("trip-") && f.name.endsWith(".csv") }
                    ?.asList() ?: emptyList()
            }
            .filter { seen.add(it.name) }
            .sortedByDescending { startMsOf(it.name) ?: it.lastModified() }
    }

    /** Clears every folder the list shows, and reports how many files went. */
    fun deleteAll(): Int {
        val files = trips()
        files.forEach { it.delete() }
        return files.size
    }

    /** `trip-YYYYMMDD-HHMMSS-<seq>.csv`, the seq breaking ties within a second. */
    fun newName(startMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startMs))
        val dir = dir()
        return File(dir, uniqueName(stamp) { File(dir, it).exists() }).absolutePath
    }

    companion object {
        fun uniqueName(stamp: String, exists: (String) -> Boolean): String {
            var seq = 1
            while (true) {
                val candidate = "trip-$stamp-" + String.format(Locale.US, "%03d.csv", seq)
                if (!exists(candidate)) return candidate
                seq++
            }
        }

        fun startMsOf(name: String): Long? {
            val m = Regex("^trip-(\\d{8})-(\\d{6})-\\d{3}\\.csv$").find(name) ?: return null
            val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            format.isLenient = false
            return try {
                format.parse("${m.groupValues[1]}-${m.groupValues[2]}")?.time
            } catch (e: ParseException) {
                null // impossible dates (month 13, second 61) are not ours
            }
        }
    }
}

interface TripWriter {
    fun create(path: String, tag: String)

    /** Continue an existing file — no second header. */
    fun reopen(path: String)

    fun append(path: String, line: String)

    /** Flush and close. The file stays valid; sealing is not a format event. */
    fun seal(path: String)

    /** Existing data lines, used to seed the running summary on a re-open. */
    fun readDataLines(path: String): List<String>

    fun exists(path: String): Boolean
}

/** The real implementation: one append-mode writer per open trip file. */
class CsvTripWriter(private val store: TripStore) : TripWriter {
    private val open = HashMap<String, BufferedWriter>()

    override fun create(path: String, tag: String) {
        closeOf(path)
        val f = File(path)
        f.parentFile?.mkdirs()
        val w = BufferedWriter(OutputStreamWriter(FileOutputStream(f), Charsets.UTF_8))
        TripFormat.fileHead(tag).forEach { line ->
            w.write(line)
            w.newLine()
        }
        w.flush()
        open[path] = w
    }

    override fun reopen(path: String) {
        if (open.containsKey(path)) return
        val f = File(path)
        if (!f.exists()) {
            create(path, "")
            return
        }
        val w = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
        open[path] = w
    }

    override fun append(path: String, line: String) {
        val w = open[path] ?: return
        w.write(line)
        w.newLine()
        w.flush()
    }

    override fun seal(path: String) = closeOf(path)

    private fun closeOf(path: String) {
        open.remove(path)?.run {
            try {
                flush()
                close()
            } catch (e: Exception) {
            }
        }
    }

    override fun readDataLines(path: String): List<String> {
        val f = File(path)
        if (!f.exists()) return emptyList()
        return try {
            val out = ArrayList<String>()
            f.forEachLine { line ->
                if (!TripFormat.skippable(line)) out.add(line)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun exists(path: String): Boolean = File(path).exists()

    fun closeAll() = open.keys.toList().forEach { closeOf(it) }
}

object TripTagReader {
    /** Line 1 of a trip file: its tag, or null when it has none (or cannot be read). */
    fun read(file: File): TripTag? {
        if (!file.exists()) return null
        return try {
            file.bufferedReader(Charsets.UTF_8).use { r -> TripFormat.parseTag(r.readLine() ?: "") }
        } catch (e: Exception) {
            null
        }
    }
}
