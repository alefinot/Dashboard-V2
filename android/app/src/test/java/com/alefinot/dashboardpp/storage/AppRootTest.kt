package com.alefinot.dashboardpp.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRootTest {

    // ---- folder identity: is this path the folder a sentence just named? ----------

    @Test
    fun `a folder is recognised even when it is spelled another way`() {
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
        val docs = tempDir("docs")

        assertNull(AppRoot.sharedRootFor(granted = false, docsRoot = docs, canCreate = { true }))
    }

    @Test
    fun `the fallback is chosen when the folder cannot be made`() {
        val docs = tempDir("docs")

        assertNull(AppRoot.sharedRootFor(granted = true, docsRoot = docs, canCreate = { false }))
    }

    @Test
    fun `the fallback is chosen when there is no Documents volume at all`() {
        assertNull(AppRoot.sharedRootFor(granted = true, docsRoot = null, canCreate = { true }))
    }

    @Test
    fun `a made-possible shared root is the folder the plan names`() {
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
        val private = tempDir("private")

        assertEquals(private, AppRoot.baseFor(null, private))
        assertNotNull(AppRoot.baseFor(null, File("/data/user/0/x/files")))
    }

    // ---- the same sub-directories under either root -------------------------

    @Test
    fun `both roots carry the same sub-directory names`() {
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
        val label = AppRoot.sharedRootLabel()

        assertTrue("label must name the folder: $label", label.contains(AppRoot.FOLDER))
        assertFalse("label must not be an absolute path: $label", label.startsWith("/"))
    }

    // ---- the folder name is spelled once ------------------------------------

    @Test
    fun `the reader of the other root asks for the private root by name`() {
        val source = mainKotlinSources().first { it.name == "AppRoot.kt" }.text
        val line = source.lineSequence().first { it.contains("fun privateTripsDir") }
        assertTrue(
            "privateTripsDir must resolve under privateRoot, not the granted base: $line",
            line.contains("tripsDirIn(privateRoot(context))"),
        )
    }

    @Test
    fun `the folder name is declared in exactly one place`() {
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
