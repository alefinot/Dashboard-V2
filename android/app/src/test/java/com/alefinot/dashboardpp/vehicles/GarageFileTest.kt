package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The garage file: the format, and the two rules that decide what the app may do to it (shared
 * storage plan §3).
 *
 * A garage used to be a preferences file the user could not read. Now it is a JSON document in
 * their Documents folder, so it will be hand-edited, restored from a backup, and half-copied by a
 * cable — and the app's behaviour in front of a damaged file is the difference between a user
 * losing a typo and losing every card they own. Hence the shape of these tests:
 *
 * - **Round-trip first.** If the writer and the reader disagree about the container, no amount of
 *   careful damage handling matters — the app would wreck its own file on the next save.
 * - **Damage refuses, and says why.** [GarageRead.Damaged] is the state in which
 *   `VehicleStore.put`/`delete`/`select` return `false`. The reason is asserted as part of the
 *   outcome because that text is what the editor shows; a test that only checked "some damage"
 *   would let a message saying "parse error" reach a user who needs to know which card.
 * - **Absent is not damage.** An empty folder and an unreadable file look the same to a caller that
 *   only asks "did it parse?", and the two answers are opposite: write freely, or write never.
 * - **The writer is atomic.** `.tmp` then rename, the rule [VehiclePhotoStore] already follows for
 *   a JPEG. A garage written half-way is a garage the app then refuses to open. Replacing an
 *   existing file in that rename is POSIX behaviour — the app runs on Android, so the assertion that
 *   needs it is skipped on a Windows host instead of being dropped; the half that runs everywhere is
 *   that a write which fails leaves the old file byte-for-byte where it was.
 *
 * `nextId()`'s "never reissue a freed id" rule is asserted in [VehicleOrderTest] against
 * [VehicleStore.nextIdName], which is pure; through the file it is the same function reading a
 * `nextSeq` that these tests show is preserved.
 */
class GarageFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val v1 = Vehicle(
        id = "v1",
        mac = "AA:BB:CC:DD:EE:01",
        name = "Honda",
        photoFile = "v1.jpg",
        type = VehicleType.CAR,
        plate = "B 1234 CD",
        maker = "Honda",
        model = "Civic",
        year = "2019",
        notes = "runs on the hall sensor only",
        bleAddress = "AA:BB:CC:DD:EE:01",
        lastConnectedAtMs = 1_730_000_000_000,
        firmwareVersion = "1.5.1",
        createdAtMs = 1_700_000_000_000,
    )

    private val v3 = Vehicle(
        id = "v3",
        mac = null,
        name = "Yamaha",
        photoFile = null,
        type = VehicleType.MOTORCYCLE,
        createdAtMs = 1_700_000_001_000,
    )

    /** The container round-trips: ids, the pointer, the counter, and the photo names inside records. */
    @Test
    fun `a garage written by the app reads back identical`() {
        val garage = Garage(nextSeq = 7, selectedId = "v3", vehicles = listOf(v1, v3))
        val read = GarageFile.decode(GarageFile.encode(garage))

        assertTrue("expected Ok, got $read", read is GarageRead.Ok)
        val back = (read as GarageRead.Ok).garage
        assertEquals(garage.nextSeq, back.nextSeq)
        assertEquals(garage.selectedId, back.selectedId)
        assertEquals(listOf(v1, v3), back.vehicles)
        // Named separately because a record whose photo name is dropped is a card that quietly
        // lost its picture, and the equality above would not say which field broke.
        assertEquals("v1.jpg", back.vehicles.first { it.id == "v1" }.photoFile)
        assertEquals(null, back.vehicles.first { it.id == "v3" }.photoFile)
    }

    /** An empty garage is a real state, not damage: the app may write it freely. */
    @Test
    fun `an empty garage round-trips`() {
        val text = GarageFile.encode(Garage(nextSeq = 0, selectedId = null, vehicles = emptyList()))
        val read = GarageFile.decode(text)

        assertTrue("expected Ok, got $read", read is GarageRead.Ok)
        val back = (read as GarageRead.Ok).garage
        assertEquals(0, back.nextSeq)
        assertNull(back.selectedId)
        assertEquals(emptyList<Vehicle>(), back.vehicles)
    }

    /** A name with quotes, a backslash and an emoji has to survive the writer and the reader. */
    @Test
    fun `a name the user typed is not a format hazard`() {
        val awkward = v3.copy(name = "Bob \"The Bike\" \\ Yamaha 🏍", notes = "line1\nline2")
        val read = GarageFile.decode(GarageFile.encode(Garage(3, awkward.id, listOf(awkward))))

        assertTrue("expected Ok, got $read", read is GarageRead.Ok)
        val back = (read as GarageRead.Ok).garage.vehicles.single()
        assertEquals(awkward.name, back.name)
        assertEquals(awkward.notes, back.notes)
    }

    /** A file that is not there yet is a new install, and must not be confused with a broken one. */
    @Test
    fun `a missing file is absent rather than damaged`() {
        val missing = File(folder.newFolder("empty"), "vehicles.json")
        val read = GarageFile.read(missing)

        assertTrue(
            "an absent file must answer Absent: a caller that treats it as damage can never " +
                "save a first card, and one that treats damage as absent overwrites a garage",
            read is GarageRead.Absent,
        )
    }

    /** A truncated write — the classic mid-`rename` death — reads as damage, not as an empty garage. */
    @Test
    fun `a truncated file refuses the write that would replace it`() {
        val good = GarageFile.encode(Garage(7, "v1", listOf(v1, v3)))
        val torn = good.substring(0, good.length - 12)

        val read = GarageFile.decode(torn)
        assertTrue("a truncated file must be damage, got $read", read is GarageRead.Damaged)
        assertTrue(
            "the damage reason is shown to the user; it has to name the trouble, not just admit one",
            (read as GarageRead.Damaged).why.isNotBlank(),
        )
    }

    /** Empty text is the same case as a truncated one, and reaches the same answer. */
    @Test
    fun `an empty file is damage`() {
        assertTrue(GarageFile.decode("") is GarageRead.Damaged)
        assertTrue(GarageFile.decode("   \n ") is GarageRead.Damaged)
    }

    /**
     * A file from a build this app does not know is not parsed field by field: the fields this
     * build does not know would be dropped by the next save, and the save would look like success.
     */
    @Test
    fun `a version this build cannot write is damage`() {
        val future = GarageFile.encode(Garage(7, "v1", listOf(v1))).replace("\"version\":1", "\"version\":2")

        val read = GarageFile.decode(future)
        assertTrue("expected damage, got $read", read is GarageRead.Damaged)
        assertTrue(
            "the reason should quote the version the file claims, which is the sentence that tells " +
                "the user to update the app rather than delete the file",
            (read as GarageRead.Damaged).why.contains("2"),
        )

        // A file with no version tag at all is the same case: something wrote it, and it was not
        // this format.
        val untagged = GarageFile.encode(Garage(7, "v1", listOf(v1))).replace("\"version\":1,", "")
        assertTrue("expected damage, got ${GarageFile.decode(untagged)}", GarageFile.decode(untagged) is GarageRead.Damaged)
    }

    /**
     * Two cards with one id is a hand-edit slip, and writing that file back would fold two people's
     * drives together under one card — ids double as trip tags.
     */
    @Test
    fun `two cards sharing an id is damage`() {
        val text = GarageFile.encode(Garage(9, "v1", listOf(v1, v1.copy(name = "Other"))))

        val read = GarageFile.decode(text)
        assertTrue("expected damage, got $read", read is GarageRead.Damaged)
        assertTrue(
            "the reason should name the id the user has to fix",
            (read as GarageRead.Damaged).why.contains("v1"),
        )
    }

    /** A record that cannot be read is a torn file now: one card is no longer a separate row. */
    @Test
    fun `one unreadable record damages the whole file`() {
        val text = GarageFile.encode(Garage(3, "v1", listOf(v1, v3)))
        val broken = text.replace("\"v\":\"v1\"", "\"v\":\"v9\"") // v3's record: a format version this reader rejects

        assertTrue(
            "with one file for the garage, a record this build cannot decode must stop every write",
            GarageFile.decode(broken) is GarageRead.Damaged,
        )
    }

    /** A missing or misplaced `vehicles` key is a file that was never written correctly. */
    @Test
    fun `a missing or wrong-shaped vehicles list is damage`() {
        val missing = GarageFile.encode(Garage(3, "v1", listOf(v1))).replace(Regex("\"vehicles\":\\[.*?]"), "\"next\":7")
        assertTrue("expected damage, got ${GarageFile.decode(missing)}", GarageFile.decode(missing) is GarageRead.Damaged)

        val wrongShape = GarageFile.encode(Garage(3, "v1", listOf(v1))).replace(Regex("\"vehicles\":\\[.*?]"), "\"vehicles\":7")
        assertTrue("expected damage, got ${GarageFile.decode(wrongShape)}", GarageFile.decode(wrongShape) is GarageRead.Damaged)
    }

    /**
     * The counter and the pointer are bookkeeping, not cards: a damaged one is worth fixing, not
     * worth losing the garage over. They fall back to something the app can act on, and the cards
     * survive intact.
     */
    @Test
    fun `damaged bookkeeping costs the cards nothing`() {
        val full = GarageFile.encode(Garage(7, "v3", listOf(v1, v3)))

        // Counter gone: it restarts above the highest id in the file, so a name already used as a
        // trip tag is never handed out again.
        val noSeq = full.replace(Regex("\"nextSeq\":\\d+"), "\"nextSeq\":\"seven\"")
        val back = GarageFile.decode(noSeq)
        assertTrue("expected Ok, got $back", back is GarageRead.Ok)
        val garage = (back as GarageRead.Ok).garage
        assertEquals(listOf(v1, v3), garage.vehicles)
        assertTrue(
            "a broken counter must not fall back below the highest id, or the next card would " +
                "inherit v3's drives",
            garage.nextSeq >= 3,
        )

        // Pointer naming a card that is not there: sorted() already treats that as "nothing first".
        val ghost = full.replace("\"selectedId\":\"v3\"", "\"selectedId\":\"v42\"")
        assertTrue("expected Ok, got ${GarageFile.decode(ghost)}", GarageFile.decode(ghost) is GarageRead.Ok)
    }

    /** The writer lands a whole file and leaves no scratch behind, so a crash cannot show half a garage. */
    @Test
    fun `the writer is atomic and leaves no scratch file`() {
        val dir = folder.newFolder("garage")
        val target = File(dir, "vehicles.json")

        assertTrue(GarageFile.writeAtomically(target, GarageFile.encode(Garage(7, "v3", listOf(v1, v3)))))
        assertTrue(target.isFile)
        val leftovers: List<String> =
            dir.listFiles { f: File -> f.name.endsWith(".tmp") }!!.map { it.name }
        assertEquals(
            "a leftover vehicles.json.tmp is litter in the user's folder that looks like a garage",
            emptyList<String>(),
            leftovers,
        )

        // And what landed is readable, which is the point of the exercise.
        val read = GarageFile.read(target)
        assertTrue("expected Ok, got $read", read is GarageRead.Ok)
        assertEquals(2, (read as GarageRead.Ok).garage.vehicles.size)
    }

    /**
     * Saving over an existing garage replaces it in one step: a reader never sees the old file and
     * the new one merged, and never sees nothing.
     *
     * That is POSIX `rename` semantics — same directory, target exists, replacement atomic — which
     * is exactly what Android gives this code. A Windows JVM refuses the call, so the assertion is
     * skipped there rather than quietly rewritten into testing something the production code does
     * not do. On the bench tablet this path runs every time a card is edited twice.
     */
    @Test
    fun `a second write replaces the file whole`() {
        Assume.assumeFalse(
            "replace-on-rename is POSIX behaviour; the app runs on Android, this host is Windows",
            System.getProperty("os.name").orEmpty().lowercase().contains("windows"),
        )
        val dir = folder.newFolder("replace")
        val target = File(dir, "vehicles.json")
        assertTrue(GarageFile.writeAtomically(target, GarageFile.encode(Garage(7, "v3", listOf(v1, v3)))))

        assertTrue(GarageFile.writeAtomically(target, GarageFile.encode(Garage(9, "v1", listOf(v1)))))
        val again = GarageFile.read(target)
        assertTrue("expected Ok, got $again", again is GarageRead.Ok)
        assertEquals(1, (again as GarageRead.Ok).garage.vehicles.size)
        assertEquals(9, again.garage.nextSeq)
        assertFalse(File(dir, "vehicles.json.tmp").exists())
    }

    /**
     * A write that cannot land leaves the garage the user still owns exactly as it was.
     *
     * This is the assertion behind the editor's "nothing on disk changed" sentence, so it is the one
     * that must not depend on the platform: the failure is provoked by taking write permission away
     * from the folder, which every host refuses a `mkdirs` or a temp file for. If the OS hands out
     * a folder that stays writable anyway, the test gives up rather than passing on nothing.
     */
    @Test
    fun `a write that cannot land leaves the good file alone`() {
        val dir = folder.newFolder("locked")
        val target = File(dir, "vehicles.json")
        val good = GarageFile.encode(Garage(7, "v3", listOf(v1, v3)))
        assertTrue(GarageFile.writeAtomically(target, good))

        val locked = dir.setWritable(false, false)
        Assume.assumeTrue("the host would not lock the folder", locked && !dir.canWrite())
        try {
            assertFalse(GarageFile.writeAtomically(target, GarageFile.encode(Garage(9, "v1", listOf(v1)))))
            assertEquals("a failed write must not rewrite the user's garage", good, target.readText())
        } finally {
            dir.setWritable(true, false)
        }
    }

    /** A write that cannot even make its scratch file reports failure and touches nothing else. */
    @Test
    fun `a write that cannot land fails without disturbing anything`() {
        // Not a directory: mkdirs cannot produce one, so the write has to fail rather than throw.
        val fileNotDir = folder.newFile("not-a-directory")
        val target = File(fileNotDir, "vehicles.json")

        assertFalse(GarageFile.writeAtomically(target, GarageFile.encode(Garage(1, null, listOf(v1)))))
        assertFalse(target.exists())
        assertTrue("the thing in the way must still be the thing that was there", fileNotDir.isFile)
    }

    /** Read of a file that exists but cannot be read answers damage, so the store will not overwrite it. */
    @Test
    fun `a file that cannot be read is damage`() {
        val dir = folder.newFolder("unreadable")
        val target = File(dir, "vehicles.json")
        target.writeText("garbage that is not json at all {{{")

        val read = GarageFile.read(target)
        assertTrue("expected damage, got $read", read is GarageRead.Damaged)
    }
}
