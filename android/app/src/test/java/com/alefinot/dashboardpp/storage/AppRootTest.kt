package com.alefinot.dashboardpp.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the app's user data goes (shared storage plan §1).
 *
 * [AppRoot] is the one place that answers that question, and the answer has two
 * halves. The half worth arguing about — shared when the grant is held *and* the
 * folder can actually be created, app-private otherwise, same sub-directory names
 * either way — is pure, so it is tested here against a `File` the JVM can make.
 * The half that is one Android call (`Environment.isExternalStorageManager()`,
 * `getExternalFilesDir()`) has nothing to argue about and is not tested: this
 * project has no Robolectric, and a fake Context that returns a canned answer for
 * `isExternalStorageManager()` would only be a test of that fake.
 *
 * The failure these tests exist to prevent is silent, and expensive either way:
 * a root that is chosen when the volume cannot be written picks a folder that
 * loses every trip the user records, and a root that falls back when the grant is
 * good hides the drive files inside `Android/data/` from a user who can see them.
 * Both look like a working app for exactly one drive.
 */
class AppRootTest {

    // ---- folder identity: is this path the folder a sentence just named? ----------

    @Test
    fun `a folder is recognised even when it is spelled another way`() {
        // The Storage card says where drives the folder it names does not hold are - "the app's own
        // folder" or "another folder" - and that choice is made by comparing the folder the files
        // were found in with the app's private root. Comparing strings would call the same folder
        // by two names.
        val docs = tempDir("identity")
        val trips = File(docs, "Dashboard++/trips").apply { mkdirs() }
        val roundabout = File(docs, "Dashboard++/../Dashboard++/trips").absolutePath

        assertTrue(AppRoot.sameFolder(trips.absolutePath, roundabout))
        assertTrue(AppRoot.sameFolder(trips.path, trips.path))
        assertFalse(
            "the private root is not the shared root, however both are spelled",
            AppRoot.sameFolder(trips.absolutePath, File(docs, "app-private/trips").absolutePath),
        )
    }

    // ---- the decision: shared, or the app-private fallback ------------------

    @Test
    fun `shared is chosen when the grant is held and the folder can be made`() {
        val docs = tempDir("docs")
        val shared = AppRoot.sharedRootFor(granted = true, docsRoot = docs, canCreate = { true })

        assertNotNull("a granted, writable volume must be used", shared)
        assertEquals(File(docs, AppRoot.FOLDER), shared)
    }

    @Test
    fun `the fallback is chosen when the grant is missing`() {
        // The common state on a fresh install: the app has the permission declared
        // but the owner has not visited the settings page yet. Writing to Documents
        // here would throw for every sample of every drive.
        val docs = tempDir("docs")

        assertNull(AppRoot.sharedRootFor(granted = false, docsRoot = docs, canCreate = { true }))
    }

    @Test
    fun `the fallback is chosen when the folder cannot be made`() {
        // A grant the volume does not honour: read-only or missing SD card, an OEM
        // volume mounted over Documents, or storage in the middle of being
        // unmounted. `Environment.isExternalStorageManager()` still says true.
        val docs = tempDir("docs")

        assertNull(AppRoot.sharedRootFor(granted = true, docsRoot = docs, canCreate = { false }))
    }

    @Test
    fun `the fallback is chosen when there is no Documents volume at all`() {
        // External storage unmounted or absent - getExternalStoragePublicDirectory
        // hands back a path on a volume that is not there, so the caller passes null.
        assertNull(AppRoot.sharedRootFor(granted = true, docsRoot = null, canCreate = { true }))
    }

    @Test
    fun `a made-possible shared root is the folder the plan names`() {
        // The one place the folder name is spelled is asserted below; here that the
        // root really is Documents/<FOLDER> and not <FOLDER> on the volume root.
        val docs = tempDir("docs")
        val shared = AppRoot.sharedRootFor(granted = true, docsRoot = docs, canCreate = { true })!!

        assertEquals(AppRoot.FOLDER, shared.name)
        assertEquals(docs, shared.parentFile)
    }

    // ---- the fallback base is never null -----------------------------------

    @Test
    fun `the base is the shared root when one exists`() {
        val docs = tempDir("docs")
        val shared = File(docs, AppRoot.FOLDER)
        val private = tempDir("private")

        assertEquals(shared, AppRoot.baseFor(shared, private))
    }

    @Test
    fun `the base is the app-private root when there is no shared root`() {
        // No null can come out of this. Callers keep a non-null base for every
        // drive, which is the whole reason the fallback chain is `?: filesDir`:
        // an app with nowhere to write must still record the drive.
        val private = tempDir("private")

        assertEquals(private, AppRoot.baseFor(null, private))
        assertNotNull(AppRoot.baseFor(null, File("/data/user/0/x/files")))
    }

    // ---- the same sub-directories under either root -------------------------

    @Test
    fun `both roots carry the same sub-directory names`() {
        // TripsViewModel, TripStore, VehiclePhotoStore and the garage file each ask
        // for one of these under whichever root they were given. Different names on
        // the two paths would put a drive's CSV where the Trips tab is not looking,
        // and a photo where its record's `photoFile` cannot resolve it.
        val shared = File("/storage/emulated/0/Documents", AppRoot.FOLDER)
        val private = File("/storage/emulated/0/Android/data/pkg/files")

        for (base in listOf(shared, private)) {
            assertEquals("trips", AppRoot.tripsDirIn(base).name)
            assertEquals("vehicles", AppRoot.vehiclesDirIn(base).name)
            assertEquals("vehicles.json", AppRoot.garageFileIn(base).name)
            assertEquals(base, AppRoot.tripsDirIn(base).parentFile)
            assertEquals(base, AppRoot.vehiclesDirIn(base).parentFile)
            assertEquals(base, AppRoot.garageFileIn(base).parentFile)
        }
    }

    @Test
    fun `the shared sub-directories sit under the shared root and not beside it`() {
        val shared = File("/storage/emulated/0/Documents", AppRoot.FOLDER)

        assertEquals(File("/storage/emulated/0/Documents/${AppRoot.FOLDER}/trips"), AppRoot.tripsDirIn(shared))
        assertEquals(
            File("/storage/emulated/0/Documents/${AppRoot.FOLDER}/vehicles/v1.jpg"),
            File(AppRoot.vehiclesDirIn(shared), "v1.jpg"),
        )
    }

    // ---- the label the UI shows --------------------------------------------

    @Test
    fun `the label names the folder and nothing else`() {
        // The UI says where the files are; it must not promise a path that differs
        // from the one AppRoot actually resolves. Both halves come from the same
        // constants, so this asserts the sentence is the folder, not a paraphrase.
        val label = AppRoot.sharedRootLabel()

        assertTrue("label must name the folder: $label", label.contains(AppRoot.FOLDER))
        assertFalse("label must not be an absolute path: $label", label.startsWith("/"))
    }

    // ---- the folder name is spelled once ------------------------------------

    @Test
    fun `the reader of the other root asks for the private root by name`() {
        // TripStore lists both folders so a storage grant never hides a drive. Its second
        // call has to be the *private* one — if it ever resolves through base(), both
        // entries are the folder in use, the list silently stops showing old drives, and
        // nothing fails. So the wiring is pinned, not the behaviour: this is the one place
        // that may name the private root outside base().
        val source = mainKotlinSources().first { it.name == "AppRoot.kt" }.text
        val line = source.lineSequence().first { it.contains("fun privateTripsDir") }
        assertTrue(
            "privateTripsDir must resolve under privateRoot, not the granted base: $line",
            line.contains("tripsDirIn(privateRoot(context))"),
        )
    }

    @Test
    fun `the folder name is declared in exactly one place`() {
        // The constant is the single source of truth the tests and every caller read.
        // Two spellings is how a folder rename ends up half-applied - trips in the
        // new folder, photos beside them in the old one, invisible to both.
        val sources = mainKotlinSources()
        val declared = sources.filter {
            Regex("const val FOLDER\\s*=\\s*\"Dashboard\\+\\+\"").containsMatchIn(it.text)
        }
        assertEquals(
            "AppRoot.FOLDER must be the only declaration of the folder name",
            listOf("AppRoot.kt"),
            declared.map { it.name },
        )

        val spelled = sources.filter {
            it.name != "AppRoot.kt" && it.text.contains("\"Dashboard++\"")
        }
        assertTrue(
            "these files spell the shared folder name themselves instead of reading " +
                "AppRoot.FOLDER: " + spelled.map { it.name },
            spelled.isEmpty(),
        )
    }

    /**
     * Every main-source Kotlin file, with the file name it is looked up by in the
     * failure messages above. The root search is the same one
     * [com.alefinot.dashboardpp.vehicles.AppXmlWiringTest] runs: Gradle starts unit
     * tests in the module directory, but the prefix differs across configurations,
     * so each level upward is tried with the plausible module prefixes.
     */
    private fun mainKotlinSources(): List<Src> {
        val rel = "src/main/java/com/alefinot/dashboardpp"
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (p in listOf("", "app/", "android/app/")) {
                val root = File(dir, p + rel)
                if (root.isDirectory) {
                    return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
                        .map { Src(it.name, it.readText()) }
                        .toList()
                }
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("cannot find $rel walking up from " + System.getProperty("user.dir"))
    }

    /** One main-source file, named the way the failure messages above quote it. */
    private class Src(val name: String, val text: String)

    /** A real directory on disk, for the cases where a file must genuinely exist. */
    private fun tempDir(name: String): File {
        val d = File(System.getProperty("java.io.tmpdir"), "approot-" + System.nanoTime() + "-" + name)
        assertTrue("could not create test dir $d", d.mkdirs())
        d.deleteOnExit()
        return d
    }
}
