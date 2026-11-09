package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Where the garage is allowed to live, and what the app may write when it is not where it looked.
 *
 * The garage is one JSON file, and the folder it lives in follows the storage grant:
 * `Documents/Dashboard++/` while the owner has All files access (or the legacy permission on
 * Android 10), `Android/data/<pkg>/files/` when they have not — and a volume that unmounts briefly
 * moves it by itself. Every one of those is a moment in which the app is looking into an empty
 * folder while the cards sit in the other one.
 *
 * That is not a hypothetical. On 2026-11-09 a bench phone answered "No dashboards paired yet" over
 * an intact garage: the store had said "no garage here", the adoption form opened and reserved an
 * id, and that empty garage was renamed over the file — one card, and every field its owner had
 * typed, gone in a single atomic write. The two rules below are the fix, and each is the shape of
 * that failure:
 *
 * - **Look in both roots before deciding the garage is empty** ([GarageFile.readBoth]), preferring
 *   the file that is at risk. The other root is a rescue, never a merge: two garages is a state this
 *   app shows rather than mixes, exactly as the Trips list shows both folders.
 * - **One door to the file, and it refuses a garage the read never produced.** The old rule was a
 *   convention — "every mutation routes through `mutate`, which checks damage" — and `nextId()` was
 *   not one of the mutations that did.
 */
class GarageSurvivesAStorageRootMoveTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The card the 2026-11-09 loss cost its owner, rebuilt from what the vehicle page showed. */
    private val card = Vehicle(
        id = "v1",
        mac = "94:B5:55:F8:B8:58",
        name = "Motosbu",
        type = VehicleType.MOTORCYCLE,
        plate = "X45FHD",
        maker = "APRILIA",
        model = "SPORTCITY  ONE",
        year = "2011",
        notes = "Swap 100cc 4T",
    )

    private fun shared() = File(folder.newFolder("shared"), "vehicles.json")

    private fun private() = File(folder.newFolder("private"), "vehicles.json")

    /** Write [g] to [file] the way the app does, so the reader is tested against its own output. */
    private fun put(file: File, g: Garage) {
        assertTrue(
            "fixture: the garage must be writable to be testable",
            GarageFile.writeAtomically(file, GarageFile.encode(g)),
        )
    }

    /** A file this build will not parse: what a half-copied restore or a lost brace leaves behind. */
    private fun tear(file: File) {
        file.parentFile?.mkdirs()
        file.writeText("{\"version\":1,\"nextSeq\":1,\"vehicles\":[")
    }

    // ---- the two-root read --------------------------------------------------------------

    @Test
    fun `the cards in the other root are found when the folder the app writes to is empty`() {
        val shared = shared()
        val priv = private()
        put(priv, Garage(nextSeq = 1, selectedId = "v1", vehicles = listOf(card)))

        val scan = GarageFile.readBoth(shared, priv)
        assertTrue(
            "a storage-root move must not read as a brand-new install: " +
                "an empty answer is the one answer the store is allowed to write over",
            scan is GarageScan.Found,
        )
        scan as GarageScan.Found
        assertEquals(listOf(card), scan.garage.vehicles)
        assertEquals("and say where they came from", priv, scan.from)
    }

    @Test
    fun `the file the app writes to wins, and two garages are never merged`() {
        val shared = shared()
        val priv = private()
        put(shared, Garage(nextSeq = 2, selectedId = "v2", vehicles = listOf(card.copy(id = "v2"))))
        put(priv, Garage(nextSeq = 1, selectedId = "v1", vehicles = listOf(card)))

        val scan = GarageFile.readBoth(shared, priv)
        scan as GarageScan.Found
        assertEquals("preferring the write target is what makes the next write a move", shared, scan.from)
        assertEquals(listOf("v2"), scan.garage.vehicles.map { it.id })
    }

    @Test
    fun `an unreadable write target blocks the write even when the other root is perfect`() {
        val shared = shared()
        val priv = private()
        tear(shared)
        put(priv, Garage(nextSeq = 1, selectedId = "v1", vehicles = listOf(card)))

        val scan = GarageFile.readBoth(shared, priv)
        assertTrue(
            "falling back here would let the next write overwrite a file the app could not read — " +
                "the silent delete the whole format exists to prevent",
            scan is GarageScan.Blocked,
        )
        assertEquals(shared, (scan as GarageScan.Blocked).where)
    }

    @Test
    fun `an unreadable fallback is never reported as an empty garage`() {
        val shared = shared()
        val priv = private()
        tear(priv)

        val scan = GarageFile.readBoth(shared, priv)
        assertTrue(
            "\"nothing here yet\" and \"there is something here I dare not touch\" are opposite " +
                "answers, and here the something may be the only copy of the cards",
            scan is GarageScan.Blocked,
        )
        assertEquals(priv, (scan as GarageScan.Blocked).where)
    }

    @Test
    fun `two empty roots are a new install, and writing there stays free`() {
        assertTrue(
            "the rescue must not turn a genuine first launch into a refusal to save",
            GarageFile.readBoth(shared(), private()) is GarageScan.Nothing,
        )
    }

    @Test
    fun `a device with one root reads that one file twice without a special case`() {
        val only = shared()
        put(only, Garage(nextSeq = 1, selectedId = "v1", vehicles = listOf(card)))

        val scan = GarageFile.readBoth(only, only)
        scan as GarageScan.Found
        assertEquals(listOf(card), scan.garage.vehicles)
        assertEquals(only, scan.from)
    }

    // ---- the write gate -----------------------------------------------------------------

    @Test
    fun `the store never asks one folder where the garage lives`() {
        val store = mainSources().first { it.name == "VehicleStore.kt" }.readText()
        assertTrue("GarageState has to go through the two-root read", store.contains("GarageFile.readBoth("))
        assertTrue(
            "and must not ask a single file again — that question is what answered \"empty\" " +
                "over a garage that existed",
            !store.contains("GarageFile.read("),
        )
    }

    @Test
    fun `there is one door to the garage file and it refuses a garage the read never produced`() {
        val writers = mainSources().filter { it.readText().contains("GarageFile.writeAtomically(") }
        assertEquals(
            "every garage write must funnel through GarageState.write(); a second caller is a " +
                "second set of failure rules, and the 2026-11-09 loss came from a caller that " +
                "checked nothing",
            listOf("VehicleStore.kt"),
            writers.map { it.name },
        )

        val store = mainSources().first { it.name == "VehicleStore.kt" }.readText()
        assertTrue(
            "the door has to check that the read actually permitted a write",
            store.contains("if (!writable) return false"),
        )
    }

    /** Every Kotlin file under `app/src/main`, the same tree the other source guards read. */
    private fun mainSources(): List<File> {
        val root = File("src/main")
            .takeIf { it.isDirectory }
            ?: File("android/app/src/main")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
