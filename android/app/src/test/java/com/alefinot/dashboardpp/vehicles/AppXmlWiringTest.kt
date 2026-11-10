package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The parts of this app that are configured in XML, which the compiler cannot see.
 *
 * Two of those pieces of wiring decide whether the editor's photo buttons work, and both fail
 * in the same unhelpful way - by making the app say something that is not true:
 *
 * - **The FileProvider roots.** A camera capture is handed to another app as a `content://`
 *   URI over one of the roots in `res/xml/files_path.xml`. If the directory
 *   [VehiclePhotoStore.captureFile] writes into is not listed, `getUriForFile` throws
 *   `IllegalArgumentException`, the store answers "no capture to offer", and the editor tells
 *   the user there is no camera on a tablet that has one. Nothing compiles differently, no lint
 *   check notices; on a real tablet the only symptom is that sentence. (This file exists
 *   because that exact mistake was made in this branch: `captures/` was declared in the store's
 *   code and in its docs, and never added to the XML. It took a tablet to notice.)
 * - **Package visibility.** From Android 11 an app that does not declare the `IMAGE_CAPTURE`
 *   action in `<queries>` cannot resolve the camera activity, so `TAKE PHOTO` can only ever
 *   report that there is no camera - on API 28 the same code works fine, which is how this one
 *   survives review on a development tablet.
 *
 * The third thing checked here is the manifest's permission block, which fails the other way:
 * an app that asks for more than it uses is not broken, it is mistrusted. Since the one
 * connection story, that means the BLE block present and the Wi-Fi / network-state permissions
 * absent - asserted, because a permission is easy to leave behind when the code that read it is
 * deleted, and the listing is what a reviewer reads.
 *
 * So these tests reach for the source **text** rather than the running objects. A JVM unit test
 * has no Android `Context`, so it cannot ask the store for a capture file or the provider for a
 * URI, and the assertion that actually matters is that the names which have to agree at runtime
 * appear in the two files that mention them. Text tests on purpose: brittle in the way that
 * matters (rename the directory, or drop the block, and this fails), cheap in the way that
 * matters (no emulator, no Robolectric, no tablet).
 */
class AppXmlWiringTest {

    /** The scratch capture directory has to be a published root, or a capture has no URI. */
    @Test
    fun `the scratch capture directory is a declared provider root`() {
        val xml = moduleFile("src/main/res/xml/files_path.xml")
        assertTrue(
            "files_path.xml must publish the scratch capture root, or a camera capture has no " +
                "URI to hand over: " + xml.lines().joinToString(" | "),
            Regex("path\\s*=\\s*\"captures/\"").containsMatchIn(xml),
        )
    }

    /** The trip share came first, and must not be dropped while adding another root. */
    @Test
    fun `the trip export root is still declared`() {
        val xml = moduleFile("src/main/res/xml/files_path.xml")
        assertTrue(
            "files_path.xml lost the exports root the share sheet depends on",
            Regex("path\\s*=\\s*\"exports/\"").containsMatchIn(xml),
        )
    }

    /**
     * The other half of the first agreement: the name the store builds its scratch path from.
     * If this constant and the XML drift apart the failure is silent at runtime, so it is loud
     * here instead.
     */
    @Test
    fun `the store names its capture directory the way the provider declares it`() {
        val src = moduleFile("src/main/java/com/alefinot/dashboardpp/vehicles/VehiclePhotoStore.kt")
        val m = Regex("CAPTURE_DIR\\s*=\\s*\"([^\"]+)\"").find(src)
        if (m == null) {
            // Assert.fail returns void, which cannot be the right-hand side of an elvis; the
            // throw is what keeps `m` a MatchResult below.
            throw AssertionError(
                "VehiclePhotoStore no longer declares CAPTURE_DIR - update this test and " +
                    "files_path.xml together",
            )
        }
        val xml = moduleFile("src/main/res/xml/files_path.xml")
        assertTrue(
            "the store writes captures to \"${m.groupValues[1]}\" while files_path.xml publishes " +
                "something else - getUriForFile will throw and the editor will claim there is no camera",
            xml.contains("path=\"${m.groupValues[1]}/\""),
        )
    }

    /**
     * Without this block the camera route is dead on Android 11+ while working on the API 28
     * tablet it was developed against, and the only evidence in the field is a button that
     * insists there is no camera.
     */
    @Test
    fun `the manifest declares visibility for the camera capture intent`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml")
        val queries = Regex("<queries>(.*?)</queries>", RegexOption.DOT_MATCHES_ALL).find(manifest)
        if (queries == null) {
            throw AssertionError(
                "AndroidManifest.xml has no <queries> block: on API 30+ the editor cannot resolve " +
                    "an IMAGE_CAPTURE intent, so TAKE PHOTO can only report that no camera exists",
            )
        }
        assertTrue(
            "the <queries> block no longer covers IMAGE_CAPTURE - TAKE PHOTO will not launch a " +
                "camera on Android 11+",
            queries.value.contains("android.media.action.IMAGE_CAPTURE"),
        )
    }

    /**
     * The radio the app actually uses, and none of the ones it no longer can.
     *
     * The BLE block is what the connection story runs on, and the three permissions removed
     * with the HTTP transport were read only by the discovery sweep (`Nsd`, the subnet scan,
     * the verifier) and by the screens that explained why a LAN could not be seen. A leftover
     * permission is worse than dead code here: the permission list is what a reviewer, and the
     * Play listing, read as a statement about what an app does with a phone. `INTERNET` stays -
     * the trip map fetches OSM tiles - and the BLE `uses-feature` stays `required="false"`,
     * which is a shipping decision (who can install the APK) rather than a code one.
     */
    @Test
    fun `the manifest asks for the radio the app uses and none of the ones it dropped`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml")
        val declared = Regex("uses-permission[^>]*android\\.permission\\.([A-Z_]+)")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        for (needed in listOf(
            "BLUETOOTH", "BLUETOOTH_ADMIN", "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "INTERNET",
        )) {
            assertTrue(
                "the manifest no longer declares $needed - the link, the scan or the map " +
                    "tiles stops working with no compile error to say so: $declared",
                declared.contains(needed),
            )
        }
        for (dropped in listOf(
            "ACCESS_WIFI_STATE", "CHANGE_WIFI_MULTICAST_STATE", "ACCESS_NETWORK_STATE",
            "ACCESS_COARSE_LOCATION",
        )) {
            assertTrue(
                "$dropped is back in the manifest: nothing in this app reaches a network any " +
                    "more, and a permission nobody reads is a promise the listing still makes",
                !declared.contains(dropped),
            )
        }
    }

    /**
     * The folder the app writes into is shared storage or nothing (shared storage plan §2).
     *
     * `Android/data/<pkg>/files/` is where the files used to go, and it is the reason the plan
     * exists: a folder a file manager hides, a USB mount never offers, and a user cannot clean up
     * by hand after a bad drive. Two classes resolve that folder, so both are banned from naming
     * it - if either went back to it alone, the trips would land where the Trips tab is not
     * looking, or a card's photo where its record cannot find it, and the two halves would each
     * look correct on the bench.
     *
     * Only [com.alefinot.dashboardpp.storage.AppRoot] may name it, and only as the fallback the
     * grant-less path takes.
     */
    @Test
    fun `the stores resolve their folder through AppRoot and never name the private one`() {
        for (rel in listOf(
            "src/main/java/com/alefinot/dashboardpp/trips/TripStore.kt",
            "src/main/java/com/alefinot/dashboardpp/vehicles/VehiclePhotoStore.kt",
        )) {
            val src = moduleFile(rel)
            val code = src.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .joinToString("\n")
            for (banned in listOf("getExternalFilesDir", "Android/data")) {
                assertTrue(
                    "$rel names $banned in code: it must ask AppRoot.tripsDir / " +
                        "AppRoot.vehiclesDir, which decide shared-versus-private once for the " +
                        "whole app",
                    !code.contains(banned),
                )
            }
            assertTrue(
                "$rel no longer asks AppRoot where its files go, so nothing keeps the recorder, " +
                    "the list and the share sheet on the same folder",
                src.contains("AppRoot.tripsDir") || src.contains("AppRoot.vehiclesDir"),
            )
        }
    }

    /**
     * The storage permission block, stated rather than assumed (shared storage plan §1).
     *
     * `MANAGE_EXTERNAL_STORAGE` is what opens `Documents/Dashboard++`; without the declaration
     * the settings page the app jumps to has no switch for this app, and the app degrades to its
     * private folder forever while its Storage card offers a button that cannot work. The legacy
     * permission is asserted *with its cap*: scoped storage applies by default to apps that target
     * Android 10 and up, so the cap sits at 29 - on Android 10 the legacy opt-out below makes this
     * permission the whole answer, and on API 30+ it grants nothing further and would be a promise
     * the listing makes that nothing reads (the same reason the dropped Wi-Fi permissions above are
     * asserted absent).
     *
     * `requestLegacyExternalStorage` is asserted because the cap is only half of it: without the
     * flag an Android 10 device is scoped, `WRITE_EXTERNAL_STORAGE` buys nothing there, and the
     * app's Android 10 branch of [com.alefinot.dashboardpp.storage.AppRoot.isGranted] would be a
     * claim nothing backs up. Both halves were measured on a Mi 9 Lite and a Galaxy Tab A.
     */
    @Test
    fun `the manifest declares the storage access the app actually uses`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml")
        val declared = Regex("uses-permission[^>]*android\\.permission\\.([A-Z_]+)")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        for (needed in listOf("MANAGE_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE")) {
            assertTrue(
                "the manifest no longer declares $needed - the folder the app promises to save " +
                    "into stops existing on the devices that need it: $declared",
                declared.contains(needed),
            )
        }
        val legacy = Regex("uses-permission[^>]*android\\.permission\\.WRITE_EXTERNAL_STORAGE[^>]*>")
            .find(manifest)
        assertTrue(
            "WRITE_EXTERNAL_STORAGE must carry maxSdkVersion=\"29\": it is the answer on Android " +
                "8.1 through 10 (Android 10 because of requestLegacyExternalStorage, asserted just " +
                "below) and grants nothing from Android 11 up, where an uncapped declaration would " +
                "be a permission the listing still promises to users whose devices ignore it",
            legacy != null && legacy.value.contains("maxSdkVersion=\"29\""),
        )
        assertTrue(
            "the manifest must carry android:requestLegacyExternalStorage=\"true\": without it an " +
                "Android 10 device runs this app scoped, the storage permission opens nothing, and " +
                "AppRoot.isGranted's Android 10 branch quietly always answers false",
            "android:requestLegacyExternalStorage=\"true\"" in manifest,
        )
    }

    /**
     * The app's own updater needs both halves of the same agreement the camera capture does:
     * the manifest permission that permits handing an APK to the package installer, and a
     * published provider root that can name the file it hands. Either half missing fails the
     * same quiet way — INSTALL reports a refusal with no compile error anywhere — so the pair
     * is asserted here, together with the third name that has to agree: `AppUpdate.APK_DIR`
     * in code and the folder in the XML, the same agreement the camera capture's directory
     * is checked under.
     */
    @Test
    fun `the app updater may hand its downloaded APK to the installer`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml")
        val declared = Regex("uses-permission[^>]*android\\.permission\\.([A-Z_]+)")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertTrue(
            "REQUEST_INSTALL_PACKAGES is gone from the manifest: the App Update card can fetch " +
                "an APK all day and never hand it to the installer - $declared",
            declared.contains("REQUEST_INSTALL_PACKAGES"),
        )
        val updater = moduleFile("src/main/java/com/alefinot/dashboardpp/update/AppUpdate.kt")
        val m = Regex("APK_DIR\\s*=\\s*\"([^\"]+)\"").find(updater)
        if (m == null) {
            throw AssertionError(
                "AppUpdate no longer declares APK_DIR - update this test and files_path.xml " +
                    "together",
            )
        }
        val xml = moduleFile("src/main/res/xml/files_path.xml")
        assertTrue(
            "the updater writes APKs to \"${m.groupValues[1]}\" while files_path.xml publishes " +
                "something else - getUriForFile will throw and INSTALL can only report a failure",
            xml.contains("path=\"${m.groupValues[1]}/\""),
        )
    }

    /**
     * The shared folder has to be a published root, or a drive cannot be shared (shared storage
     * plan §4), and both sides of that agreement are checked here.
     *
     * [com.alefinot.dashboardpp.storage.AppRoot.FOLDER] is the only place the folder name is
     * written in code; `res/xml/files_path.xml` has to spell it the same way, because
     * `getUriForFile` throws `IllegalArgumentException` for a file under no declared root. On a
     * real phone the symptom is a SHARE CSV button that does nothing — `startActivity` throws
     * inside the chooser, which is not a path any unit test on the JVM can walk.
     *
     * The private root is asserted alongside the shared one: when the grant is missing the CSVs
     * live under `Android/data/…/files/trips`, and without that second root the share sheet
     * would be broken on exactly the devices the degraded path exists for.
     */
    @Test
    fun `the shared folders are published roots that agree with the folder constant`() {
        val appRoot = moduleFile("src/main/java/com/alefinot/dashboardpp/storage/AppRoot.kt")
        val folder = Regex("FOLDER\\s*=\\s*\"([^\"]+)\"").find(appRoot)
        if (folder == null) {
            throw AssertionError(
                "AppRoot no longer declares FOLDER - update this test and files_path.xml together",
            )
        }
        val xml = moduleFile("src/main/res/xml/files_path.xml")
        val name = folder.groupValues[1]
        assertTrue(
            "files_path.xml must publish the shared drive folder, or SHARE CSV throws " +
                "IllegalArgumentException for every recorded drive",
            xml.contains("path=\"Documents/$name/trips/\""),
        )
        assertTrue(
            "files_path.xml must publish the shared vehicle folder alongside it",
            xml.contains("path=\"Documents/$name/vehicles/\""),
        )
        assertTrue(
            "files_path.xml lost the private trips root: with the grant missing, the CSVs are in " +
                "the app's own folder and the share sheet can still only hand out a declared root",
            Regex("external-files-path[^>]*path\\s*=\\s*\"trips/\"").containsMatchIn(xml),
        )
    }

    /**
     * A shared CSV is the recorded file (shared storage plan §4). [com.alefinot.dashboardpp.trips.TripExport]
     * used to copy the CSV into the cache and hand that copy over, which made a second file that
     * looked like the drive and was not it - the one in the user's folder was the original, the
     * one they had emailed was scratch the OS might clear. The copy is gone, so nothing should
     * quietly grow it back; the GPX keeps its cache because it is built at the moment of sharing.
     */
    @Test
    fun `a shared CSV is the recorded file and not a cache copy`() {
        val export = moduleFile("src/main/java/com/alefinot/dashboardpp/trips/TripExport.kt")
        assertTrue(
            "TripExport grew a csv() copy again: the share sheet must read the file in " +
                "Documents/Dashboard++/trips itself, not a duplicate under cacheDir/exports",
            !Regex("fun\\s+csv\\s*\\(").containsMatchIn(export),
        )
        assertTrue(
            "the GPX still has to be built somewhere - cacheDir/exports is the only writable " +
                "place when the app has no storage grant",
            Regex("cacheDir").containsMatchIn(export),
        )
        val vm = moduleFile("src/main/java/com/alefinot/dashboardpp/viewmodel/TripsViewModel.kt")
        assertTrue(
            "shareCsv must hand the recorded file to the share sheet; it is calling something " +
                "else, which means a copy is back",
            Regex("shareCsv\\(row: TripRow\\): Intent\\s*=\\s*export\\.shareIntent\\(row\\.file").containsMatchIn(vm),
        )
    }

    /**
     * Module-relative read. Gradle starts unit tests in the module directory (`android/app`) but
     * prefixes differ across configurations and IDEs, so each level from here upward is tried
     * with the plausible module prefixes before giving up. A guard test that fails because it
     * could not find its own input is worse than no guard test: it teaches you to ignore it.
     */
    private fun moduleFile(rel: String): String {
        val prefixes = listOf("", "app/", "android/app/")
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (p in prefixes) {
                val f = File(dir, p + rel)
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "cannot find $rel (or app/$rel, or android/app/$rel) walking up from " +
                System.getProperty("user.dir"),
        )
    }
}
