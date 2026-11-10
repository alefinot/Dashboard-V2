package com.alefinot.dashboardpp.update

import android.content.Context
import com.alefinot.dashboardpp.ble.BleJson
import com.alefinot.dashboardpp.ble.OtaController
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One GitHub release that actually carries the file the caller is after — for this
 * object the unit's `firmware.bin`, for [AppUpdate] the app's own `app-debug.apk`.
 *
 * [version] is what every comparison in the firmware card runs on: the tag with its
 * leading `v` removed, so it matches the `DPPVER=` marker inside the image and the
 * `build_version` the unit reports — three spellings of one number, normalised here
 * once. [size] is GitHub's own count for the asset, which is what the cached file is
 * checked against before a download is skipped.
 */
data class FirmwareRelease(
    val tag: String,
    /** The release title, e.g. `V2.0.3` — what the dropdown shows. */
    val title: String,
    /** `2.0.3` — comparable to `DPPVER=` / `build_version`. */
    val version: String,
    val size: Long,
    val downloadUrl: String,
)

/** The release list's state, as the card draws it. */
sealed interface ReleaseList {
    data object Idle : ReleaseList
    data object Loading : ReleaseList

    /** Newest first. An empty list is a real answer — it says the repo has no image yet. */
    data class Ready(val releases: List<FirmwareRelease>) : ReleaseList

    /** A code for [listError]; the card puts it in a sentence. */
    data class Failed(val code: String, val retryIn: Long = 0L) : ReleaseList
}

/** What happened to the file behind the chosen release. */
sealed interface FirmwareFileState {
    data object Idle : FirmwareFileState
    data class Downloading(val release: FirmwareRelease, val got: Long) : FirmwareFileState

    /** The image is on disk and was handed to [OtaController] — the card shows it as the picked file. */
    data class Ready(val release: FirmwareRelease) : FirmwareFileState

    /** A code for [fileError]. */
    data class Failed(val code: String) : FirmwareFileState
}

/**
 * The firmware versions the phone can offer, read from this project's GitHub releases.
 *
 * **What this is.** On 2026-11-09 the maintainer put a version dropdown on the firmware
 * card and asked it to fetch the image itself instead of making him find a `.bin` in a
 * Downloads folder. That widened AGENTS.md rule 5 on purpose — and only here, on the
 * phone. The parts of the old sentence that stay: **nothing in this file talks to a
 * unit**. A unit has no radio, no address and no HTTP; the only transport to it is
 * GATT, and this object never opens one. It downloads `firmware.bin` from the release
 * list into the app's cache and hands the bytes to [OtaController.pickFile], which is
 * the same door a hand-picked file comes through — same size band, same CRC-32, same
 * `DPPVER=` read, same unit-side gate. There is still no signing anywhere (rule 3):
 * the CRC is what the wire gets, and the version cross-check below is what the human gets.
 *
 * **What the list guarantees.** Drafts are never offered, and neither is a release whose
 * assets carry no uploaded `firmware.bin` — the dropdown cannot offer something the
 * release flow (rule 27) did not ship. Since the V2 repository only ever carries
 * BLE-capable images, a phone cannot be talked into fetching a Wi-Fi-era image for a
 * BLE unit from here; that guard comes free with the repository choice.
 *
 * **The rate-limit discipline.** Unauthenticated calls to `api.github.com` get 60 an
 * hour per network. The list is therefore fetched on the card's first open, on an
 * explicit REFRESH, and after a failure no sooner than [ReleaseList.Failed.retryIn]
 * says — never on a timer, and never per recomposition.
 *
 * AGENTS.md rule 23: the repository named in [RELEASES_URL] is the **third** repo-name
 * string in the app (after the README clone block and the tile User-Agent in
 * `ui/map/RouteMap.kt`), and it gets re-pointed with the other two at switch time.
 */
object FirmwareReleases {

    /** The releases of the repository this tree publishes. Rule 23: re-point at switch time. */
    private const val RELEASES_URL =
        "https://api.github.com/repos/alefinot/Dashboard-V2/releases?per_page=100"

    /** GitHub's API asks for an identifying UA; the contact is the repository itself. */
    private const val USER_AGENT =
        "DashboardPP-Android (Dashboard++ for ESP32; +https://github.com/alefinot/Dashboard-V2)"

    /** The asset name every release carries, per AGENTS.md rule 27. */
    const val ASSET_NAME = "firmware.bin"

    /** The app's own APK, the other asset rule 27 puts on every release — [AppUpdate] reads the
     * same list for it. One endpoint, one user agent, one client, and one repository string to
     * re-point at switch time (rule 23), which is why [AppUpdate] asks here instead of fetching. */
    const val APK_ASSET_NAME = "app-debug.apk"

    /**
     * The release list's raw body, for the other reader of the same endpoint ([AppUpdate]).
     * The lane itself stays in this file: it is the app's only GitHub home, and the one place
     * the repository is named is what keeps AGENTS.md rule 23's switch to one edit.
     */
    internal fun fetchListBody(): String? = get(RELEASES_URL)

    /** How long a successful list stays good for an automatic refresh (REFRESH bypasses it). */
    const val LIST_TTL_MS = 60_000L

    /** How long a failed list waits before the card may try again unasked. */
    const val RETRY_AFTER_MS = 30_000L

    private val _list = MutableStateFlow<ReleaseList>(ReleaseList.Idle)
    val list: StateFlow<ReleaseList> = _list.asStateFlow()

    private val _file = MutableStateFlow<FirmwareFileState>(FirmwareFileState.Idle)
    val file: StateFlow<FirmwareFileState> = _file.asStateFlow()

    /** The one in-flight list call / download, whatever screen is open. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Built once; OkHttp follows GitHub's CDN redirect itself. */
    private val http = OkHttpClient()

    private var lastFetchMs = 0L

    /**
     * Fetches the release list unless it is fresh. The card calls this on first
     * composition (the [LIST_TTL_MS] makes repeat visits free) and again with
     * [force] on a REFRESH press.
     */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        when (val state = _list.value) {
            ReleaseList.Loading -> return
            is ReleaseList.Ready -> if (!force) return
            is ReleaseList.Failed -> if (!force && now < state.retryIn) return
            else -> if (!force && now - lastFetchMs < LIST_TTL_MS) return
        }
        lastFetchMs = now
        _list.value = ReleaseList.Loading
        scope.launch {
            val body = get(RELEASES_URL)
            if (body == null) {
                _list.value = ReleaseList.Failed("network", retryIn = now + RETRY_AFTER_MS)
                return@launch
            }
            val parsed = parseReleases(body)
            _list.value = if (parsed == null) {
                // A body that is not the array we asked for: a rate-limit notice, a login
                // wall, or GitHub having moved something. Say so rather than show an empty list.
                ReleaseList.Failed("bad_payload", retryIn = now + RETRY_AFTER_MS)
            } else {
                ReleaseList.Ready(parsed)
            }
        }
    }

    /**
     * Puts [release]'s image in the cache and hands it to [OtaController] — nothing
     * reaches the unit from here beyond the pick. A cached file whose length matches
     * GitHub's size is used as it stands, so re-picking a version after a failed
     * install costs a probe, not a download.
     */
    fun fetch(context: Context, release: FirmwareRelease) {
        if (_file.value is FirmwareFileState.Downloading) return
        val target = File(context.cacheDir, fileNameFor(release.version))
        if (target.isFile && target.length() == release.size) {
            handOff(context, target, release)
            return
        }
        _file.value = FirmwareFileState.Downloading(release, 0L)
        scope.launch {
            val tmp = File(context.cacheDir, target.name + ".tmp")
            val outcome = try {
                download(release, tmp)
            } catch (e: IOException) {
                runCatching { tmp.delete() }
                "network"
            }
            when {
                outcome == "network" -> _file.value = FirmwareFileState.Failed("network")
                outcome == "too_big" -> {
                    runCatching { tmp.delete() }
                    _file.value = FirmwareFileState.Failed("too_big")
                }
                // The length GitHub advertised is the length we must have, or the
                // transfer ended at a redirect page instead of at the bytes.
                outcome.toLongOrNull() != release.size -> {
                    runCatching { tmp.delete() }
                    _file.value = FirmwareFileState.Failed("size_mismatch")
                }
                else -> {
                    runCatching { target.delete() }
                    if (!tmp.renameTo(target)) {
                        _file.value = FirmwareFileState.Failed("disk")
                    } else {
                        dropStaleCache(context, target.name)
                        handOff(context, target, release)
                    }
                }
            }
        }
    }

    /** Back to "no chosen file". Leaves the cache itself alone — the next pick reuses it. */
    fun resetFile() {
        if (_file.value is FirmwareFileState.Downloading) return
        _file.value = FirmwareFileState.Idle
    }

    // ------------------------------------------------------------------
    // The parts a test can run without a network or a Context.
    // ------------------------------------------------------------------

    /**
     * Turns a GitHub `releases` payload into offers, newest first.
     *
     * Returns null when the body is not the array we asked for — that is how the
     * caller tells "no releases" (an empty list) from "GitHub answered something
     * else" (rate limit, moved route). Drafts never appear; a release without an
     * uploaded [assetName] asset never appears either, because rule 27 says every
     * release meant to be flashed carries exactly one such file — and every release
     * meant to be installed carries its [APK_ASSET_NAME].
     */
    fun parseReleases(body: String, assetName: String = ASSET_NAME): List<FirmwareRelease>? {
        // BleJson throws on a body that is not JSON at all — a login wall or an HTML error
        // page is exactly what a captive portal answers with. Same answer as "not an array":
        // the caller says GitHub answered something else.
        val top = try {
            BleJson.parse(body)
        } catch (e: IllegalArgumentException) {
            null
        } as? List<*> ?: return null
        val out = ArrayList<FirmwareRelease>()
        for (entry in top) {
            val release = entry as? Map<*, *> ?: continue
            if (release["draft"] == true) continue
            val tag = release["tag_name"] as? String ?: continue
            val assets = release["assets"] as? List<*> ?: continue
            val asset = assets.firstOrNull { a ->
                val m = a as? Map<*, *> ?: return@firstOrNull false
                m["name"] == assetName && m["state"] == "uploaded"
            } as? Map<*, *> ?: continue
            val url = asset["browser_download_url"] as? String ?: continue
            if (url.isEmpty()) continue
            val title = (release["name"] as? String)?.takeIf { it.isNotBlank() } ?: tag
            out += FirmwareRelease(
                tag = tag,
                title = title,
                version = versionOf(tag),
                size = (asset["size"] as? Double)?.toLong() ?: -1L,
                downloadUrl = url,
            )
        }
        return out.sortedWith(
            compareByDescending<FirmwareRelease> { versionKey(it.version) }
                .thenByDescending { it.tag },
        )
    }

    /** `v2.0.3` / `V2.0.3` / `2.0.3` all normalise to `2.0.3`. */
    fun versionOf(tag: String): String =
        tag.removePrefix("v").removePrefix("V").trim()

    /** The cache file for one version. A sanitised name — the version is not trusted to be tidy. */
    fun fileNameFor(version: String): String {
        val safe = version
            .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return "dpp-$safe.bin"
    }

    /**
     * A comparable key from a dotted version: each numeric component becomes a fixed
     * width, so `2.0.10` sorts after `2.0.3` and not inside it (a plain string
     * compare does exactly that, and would offer the older image as the newest).
     * Non-numeric components sort below every number, which is the honest order for
     * a version this code does not understand.
     */
    fun versionKey(version: String): String {
        val parts = version.split('.')
        if (parts.isEmpty() || parts[0].toIntOrNull() == null) return "!$version"
        return parts.joinToString(".") { part ->
            val digits = part.takeWhile { it.isDigit() }
            if (digits.isEmpty()) "!$part" else digits.padStart(6, '0') + part.substring(digits.length)
        }
    }

    // ------------------------------------------------------------------

    private fun handOff(context: Context, file: File, release: FirmwareRelease) {
        _file.value = FirmwareFileState.Ready(release)
        OtaController.pickFile(context, file, "$ASSET_NAME · ${release.title}")
    }

    /** One sync GET, or null for any failure (the body is only ever parsed on 2xx). */
    private fun get(url: String): String? = try {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    } catch (e: IOException) {
        null
    }

    /**
     * Streams the asset to [tmp], capped at the OTA size band's ceiling — reading a
     * redirect page or a wrong asset into memory is what `MAX_BYTES` exists to stop.
     * Returns the byte count as a string, "" for a dead connection, or "too_big".
     */
    private fun download(release: FirmwareRelease, tmp: File): String = downloadAsset(
        release.downloadUrl, tmp, OtaController.MAX_BYTES.toLong(),
    ) { got ->
        _file.value = FirmwareFileState.Downloading(release, got)
    }

    /**
     * The shared streaming half: one GET, one cap, one progress beat, for either asset of
     * either release card ([AppUpdate] passes its own ceiling). Returns the byte count as a
     * string, "" for a dead connection or a refused response, or "too_big".
     */
    internal fun downloadAsset(
        url: String,
        tmp: File,
        maxBytes: Long,
        onProgress: (Long) -> Unit,
    ): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        var lastPublished = 0L
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return ""
                val body = response.body ?: return ""
                var got = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val block = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(block)
                            if (n < 0) break
                            got += n
                            if (got > maxBytes) return "too_big"
                            out.write(block, 0, n)
                            // Publish on a coarse step so the bar moves without recomposing
                            // a settings card for every 64 KB.
                            if (got - lastPublished >= 128 * 1024L) {
                                lastPublished = got
                                onProgress(got)
                            }
                        }
                    }
                }
                got.toString()
            }
        } catch (e: IOException) {
            ""
        }
    }

    /** Keep exactly one firmware in the cache — the one just written. 1.5 MB is not free. */
    private fun dropStaleCache(context: Context, keep: String) {
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.name != keep && f.name.startsWith("dpp-") && f.name.endsWith(".bin")) {
                runCatching { f.delete() }
            }
        }
    }
}

/** The card's one-line sentence for a list failure code. */
fun listError(code: String): String = when (code) {
    "network" -> "Could not reach GitHub — check the phone's network."
    "bad_payload" -> "GitHub answered something other than a release list."
    else -> "Release list failed ($code)."
}

/** The card's one-line sentence for a file failure code. */
fun fileError(code: String): String = when (code) {
    "network" -> "The download stopped — the phone's network or GitHub dropped it."
    "too_big" -> "That file is bigger than any image this unit can hold."
    "size_mismatch" -> "GitHub advertised a different size than the bytes that arrived."
    "disk" -> "The phone could not keep the downloaded file."
    else -> "Download failed ($code)."
}
