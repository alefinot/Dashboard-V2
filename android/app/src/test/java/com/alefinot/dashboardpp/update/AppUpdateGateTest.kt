package com.alefinot.dashboardpp.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app updater's deciding half, checked without a network or a Context.
 *
 * [AppUpdate.pickOffer] is the whole user-visible promise — "newest release only, and only
 * when it is newer than what runs" — and it is the part that can be held still on a JVM: the
 * installer door after it belongs to Android itself and is covered on the bench. The parse
 * half is asserted here only where it is the app's own question: which asset of a release
 * makes the release offerable **to the phone**.
 */
class AppUpdateGateTest {

    private fun releaseJson(tag: String, title: String, assets: String): String = """
        { "tag_name": "$tag", "name": "$title", "draft": false, "assets": $assets }
    """.trimIndent()

    private fun asset(
        name: String,
        state: String = "uploaded",
        size: Long = 11_650_128L,
    ): String = """
        [{ "name": "$name", "state": "$state", "size": $size,
           "browser_download_url": "https://objects.example/$name" }]
    """.trimIndent()

    private fun release(tag: String): FirmwareRelease = FirmwareRelease(
        tag = tag,
        title = tag.uppercase(),
        version = FirmwareReleases.versionOf(tag),
        size = 11_650_128L,
        downloadUrl = "https://objects.example/app-debug.apk",
    )

    /** The newest release newer than the running one wins; equal or older is no offer at all. */
    @Test
    fun onlyANewerReleaseIsOffered() {
        val releases = listOf(release("v2.0.4"), release("v2.0.3"), release("v2.0.1"))
        assertEquals("2.0.4", AppUpdate.pickOffer(releases, "2.0.3")?.version)
        // "newest only": a list in arbitrary order still offers the newest, not the first.
        val shuffled = listOf(release("v2.0.1"), release("v2.0.10"), release("v2.0.4"))
        assertEquals("2.0.10", AppUpdate.pickOffer(shuffled, "2.0.4")?.version)
        // The same release as the running app is not an update.
        assertNull(AppUpdate.pickOffer(releases, "2.0.4"))
        // Neither is an older one — the installer would refuse the downgrade, so the card does.
        assertNull(AppUpdate.pickOffer(releases, "2.0.9"))
    }

    /** A scheme the comparator does not understand never outranks the number it runs. */
    @Test
    fun anUnknownVersionSchemeIsNeverOfferedOverANumber() {
        val nightlies = listOf(release("nightly-2026-11-10"))
        assertNull(AppUpdate.pickOffer(nightlies, "2.0.3"))
    }

    /** An empty list is an honest "nothing released", not an offer and not a crash. */
    @Test
    fun nothingReleasedIsNothingOffered() {
        assertNull(AppUpdate.pickOffer(emptyList(), "2.0.3"))
    }

    /**
     * The same GitHub list answers both readers, and each asks for its own asset: a release
     * carrying only the unit's `firmware.bin` is never offered to the phone, and one carrying
     * only the app's APK is never offered to the unit.
     */
    @Test
    fun theAppReaderAsksForTheApkAndNotTheFirmware() {
        val body = """
            [
              ${releaseJson("v2.0.4", "V2.0.4", asset("app-debug.apk"))},
              ${releaseJson("v2.0.3", "V2.0.3", asset(FirmwareReleases.ASSET_NAME))},
              ${releaseJson("v2.0.2", "V2.0.2", asset("app-debug.apk", state = "uploader_pending"))}
            ]
        """.trimIndent()
        val forApp = FirmwareReleases.parseReleases(body, FirmwareReleases.APK_ASSET_NAME)
        assertEquals(listOf("V2.0.4"), forApp?.map { it.title })
        val forUnit = FirmwareReleases.parseReleases(body)
        assertEquals(listOf("V2.0.3"), forUnit?.map { it.title })
    }

    /** The cache name is a filesystem name, and it has to end where the installer expects. */
    @Test
    fun theApkCacheNameIsSafeAndEndsApk() {
        assertEquals("app-2.0.4.apk", AppUpdate.apkNameFor("2.0.4"))
        assertEquals("app-a_b_c.apk", AppUpdate.apkNameFor("a/b\u0000c"))
    }
}
