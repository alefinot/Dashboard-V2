package com.alefinot.dashboardpp.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

object AppRoot {

    const val FOLDER = "Dashboard++"

    /** Sub-directory of the trip CSVs. Same name under either root. */
    const val TRIPS = "trips"

    /** Sub-directory of the vehicle card JPEGs. Same name under either root. */
    const val VEHICLES = "vehicles"

    /** The garage file. Same name under either root. */
    const val GARAGE_FILE = "vehicles.json"

    // ---- pure decision ------------------------------------------------------

    internal fun sharedRootFor(granted: Boolean, docsRoot: File?, canCreate: (File) -> Boolean): File? {
        if (!granted || docsRoot == null) return null
        val root = File(docsRoot, FOLDER)
        return if (canCreate(root)) root else null
    }

    internal fun baseFor(shared: File?, privateRoot: File): File = shared ?: privateRoot

    /** Trip CSVs under [base]. */
    internal fun tripsDirIn(base: File): File = File(base, TRIPS)

    /** Vehicle card JPEGs under [base]. */
    internal fun vehiclesDirIn(base: File): File = File(base, VEHICLES)

    /** The garage file under [base]. */
    internal fun garageFileIn(base: File): File = File(base, GARAGE_FILE)

    fun sharedRootLabel(): String = Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER

    // ---- the Android layer --------------------------------------------------

    fun isSharedAvailable(context: Context): Boolean =
        sharedRootFor(isGranted(context), documentsRoot(context), ::ensureDirOrNull) != null

    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED && usesLegacyStorage()
        }

    private fun usesLegacyStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            runCatching { Environment.isExternalStorageLegacy() }.getOrDefault(false)
        } else {
            true
        }

    enum class GrantPath {
        /** Android 11+ (API 30+): the owner can grant All files access on a settings page. */
        ALL_FILES,

        RUNTIME_PERMISSION,
    }

    fun grantPath(sdkInt: Int): GrantPath =
        if (sdkInt >= Build.VERSION_CODES.R) GrantPath.ALL_FILES else GrantPath.RUNTIME_PERMISSION

    /** The folder a drive's CSVs land in, created if it is missing. Never null. */
    fun tripsDir(context: Context): File = ensureDir(tripsDirIn(base(context)))

    fun privateTripsDir(context: Context): File = ensureDir(tripsDirIn(privateRoot(context)))

    /** The folder a card's JPEG lands in, created if it is missing. Never null. */
    fun vehiclesDir(context: Context): File = ensureDir(vehiclesDirIn(base(context)))

    /** The garage file's location. Not created: reading a missing one means an empty garage. */
    fun garageFile(context: Context): File = garageFileIn(base(context))

    @Suppress("DEPRECATION")
    private fun documentsRoot(context: Context): File? =
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        } catch (e: Exception) {
            null
        }

    private fun base(context: Context): File =
        baseFor(
            sharedRootFor(
                isGranted(context.applicationContext),
                documentsRoot(context),
                ::ensureDirOrNull,
            ),
            privateRoot(context),
        )

    fun privateRoot(context: Context): File =
        context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir

    fun sameFolder(a: String, b: String): Boolean {
        val ca = runCatching { File(a).canonicalPath }.getOrDefault(a)
        val cb = runCatching { File(b).canonicalPath }.getOrDefault(b)
        return ca == cb
    }

    /** mkdirs, tolerating a directory that already exists or a volume that refuses. */
    private fun ensureDir(f: File): File {
        if (!f.isDirectory) f.mkdirs()
        return f
    }

    /** True only when [f] is a directory we can stand on, creating it if needed. */
    private fun ensureDirOrNull(f: File): Boolean = f.isDirectory || f.mkdirs()
}
