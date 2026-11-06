package com.alefinot.dashboardpp.storage

import com.alefinot.dashboardpp.storage.AppRoot.GrantPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the app is allowed to promise, per Android version.
 *
 * The bug this pins: on an Android 10 tablet the Storage card offered "GRANT STORAGE ACCESS" and
 * the Trips header said "grant storage access in App Settings". Android 10 has no All files access
 * (it arrived in API 30) and `requestLegacyExternalStorage` stopped applying to an app targeting
 * 30+, so the button opened an App-info page with no storage entry (measured: a Mi 9 on MIUI 12
 * offers AUTO-START, Displays and Manage Space) and the owner hunted for a switch that had never
 * existed. Same sentence, three devices, two of them true.
 *
 * The version cannot be changed inside this process, so the version is a parameter
 * ([AppRoot.grantPath]) and the wording is a pure function of it ([StorageCopy]). A real device
 * still has to be looked at once — but the sentence that was wrong is now wrong on purpose, in a
 * test, before it is on anyone's screen.
 */
class StorageCopyTest {

    // ---------------------------------------------------------------- the version decides

    @Test
    fun `android 8 and 9 are the runtime-permission path`() {
        assertEquals(GrantPath.RUNTIME_PERMISSION, AppRoot.grantPath(27))
        assertEquals(GrantPath.RUNTIME_PERMISSION, AppRoot.grantPath(28))
    }

    @Test
    fun `android 10 is the dead end`() {
        // Neither way works there: no All files access yet, and legacy storage is opt-out only for
        // apps that target 29. This assertion is the whole reason StorageCopy exists.
        assertEquals(GrantPath.NONE, AppRoot.grantPath(29))
    }

    @Test
    fun `android 11 and up have a switch to point at`() {
        assertEquals(GrantPath.ALL_FILES, AppRoot.grantPath(30))
        assertEquals(GrantPath.ALL_FILES, AppRoot.grantPath(33))
        assertEquals(GrantPath.ALL_FILES, AppRoot.grantPath(35))
    }

    // ---------------------------------------------------------------- counting

    @Test
    fun `one of a thing is said as one of a thing`() {
        assertEquals("1 car", StorageCopy.cars(1))
        assertEquals("1 drive", StorageCopy.drives(1))
        assertEquals("1 trip file", StorageCopy.tripFiles(1))
        // The plural is the default, including for zero — "0 cars", as everywhere else in English.
        assertEquals("0 cars", StorageCopy.cars(0))
        assertEquals("3 drives", StorageCopy.drives(3))
        assertEquals("12 trip files", StorageCopy.tripFiles(12))
    }

    // ---------------------------------------------------------------- the button

    @Test
    fun `the storage card offers a button only where there is something to open`() {
        assertEquals("STORAGE ACCESS GRANTED", StorageCopy.grantButton(true, GrantPath.ALL_FILES))
        assertEquals("GRANT STORAGE ACCESS", StorageCopy.grantButton(false, GrantPath.ALL_FILES))
        // Android 10: no switch. Not a disabled button either — a control that does nothing reads
        // as broken hardware, while no control at all reads as "not offered here".
        assertNull(StorageCopy.grantButton(false, GrantPath.NONE))
        // Android 9: the switch is not in App settings, it is a dialog at startup.
        assertNull(StorageCopy.grantButton(false, GrantPath.RUNTIME_PERMISSION))
    }

    // ---------------------------------------------------------------- the sentences

    @Test
    fun `no sentence points an android 10 owner at a switch`() {
        for (text in android10Sentences()) {
            val lowered = text.lowercase()
            val promises = listOf("grant storage", "app settings", "app info", "all files")
                .filter { lowered.contains(it) }
            assertTrue(
                "Android 10 has nothing to grant, but the app says: \"$text\"",
                promises.isEmpty(),
            )
        }
    }

    @Test
    fun `the android 10 sentences still say where the files are`() {
        // Honest degradation, not silence: the owner still needs to know the files exist inside
        // the app, and that the app's own Share buttons are how they get out.
        val notes = android10Sentences()
        assertTrue(notes.any { it.contains("app's own folder") })
        assertTrue(notes.any { it.contains("Share") })
    }

    @Test
    fun `android 11 keeps the offer it has always made`() {
        // The other direction: fixing Android 10 must not talk the useful path out of the app.
        assertTrue(
            StorageCopy.storageNote(false, GrantPath.ALL_FILES).contains("Grant storage access"),
        )
        assertTrue(
            StorageCopy.tripsNote(false, GrantPath.ALL_FILES, "Documents/Dashboard++", "trips")
                .contains("App Settings"),
        )
        assertTrue(
            StorageCopy.tripFileNote(false, GrantPath.ALL_FILES, "Documents/Dashboard++", "trips")
                .contains("App Settings"),
        )
    }

    @Test
    fun `android 9 is told to allow the permission rather than hunt for a switch`() {
        val note = StorageCopy.storageNote(false, GrantPath.RUNTIME_PERMISSION)
        assertTrue(note, note.contains("asks for storage permission"))
        assertTrue(
            StorageCopy.tripsNote(false, GrantPath.RUNTIME_PERMISSION, "Documents/Dashboard++", "trips")
                .contains("allow storage"),
        )
    }

    @Test
    fun `a granted card and a granted trip both name the shared folder`() {
        val note = StorageCopy.tripsNote(true, GrantPath.NONE, "Documents/Dashboard++", "trips")
        assertTrue(note, note.contains("Documents/Dashboard++/trips"))
        // The file's own location, not the app's capability: a drive recorded while the grant was
        // on stays where it was written after it is revoked.
        val file = StorageCopy.tripFileNote(true, GrantPath.NONE, "Documents/Dashboard++", "trips")
        assertTrue(file, file.contains("Documents/Dashboard++/trips/"))
    }

    @Test
    fun `no storage sentence in the app says one drive`() {
        // A textual guard for the two screens that count things: the wording now comes from
        // StorageCopy, and "$n drives" hardcoded back into one of them would say "1 drives".
        val offenders = mainSources().mapNotNull { f ->
            val hits = f.readLines().mapIndexedNotNull { n, line ->
                if (Regex("""\$\{?\w*(size|count|tripFiles|cars)\}?\s+(drives|cars|trip files)""")
                        .containsMatchIn(line)
                ) "${f.name}:${n + 1}: ${line.trim()}" else null
            }
            if (hits.isEmpty()) null else f.name + "\n" + hits.joinToString("\n")
        }
        assertFalse(
            "Count these as StorageCopy.drives(...)/cars(...)/tripFiles(...) so \"1 drives\" " +
                "cannot come back:\n" + offenders.joinToString("\n"),
            offenders.isNotEmpty(),
        )
    }

    private fun android10Sentences(): List<String> = listOf(
        StorageCopy.storageNote(false, GrantPath.NONE),
        StorageCopy.tripsNote(false, GrantPath.NONE, "Documents/Dashboard++", "trips"),
        StorageCopy.tripFileNote(false, GrantPath.NONE, "Documents/Dashboard++", "trips"),
    )

    private fun mainSources(): List<File> {
        val root = File("src/main").takeIf { it.isDirectory } ?: File("android/app/src/main")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
