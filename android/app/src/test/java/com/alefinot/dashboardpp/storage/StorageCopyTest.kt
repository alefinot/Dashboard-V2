package com.alefinot.dashboardpp.storage

import com.alefinot.dashboardpp.storage.AppRoot.GrantPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the app is allowed to promise, per Android version.
 *
 * The bug this was written for: on an Android 10 tablet the Storage card offered "GRANT STORAGE
 * ACCESS" and the Trips header said "grant storage access in App Settings". Android 10 has no All
 * files access (it arrived in API 30), so the button opened an App-info page with no storage entry
 * (measured: a Mi 9 on MIUI 12 offers AUTO-START, Displays and Manage Space) and the owner hunted
 * for a switch that had never existed. Same sentence, three devices, two of them true.
 *
 * **This file also carries the story of being wrong about the other half.** It asserted for a while
 * that Android 10 was a dead end - `GrantPath.NONE`, "neither way works there" - on the strength of
 * a general sentence about `requestLegacyExternalStorage` not applying to apps that target 30+.
 * That sentence holds for Android 11 and newer devices and not for Android 10 devices, and nobody
 * looked at a device to find out: on a Mi 9 Lite (MIUI 12) and a Galaxy Tab A, both on Android 10,
 * a targetSdk-35 install carrying the flag reports `LEGACY_STORAGE: allow`, gets the storage
 * dialog, and then creates and writes `Documents/Dashboard++/trips`. A test can be green,
 * well-argued, and cost an owner a folder they could have had for one tap.
 *
 * So the assertions here are pinned to measurements rather than to recollection, and where the
 * wording of an Android 11+ device is concerned nothing has changed: what the app may promise is
 * still decided by the version, as a parameter ([AppRoot.grantPath]) with the sentences as pure
 * functions of it ([StorageCopy]).
 */
class StorageCopyTest {

    // ---------------------------------------------------------------- the version decides

    @Test
    fun `android 8_1 through 10 are the runtime-permission path`() {
        assertEquals(GrantPath.RUNTIME_PERMISSION, AppRoot.grantPath(26))
        assertEquals(GrantPath.RUNTIME_PERMISSION, AppRoot.grantPath(27))
        assertEquals(GrantPath.RUNTIME_PERMISSION, AppRoot.grantPath(28))
        // Android 10 measures as a runtime-permission device, not a dead end: the flag in the
        // manifest is honoured there whatever this app targets, so WRITE_EXTERNAL_STORAGE opens
        // public Documents. Bench, 2026-11-06, two OEMs; see AppRoot.isGranted.
        assertEquals(GrantPath.RUNTIME_PERMISSION, AppRoot.grantPath(29))
    }

    @Test
    fun `the line between the two ways in is api 30`() {
        // Exactly where the platform draws it: All files access is invented in Android 11, and the
        // same release is where requestLegacyExternalStorage stops being honoured. Both switches
        // flip on the same number, so this is the only line worth pinning.
        assertEquals(GrantPath.RUNTIME_PERMISSION, AppRoot.grantPath(29))
        assertEquals(GrantPath.ALL_FILES, AppRoot.grantPath(30))
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
    fun `every android version's card offers the door that version has`() {
        assertEquals("STORAGE ACCESS GRANTED", StorageCopy.grantButton(true, GrantPath.ALL_FILES))
        assertEquals("STORAGE ACCESS GRANTED", StorageCopy.grantButton(true, GrantPath.RUNTIME_PERMISSION))
        // Android 11+: a settings page to hand them.
        assertEquals("GRANT STORAGE ACCESS", StorageCopy.grantButton(false, GrantPath.ALL_FILES))
        // Android 8.1-10: no page, but the app can raise the system's dialog itself. Saying
        // "ALLOW" rather than "GRANT" is the difference between the two verbs the owner is about to
        // perform, and this button used to be missing here entirely.
        assertEquals("ALLOW STORAGE", StorageCopy.grantButton(false, GrantPath.RUNTIME_PERMISSION))
    }

    // ---------------------------------------------------------------- the sentences

    @Test
    fun `the storage card counts only the folder it names and admits the rest`() {
        // The card's headline is `<folder> · counts`, so a count that includes files outside that
        // folder contradicts the label two words above it. Bench, 2026-11-06: a tablet that had
        // just been granted storage read `Documents/Dashboard++ · 5 trip files` over an empty
        // folder, because the number came from a list that reads both roots.
        assertEquals(
            "\u00b7 0 trip files \u00b7 0 cars",
            StorageCopy.cardCounts(0, 0, 0, false),
        )
        assertEquals(
            "\u00b7 3 trip files \u00b7 1 car",
            StorageCopy.cardCounts(3, 1, 0, false),
        )
        assertEquals(
            "\u00b7 0 trip files \u00b7 0 cars \u00b7 + 5 trip files in the app's own folder",
            StorageCopy.cardCounts(0, 0, 5, true),
        )
        assertEquals(
            "\u00b7 1 trip file \u00b7 2 cars \u00b7 + 1 trip file in the app's own folder",
            StorageCopy.cardCounts(1, 2, 1, true),
        )
        assertEquals(
            // The rest is not always the app's own folder: revoke All files access and the
            // recorder moves to it, leaving drives behind in Documents. Then only a path is
            // honest, so the tail stays the unnamed one the Trips page uses.
            "\u00b7 2 trip files \u00b7 0 cars \u00b7 + 4 trip files in another folder",
            StorageCopy.cardCounts(2, 0, 4, false),
        )
    }

    @Test
    fun `no runtime-path sentence points at an all files switch`() {
        // The guard survives the fix, only narrower and true: below API 30 there is no All files
        // access, so a sentence that sends an Android 8.1-10 owner hunting for one is still a lie.
        // What those sentences may do is name the card they are standing on.
        for (text in runtimeSentences()) {
            val lowered = text.lowercase()
            val promises = listOf("all files", "app info", "grant storage access")
                .filter { lowered.contains(it) }
            assertTrue(
                "There is no All files access below API 30, but the app says: \"$text\"",
                promises.isEmpty(),
            )
        }
    }

    @Test
    fun `the runtime-path sentences say where the files are and how to move them`() {
        // Degradation, stated - and unlike the Android 11+ copy, the fix here is inside the app:
        // the owner can act on these sentences without leaving for a settings page.
        val notes = runtimeSentences()
        assertTrue(notes.any { it.contains("app's own folder") })
        assertTrue(notes.any { it.contains("allow") })
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
    fun `the runtime path is told to allow the permission rather than hunt for a switch`() {
        val note = StorageCopy.storageNote(false, GrantPath.RUNTIME_PERMISSION)
        assertTrue(note, note.contains("asks for storage permission"))
        // The card's own button has to be named in the sentence under it, or the owner reads the
        // sentence, sees no control it describes, and concludes the app is broken.
        assertTrue(note, note.contains("ALLOW STORAGE"))
        assertTrue(
            StorageCopy.tripsNote(false, GrantPath.RUNTIME_PERMISSION, "Documents/Dashboard++", "trips")
                .contains("allow storage"),
        )
        assertTrue(
            StorageCopy.tripFileNote(false, GrantPath.RUNTIME_PERMISSION, "Documents/Dashboard++", "trips")
                .contains("ALLOW STORAGE"),
        )
    }

    @Test
    fun `a granted card and a granted trip both name the shared folder`() {
        val note = StorageCopy.tripsNote(true, GrantPath.RUNTIME_PERMISSION, "Documents/Dashboard++", "trips")
        assertTrue(note, note.contains("Documents/Dashboard++/trips"))
        // The file's own location, not the app's capability: a drive recorded while the grant was
        // on stays where it was written after it is revoked.
        val file = StorageCopy.tripFileNote(true, GrantPath.RUNTIME_PERMISSION, "Documents/Dashboard++", "trips")
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

    /** Everything the app says out loud on a device where the answer is the storage permission. */
    private fun runtimeSentences(): List<String> = listOf(
        StorageCopy.storageNote(false, GrantPath.RUNTIME_PERMISSION),
        StorageCopy.tripsNote(false, GrantPath.RUNTIME_PERMISSION, "Documents/Dashboard++", "trips"),
        StorageCopy.tripFileNote(false, GrantPath.RUNTIME_PERMISSION, "Documents/Dashboard++", "trips"),
    )

    private fun mainSources(): List<File> {
        val root = File("src/main").takeIf { it.isDirectory } ?: File("android/app/src/main")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
