package com.alefinot.dashboardpp.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The release list's reading half, checked against canned GitHub payloads.
 *
 * None of this needs a network or a Context: the selection rules (what is offered, in what
 * order) are exactly the rules a test can hold still, and they are the ones that decide which
 * 1.5 MB image a driver ends up pushing over the air. The wire side — OkHttp, the cache file,
 * the handoff to [com.alefinot.dashboardpp.ble.OtaController] — is the existing install path,
 * covered on the bench.
 */
class FirmwareReleasesTest {

    /** A payload in the shape `api.github.com/repos/.../releases` answers with. */
    private fun releaseJson(
        tag: String,
        title: String,
        draft: Boolean = false,
        assets: String = assetJson(),
    ): String = """
        {
          "tag_name": "$tag",
          "name": "$title",
          "draft": $draft,
          "assets": $assets
        }
    """.trimIndent()

    private fun assetJson(
        name: String = FirmwareReleases.ASSET_NAME,
        state: String = "uploaded",
        size: Long = 1_543_210L,
        url: String = "https://objects.example/firmware.bin",
    ): String = """
        [{ "name": "$name", "state": "$state", "size": $size,
           "browser_download_url": "$url" }]
    """.trimIndent()

    /** The three rules that keep the dropdown honest: no drafts, no missing image, newest first. */
    @Test
    fun theOfferedVersionsAreTheReleasedOnesNewestFirst() {
        val body = """
            [
              ${releaseJson("v2.0.3", "V2.0.3")},
              ${releaseJson("v2.0.10", "V2.0.10")},
              ${releaseJson("v2.0.9", "V2.0.9", assets = assetJson(name = "app-debug.apk"))},
              ${releaseJson("v2.0.8", "V2.0.8", assets = assetJson(state = "uploader_pending"))},
              ${releaseJson("v2.0.7", "V2.0.7", draft = true)}
            ]
        """.trimIndent()
        val got = FirmwareReleases.parseReleases(body)
        assertEquals("an unpublished or assetless release was offered",
            listOf("V2.0.10", "V2.0.3"),
            got?.map { it.title },
        )
        // The version every comparison runs on: the tag without its leading v, so it matches
        // DPPVER= and build_version verbatim.
        assertEquals("2.0.10", got!![0].version)
        assertEquals(1_543_210L, got[0].size)
        assertEquals("https://objects.example/firmware.bin", got[0].downloadUrl)
    }

    /** A release with no `name` still has a tag to show; an empty array is an answer, not an error. */
    @Test
    fun unnamedReleasesShowTheirTagAndAnEmptyListIsHonest() {
        val body = """[{ "tag_name": "v1.0.0", "name": "", "assets": ${assetJson()} }]"""
        assertEquals(listOf("v1.0.0"), FirmwareReleases.parseReleases(body)?.map { it.title })
        assertEquals(emptyList<Any>(), FirmwareReleases.parseReleases("[]"))
    }

    /** A rate-limit notice or a login wall is "GitHub answered something else" — null, not empty. */
    @Test
    fun somethingOtherThanAnArrayIsRefused() {
        assertNull(FirmwareReleases.parseReleases("""{"message":"API rate limit exceeded"}"""))
        assertNull(FirmwareReleases.parseReleases("<html><body>Login</body></html>"))
        assertNull(FirmwareReleases.parseReleases("not json at all"))
    }

    /** `v2.0.3` / `V2.0.3` / `2.0.3` are one version. */
    @Test
    fun theTagNormalisesToOneSpelling() {
        assertEquals("2.0.3", FirmwareReleases.versionOf("v2.0.3"))
        assertEquals("2.0.3", FirmwareReleases.versionOf("V2.0.3"))
        assertEquals("2.0.3", FirmwareReleases.versionOf("2.0.3"))
    }

    /**
     * The order the dropdown is built from. `2.0.10` after `2.0.3` is the whole reason this
     * function exists — a plain string compare puts `2.0.10` inside `2.0.3` and offers the
     * older image as the newest.
     */
    @Test
    fun versionsSortByTheirNumbersNotTheirCharacters() {
        fun key(v: String) = FirmwareReleases.versionKey(v)
        org.junit.Assert.assertTrue("2.0.10 sorted below 2.0.3", key("2.0.10") > key("2.0.3"))
        org.junit.Assert.assertTrue("2.0.10 sorted below 2.0.4", key("2.0.10") > key("2.0.4"))
        org.junit.Assert.assertTrue("10.0.0 sorted below 9.9.9", key("10.0.0") > key("9.9.9"))
        // Something this code does not understand sorts below every number it does.
        org.junit.Assert.assertTrue(
            "an unknown scheme outranked a number",
            key("2.0.3") > key("nightly-2026-11-01"),
        )
    }

    /** The cache name is a filesystem name: the version string is not trusted to be tidy. */
    @Test
    fun theCacheNameIsSafeForWhateverTheTagSays() {
        assertEquals("dpp-2.0.3.bin", FirmwareReleases.fileNameFor("2.0.3"))
        assertEquals("dpp-a_b_c.bin", FirmwareReleases.fileNameFor("a/b\u0000c"))
    }
}
