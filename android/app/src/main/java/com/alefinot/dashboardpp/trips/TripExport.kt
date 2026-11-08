package com.alefinot.dashboardpp.trips

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Putting a trip where the share sheet can reach it (trip recording plan §6).
 *
 * The two formats are handed over differently, and the difference is the point:
 *
 * - **The CSV is shared where it lies.** A recorded drive is the user's file, in
 *   `Documents/Dashboard++/trips/`, and the share sheet reads that file through the
 *   root declared in `res/xml/files_path.xml`. Copying it into the cache first —
 *   which this class used to do — made a second file that looked like the drive and
 *   was not it: the one in the user's folder was the original, and the one they had
 *   just emailed was a duplicate the OS is free to clear.
 * - **The GPX is built, so it has to land somewhere.** It is derived from the CSV at
 *   the moment of sharing, and a derived file belongs in `cacheDir/exports/`: scratch,
 *   no loss when the OS clears it, regenerable from the drive that was kept.
 *
 * The CSV stays the archive either way — [prune] only ever touches the cache.
 */
class TripExport(context: Context) {
    private val appContext = context.applicationContext
    private val authority = "${appContext.packageName}.fileprovider"

    private fun exportDir(): File {
        val d = File(appContext.cacheDir, "exports")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** GPX built from the trip's fixed positions. Built, hence cached: see the class doc. */
    fun gpx(trip: File): File {
        val name = trip.name.removeSuffix(".csv")
        val out = File(exportDir(), "$name.gpx")
        GpxWriter.write(out, TripReader.samples(trip.absolutePath), name)
        return out
    }

    /** Drop old exports; the cache is not the archive, the CSV in trips/ is. */
    fun prune(maxBytes: Long = 32L * 1024 * 1024) {
        val dir = exportDir()
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    /**
     * A read-only `content://` URI for [file] and the chooser that carries it. A cached GPX
     * and a CSV lying in the shared trips folder are both under a declared root, so both
     * come back the same way; a file under neither throws `IllegalArgumentException`, which
     * is a `files_path.xml` mistake rather than a runtime one.
     */
    fun shareIntent(file: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(appContext, authority, file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share trip",
        )
    }
}
