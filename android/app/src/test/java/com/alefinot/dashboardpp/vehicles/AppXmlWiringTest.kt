package com.alefinot.dashboardpp.vehicles

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `the store names its capture directory the way the provider declares it`() {
        val src = moduleFile("src/main/java/com/alefinot/dashboardpp/vehicles/VehiclePhotoStore.kt")
        val m = Regex("CAPTURE_DIR\\s*=\\s*\"([^\"]+)\"").find(src)
        if (m == null) {
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
