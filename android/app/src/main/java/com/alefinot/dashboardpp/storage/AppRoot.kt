package com.alefinot.dashboardpp.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * The one place that answers "where do my files go" (shared storage plan §1).
 *
 * Everything the user owns — trip CSVs, vehicle card JPEGs, the garage file — lives
 * under one root that is either a folder a human can open,
 * `/storage/emulated/0/Documents/Dashboard++/`, or the app-private external
 * directory it used to live in. Which of the two, decided here and nowhere else,
 * so the recorder, the Trips tab, the photo store and the garage cannot disagree
 * about where a drive is.
 *
 * **Why not always the shared folder.** Writing to public Documents needs a grant this app
 * cannot hand itself: on Android 11+ it is *All files access* (`MANAGE_EXTERNAL_STORAGE`), which
 * no dialog can request — it is a settings page the owner has to open (the Storage card in
 * `ui/screens/SettingsScreen.kt` jumps to it); on Android 8.1 through 10 it is the storage
 * permission, which the app can ask for and the owner can still refuse. Until they agree, and on
 * a device where the platform will not give the grant at all, the app would throw on every sample
 * if the shared root were the only answer. So the degraded path stays: files move into
 * `Android/data/<pkg>/files/`, the UI says so, and a drive is still a drive.
 *
 * **Why the folder is not simply the media store.** Card photos are records of
 * *this* vehicle and trip logs are this app's data; neither belongs in the camera
 * roll, and neither should be swept up by a gallery's backup or a "clean up my
 * photos" pass. `Documents/Dashboard++/` is deliberately not a MediaStore
 * directory: nothing is scanned into it, which is also what makes the grant the
 * only way into it.
 *
 * **Why a constant instead of the string `"Documents"`.** OEM volumes alias their
 * public directories; [Environment.getExternalStoragePublicDirectory] resolves
 * what this device calls its Documents folder, and a hand-written path would
 * quietly resolve to a different one - or none - on the device that matters.
 *
 * The split is the same one [com.alefinot.dashboardpp.trips.TripStore.uniqueName]
 * makes: [sharedRootFor], [baseFor] and the `…DirIn` / [garageFileIn] builders are
 * pure and carry every decision worth testing; the members that take a [Context]
 * do nothing but ask Android the two questions this class cannot answer for itself.
 */
object AppRoot {

    /**
     * The folder the app owns inside Documents. Declared once, on purpose: it
     * appears in the on-screen path, in the share-sheet roots in
     * `res/xml/files_path.xml`, and in `AppRootTest`, and a rename that reaches
     * some but not all of them leaves a user's drives in one folder and their
     * photos in another.
     */
    const val FOLDER = "Dashboard++"

    /** Sub-directory of the trip CSVs. Same name under either root. */
    const val TRIPS = "trips"

    /** Sub-directory of the vehicle card JPEGs. Same name under either root. */
    const val VEHICLES = "vehicles"

    /** The garage file. Same name under either root. */
    const val GARAGE_FILE = "vehicles.json"

    // ---- pure decision ------------------------------------------------------

    /**
     * The shared root, or `null` when it must not be used.
     *
     * Both conditions are load-bearing and they fail differently. The grant is the
     * legal half: without it the write is a `SecurityException` on every sample.
     * [canCreate] is the physical half — a grant the volume does not honour (a
     * read-only or removed SD card, an OEM volume mounted over Documents, storage
     * mid-unmount) still answers `isExternalStorageManager() == true` and still
     * refuses the file, so the folder has to be proven creatable before anything is
     * promised to it. A `null` [docsRoot] is the third case: no external volume at
     * all, so there is no folder to create.
     *
     * [canCreate] is injected rather than calling `mkdirs()` here so the rule is
     * testable off-device; the Android layer passes the real thing.
     */
    internal fun sharedRootFor(granted: Boolean, docsRoot: File?, canCreate: (File) -> Boolean): File? {
        if (!granted || docsRoot == null) return null
        val root = File(docsRoot, FOLDER)
        return if (canCreate(root)) root else null
    }

    /**
     * The root the app writes under: [shared] when there is one, [privateRoot]
     * otherwise. Never null in either branch — a device with nowhere to write still
     * gets a directory it can try, which is what keeps a drive on the disk instead
     * of losing it to a null.
     */
    internal fun baseFor(shared: File?, privateRoot: File): File = shared ?: privateRoot

    /** Trip CSVs under [base]. */
    internal fun tripsDirIn(base: File): File = File(base, TRIPS)

    /** Vehicle card JPEGs under [base]. */
    internal fun vehiclesDirIn(base: File): File = File(base, VEHICLES)

    /** The garage file under [base]. */
    internal fun garageFileIn(base: File): File = File(base, GARAGE_FILE)

    /**
     * The path the UI shows for the shared root, in the words the file manager
     * uses. Not absolute: the volume in front of a tablet is not the volume the
     * owner is typing into a search box, and `/storage/emulated/0/…` reads like a
     * bug report. Built from [Environment.DIRECTORY_DOCUMENTS] and [FOLDER] so the
     * sentence and the resolved path cannot drift apart.
     */
    fun sharedRootLabel(): String = Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER

    // ---- the Android layer --------------------------------------------------

    /**
     * True when the shared root is usable right now: the grant is held *and*
     * `Documents/Dashboard++/` can be created. See [sharedRootFor] for why both
     * halves are needed and [isGranted] for what "the grant is held" means per API
     * level.
     */
    fun isSharedAvailable(context: Context): Boolean =
        sharedRootFor(isGranted(context), documentsRoot(context), ::ensureDirOrNull) != null

    /**
     * Whether this build has been *given* shared storage, which is a different
     * question per API level and one the platform answers in two incompatible ways:
     *
     * - **API 30+ (Android 11+):** All files access, a special access the owner
     *   grants on a settings page — `Environment.isExternalStorageManager()`. The
     *   permission is declared in the manifest but declaring it grants nothing;
     *   `checkSelfPermission` does not report this one, so it cannot be the test.
     * - **API 26-29 (Android 8.1 through 10):** the ordinary runtime
     *   `WRITE_EXTERNAL_STORAGE`, asked by [com.alefinot.dashboardpp.MainActivity] at first
     *   launch. On 8.1/9 that is the whole story — scoped storage does not exist on those
     *   devices, because it applies to apps running on Android 10 and up. On Android 10 it works
     *   as well, because this app opts out of scoped storage with
     *   `android:requestLegacyExternalStorage` (see the manifest) and Android 10 honours that
     *   flag whatever an app targets: measured 2026-11-06 on a Mi 9 Lite (MIUI 12) and a Galaxy
     *   Tab A at `targetSdk 35`, both reported `LEGACY_STORAGE: allow` after install, and once
     *   the launch dialog was allowed both created and wrote `Documents/Dashboard++/trips` — on
     *   the tablet, where `Documents/` did not exist at all, the app made it. That flag is inert
     *   on Android 11 devices, which is exactly where the first case takes over, and
     *   [usesLegacyStorage] is what keeps the two from blurring into each other.
     *
     * The manifest caps the permission at `maxSdkVersion="29"` for the same reason: past API 29
     * it grants nothing to this app, and the permission list is a statement about what the app
     * does.
     */
    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED && usesLegacyStorage()
        }

    /**
     * Whether the volume is showing this process the pre-scoped, flat view of external storage.
     *
     * On API 26-28 it always is. On API 29 it is the answer to the manifest flag, and it is read
     * rather than assumed for the same reason [sharedRootFor] probes the folder: an install can
     * end up non-legacy anyway (an upgrade from a build that carried no flag, or an OEM applying
     * its own policy), and holding `WRITE_EXTERNAL_STORAGE` in that state buys nothing — every
     * write into public Documents throws. Reading it costs one call and turns a mystery
     * `SecurityException` into the app-private folder the UI already explains.
     */
    private fun usesLegacyStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Deprecated in API 30, where it is never reached: from there the question is
            // isExternalStorageManager() and this app's flag is inert.
            @Suppress("DEPRECATION")
            runCatching { Environment.isExternalStorageLegacy() }.getOrDefault(false)
        } else {
            true
        }

    /**
     * Whether this Android version has anything to *ask* for. [isGranted] answers whether the
     * shared folder is open; a button has to answer the other question — is there a switch to
     * flip, and where does it live.
     *
     * Two rows, because there are two ways in. Android 10 belongs in the runtime-permission row:
     * it has no All files access (that arrived in API 30), but it still honours this app's
     * `requestLegacyExternalStorage` flag, so the storage permission is enough there — measured on
     * two Android 10 devices, see [isGranted]. The reading this replaced, that API 29 had "nothing
     * to grant", came from trusting a general sentence about `targetSdk >= 30` rather than looking
     * at a device, and it cost an owner a folder they could have had.
     */
    enum class GrantPath {
        /** Android 11+ (API 30+): the owner can grant All files access on a settings page. */
        ALL_FILES,

        /**
         * Android 8.1 through 10 (API ≤29): the runtime `WRITE_EXTERNAL_STORAGE`, asked at
         * first launch and re-askable from the Storage card.
         */
        RUNTIME_PERMISSION,
    }

    /**
     * Which of those this SDK level is. A parameter rather than [Build.VERSION.SDK_INT] on
     * purpose: this branch is the reason the Android 10 story is what it is, and a unit test can
     * walk both rows of it while this process runs on one.
     *
     * The line is API 30 and nowhere else, matching the platform: All files access was introduced
     * there, and `requestLegacyExternalStorage` stops being honoured on Android 11 devices there.
     */
    fun grantPath(sdkInt: Int): GrantPath =
        if (sdkInt >= Build.VERSION_CODES.R) GrantPath.ALL_FILES else GrantPath.RUNTIME_PERMISSION

    /** The folder a drive's CSVs land in, created if it is missing. Never null. */
    fun tripsDir(context: Context): File = ensureDir(tripsDirIn(base(context)))

    /**
     * The `trips/` folder under the **private** root, whatever the grant says. Only
     * for readers: the recorder uses [tripsDir], which follows the grant. The Trips
     * list asks for both because a drive recorded while the grant was on the other
     * side is still the owner's file, and hiding it on a permission change reads as
     * lost data (shared storage plan §5).
     */
    fun privateTripsDir(context: Context): File = ensureDir(tripsDirIn(privateRoot(context)))

    /** The folder a card's JPEG lands in, created if it is missing. Never null. */
    fun vehiclesDir(context: Context): File = ensureDir(vehiclesDirIn(base(context)))

    /** The garage file's location. Not created: reading a missing one means an empty garage. */
    fun garageFile(context: Context): File = garageFileIn(base(context))

    /**
     * The shared root [FOLDER] inside this device's Documents volume, or `null`
     * when the volume is not there. `getExternalStoragePublicDirectory` is
     * deprecated because it hands back a path with no guarantee of access — which
     * is exactly what [sharedRootFor] then checks — and it is still the call that
     * resolves an OEM's aliased Documents directory, which a literal would not.
     */
    @Suppress("DEPRECATION")
    private fun documentsRoot(context: Context): File? =
        try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        } catch (e: Exception) {
            // No external volume mounted (or a device policy that forbids it). The
            // answer is the fallback, not an exception on the UI thread.
            null
        }

    /**
     * The root chosen once, for this call. Recomputed every time rather than
     * cached: the grant can be revoked from system settings while the app runs, and
     * a trip recorder that latched the old answer would keep writing to a folder it
     * can no longer open. Cheap — it is a check and a `mkdirs()` on a path that
     * already exists.
     */
    private fun base(context: Context): File =
        baseFor(
            sharedRootFor(
                isGranted(context.applicationContext),
                documentsRoot(context),
                ::ensureDirOrNull,
            ),
            privateRoot(context),
        )

    /**
     * The app's own external folder — the root that never needs a permission. Public so the
     * Trips list can read the drives left there ([privateTripsDir]); nothing else should
     * pick a root by hand, which is the whole reason [base] exists.
     */
    fun privateRoot(context: Context): File =
        // getExternalFilesDir returns null when the volume is not mounted;
        // internal storage always is. The chain is why baseFor needs no null.
        context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir

    /**
     * Whether two paths name the same folder, however each of them is spelled. Sentences about
     * "the app's own folder" and "another folder" are decided with this: comparing raw strings
     * would call one folder by two names (`..`, a mounted volume, a path built by hand) and tell
     * the owner their drives are somewhere they are not.
     */
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
