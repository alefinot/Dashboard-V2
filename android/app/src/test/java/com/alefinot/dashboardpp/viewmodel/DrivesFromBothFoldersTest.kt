package com.alefinot.dashboardpp.viewmodel

import com.alefinot.dashboardpp.trips.TripStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Trips list reads every folder the app has recorded into, so the header has to admit
 * when some of what it shows was not written in the folder in use (shared storage plan §5).
 *
 * [com.alefinot.dashboardpp.storage.AppRoot] decides which folder that is, and it cannot be
 * decided in a JVM test — `Environment.isExternalStorageManager()` is not merely unavailable
 * there, it throws. So what is tested here is the half that is pure arithmetic: given the
 * files a folder walk actually found, does the app notice the ones that came from somewhere
 * else, and does it name the right place? The recorder's own folder is passed in as a plain
 * argument for the same reason.
 */
class DrivesFromBothFoldersTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun tripIn(dir: File, name: String): File {
        dir.mkdirs()
        return File(dir, name).apply { writeText("start_s,eff\n") }
    }

    @Test
    fun `a list of one folder's drives says nothing about other folders`() {
        val active = folder.newFolder("shared-trips")
        val files = listOf(
            tripIn(active, "trip-20261108-175512-001.csv"),
            tripIn(active, "trip-20261107-091200-001.csv"),
        )

        assertNull(TripsViewModel.elsewhereFor(files, active))
    }

    @Test
    fun `drives left behind on the other side of a grant are counted and placed`() {
        val active = folder.newFolder("shared-trips")
        val legacy = folder.newFolder("private-trips")
        val here = tripIn(active, "trip-20261108-175512-001.csv")
        val strayOne = tripIn(legacy, "trip-20261101-080000-001.csv")
        tripIn(legacy, "trip-20261102-080000-001.csv")

        val seen = TripsViewModel.elsewhereFor(listOf(here, strayOne, File(legacy, "trip-20261102-080000-001.csv")), active)

        assertEquals(2, seen?.count)
        assertEquals(legacy.absolutePath, seen?.path)
    }

    @Test
    fun `every drive being from the other folder still names that folder`() {
        // The realistic shape right after storage access is granted: nothing new recorded
        // yet, so the whole list is old drives. The line must still appear, because the
        // folder named in the header above it holds none of them.
        val active = folder.newFolder("shared-trips")
        active.mkdirs()
        val legacy = folder.newFolder("private-trips")
        val files = listOf(
            tripIn(legacy, "trip-20261101-080000-001.csv"),
            tripIn(legacy, "trip-20261102-080000-001.csv"),
        )

        val seen = TripsViewModel.elsewhereFor(files, active)

        assertEquals(2, seen?.count)
        assertEquals(legacy.absolutePath, seen?.path)
    }

    @Test
    fun `an empty list is not a second folder`() {
        assertNull(TripsViewModel.elsewhereFor(emptyList(), folder.newFolder("trips")))
    }

    @Test
    fun `the folder in use is counted on its own and the rest is counted apart`() {
        // The App settings Storage card names one folder and used to print the size of the whole
        // list next to it: on a grant that had just been allowed, a folder holding nothing was
        // captioned `Documents/Dashboard++ · 5 trip files` while all five files sat in the
        // app-private folder. The card may only claim what the folder it names contains.
        val active = folder.newFolder("shared-trips")
        val legacy = folder.newFolder("private-trips")
        val files = listOf(
            tripIn(active, "trip-20261108-175512-001.csv"),
            tripIn(legacy, "trip-20261101-080000-001.csv"),
            tripIn(legacy, "trip-20261102-080000-001.csv"),
        )

        val counts = TripsViewModel.countsFor(files, active)

        assertEquals(
            "the card names the folder in use, so it may only count the files that are in it",
            1, counts.here,
        )
        assertEquals(2, counts.elsewhere?.count)
    }

    @Test
    fun `a folder holding everything says so with its full count and nothing elsewhere`() {
        val active = folder.newFolder("shared-trips")
        val files = listOf(
            tripIn(active, "trip-20261108-175512-001.csv"),
            tripIn(active, "trip-20261107-091200-001.csv"),
        )

        val counts = TripsViewModel.countsFor(files, active)

        assertEquals(2, counts.here)
        assertNull(counts.elsewhere)
        assertEquals(0, TripsViewModel.countsFor(emptyList(), active).here)
    }

    @Test
    fun `the storage card is fed the split counts and not the whole list`() {
        // The arithmetic above is only honest if the card uses it. This is the wiring: the card's
        // number comes from the split, and the other folder's count reaches the sentence too.
        val settings = moduleFile("src/main/java/com/alefinot/dashboardpp/ui/screens/SettingsScreen.kt")
            .readText()

        assertFalse(
            "the Storage card counted every drive the list shows, including the ones sitting in " +
                "the other folder than the one its own label names",
            settings.contains("tripFiles = trips.rows.value.size"),
        )
        assertTrue(settings.contains("counts.here"))
        assertTrue(
            "the card takes the split the index pass published, rather than re-deriving it",
            settings.contains("trips.tripCounts.value"),
        )
        assertTrue(
            "the sentence that counts the card's files belongs to StorageCopy, like every other " +
                "storage sentence in the app",
            settings.contains("StorageCopy.cardCounts"),
        )
        assertTrue(
            "the card has to know whether the remainder is the app's own folder before it words " +
                "that remainder",
            settings.contains("otherTripFilesInOwnFolder"),
        )
        assertTrue(
            "opening the page has to make the index look at the volume again: the owner may have " +
                "spent the time the app was backgrounded deleting a drive in a file manager",
            settings.contains("trips.refresh()"),
        )
        assertTrue(
            "and the card has to be rebuilt when that look finishes, instead of keeping the answer " +
                "that was published before the owner left the app",
            settings.contains("trips.tripCounts.collectAsStateWithLifecycle"),
        )
    }

    @Test
    fun `a folder reached by two paths is one folder`() {
        // `..` and symlinked volumes are how a comparison goes wrong in practice: without
        // canonicalisation the app would tell the owner some of their drives live in a
        // second folder that is the same folder written differently.
        val active = folder.newFolder("shared-trips")
        val same = File(active.parentFile, "shared-trips/../shared-trips")
        val files = listOf(tripIn(active, "trip-20261108-175512-001.csv"))

        assertTrue(same.canonicalPath == active.canonicalPath)
        assertNull(TripsViewModel.elsewhereFor(files, same))
    }

    @Test
    fun `the trip list reads every folder the recorder has used`() {
        // TripStore.dirs() is the reader's answer and cannot run off-device (it needs a
        // Context), so at least pin the promise it makes to the list: the folder in use
        // first, then the app-private one, and the two are named by AppRoot rather than
        // spelled out here.
        val src = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/TripStore.kt")
        val body = src.readText()

        assertTrue(body.contains("AppRoot.tripsDir(appContext)"))
        assertTrue(body.contains("AppRoot.privateTripsDir(appContext)"))
        assertTrue(
            "the list must dedupe a folder it resolved twice",
            body.contains("distinctBy"),
        )
        assertTrue(
            "a name present in both roots is listed once",
            body.contains("filter { seen.add(it.name) }"),
        )
    }

    /**
     * Same helper as `AppXmlWiringTest`: the test task's working directory is the Gradle
     * module in a normal build and the project root in the IDE.
     */
    private fun moduleFile(relative: String): File {
        val fromModule = File(relative)
        if (fromModule.exists()) return fromModule
        return File("android/app/$relative")
    }
}
