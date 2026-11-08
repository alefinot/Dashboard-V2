package com.alefinot.dashboardpp.trips

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

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
