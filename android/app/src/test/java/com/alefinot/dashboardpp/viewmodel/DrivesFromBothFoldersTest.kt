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
        val active = folder.newFolder("shared-trips")
        val same = File(active.parentFile, "shared-trips/../shared-trips")
        val files = listOf(tripIn(active, "trip-20261108-175512-001.csv"))

        assertTrue(same.canonicalPath == active.canonicalPath)
        assertNull(TripsViewModel.elsewhereFor(files, same))
    }

    @Test
    fun `the trip list reads every folder the recorder has used`() {
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

    private fun moduleFile(relative: String): File {
        val fromModule = File(relative)
        if (fromModule.exists()) return fromModule
        return File("android/app/$relative")
    }
}
