package com.alefinot.dashboardpp.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the app-update card draws, and the bar dot answers to.
 *
 * [Checking] through [ReadyToInstall] are the card's ladder: ask GitHub, find something newer,
 * fetch it, hand it to the installer. [UpToDate] is a real answer, not a missing one — GitHub
 * said so. The two failure kinds stay apart because they want different buttons: a failed
 * *list* wants a REFRESH, a failed *file* wants a retry of the download.
 */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState

    /** Nothing newer than [installed] is released; [latest] is the newest that is (null = unknown). */
    data class UpToDate(val installed: String, val latest: String?) : AppUpdateState

    /** [release] is newer than [installed] and is what DOWNLOAD fetches. */
    data class Available(val release: FirmwareRelease, val installed: String) : AppUpdateState

    data class Downloading(val release: FirmwareRelease, val got: Long) : AppUpdateState

    /** The APK is on disk and size-checked; INSTALL hands it to the system installer. */
    data class ReadyToInstall(val release: FirmwareRelease) : AppUpdateState

    /** A code for [listError]; [retryIn] is when an unasked check may run again. */
    data class FailedList(val code: String, val retryIn: Long = 0L) : AppUpdateState

    /** A code for [appFileError]. */
    data class FailedFile(val code: String) : AppUpdateState
}

/**
 * The app's self-updater: newest release only, checked silently at startup.
 *
 * **What this is.** The releases this repository ships carry `app-debug.apk` beside
 * `firmware.bin` (AGENTS.md rule 27), so the phone can fetch its own newer build the way the
 * firmware card fetches the unit's. This object reads the *same* GitHub list through
 * [FirmwareReleases] — one endpoint, one client, one repository string to re-point at switch
 * time (rule 23) — keeps only the newest release, and only when it is newer than the running
 * `versionName`. It never offers a downgrade: the package installer would refuse it anyway, so
 * the card refuses it earlier, in words.
 *
 * **What it is not.** It is not an installer. Android does not let an app replace itself; the
 * most this object may do is download the APK into `cacheDir/apk/` and hand a FileProvider URI
 * to the system package installer, which then asks the owner and replaces the app in place —
 * data kept, launcher restarted. Until that hand, nothing outside this StateFlow happens.
 *
 * **The permission.** `REQUEST_INSTALL_PACKAGES` is a special access: declared, but granted
 * only by the owner on a system page ([openInstallGrant] jumps there). The card checks
 * [canInstall] and says so; the app never asks by dialog, because there is no dialog.
 *
 * **The signing reality** (rule 27's debug APK): the installer accepts the update only when
 * the downloaded APK is signed by the same key as the running app — same build machine. When
 * it is not, the installer refuses and says so on its own; this card's job is the honest
 * sentence, not a second signature check (there is no signature verification anywhere, rule 3
 * in spirit, and none is possible here anyway — the installer is the verifier).
 *
 * **The rate-limit discipline** is the firmware list's, to the constant: one check per startup,
 * an explicit REFRESH, and after a failure no sooner than [AppUpdateState.FailedList.retryIn].
 * Never a timer, never per recomposition — both readers share 60 GitHub calls an hour.
 */
object AppUpdate {

    /** The cache subfolder the APK lives in. AppXmlWiringTest pins it to `files_path.xml`. */
    const val APK_DIR = "apk"

    /**
     * A sane ceiling on the download, well above any APK this repository ships (~12 MB) and
     * far below a wrong URL streaming the whole CDN into the cache. The firmware band's
     * ceiling belongs to flash, not to an APK, so the APK carries its own.
     */
    const val MAX_APK_BYTES = 64L * 1024 * 1024

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    /**
     * Whether the Settings bar slot wears its dot. It is a separate flow on purpose: the bar
     * recomposes for the dot's flip and for nothing else, and never for download progress.
     */
    private val _showDot = MutableStateFlow(false)
    val showDot: StateFlow<Boolean> = _showDot.asStateFlow()

    /** The one in-flight check / download, whatever screen is open. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastCheckMs = 0L

    /** The offered version the owner has already seen on the card; the dot's "read" mark. */
    private var seenFor: String? = null

    /**
     * The startup check ([DashboardRoot]) and the card's first composition both call this;
     * the same freshness gate the firmware list uses decides whether it leaves the phone,
     * so the two callers together are one GitHub call.
     */
    fun check(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        when (val s = _state.value) {
            AppUpdateState.Checking, is AppUpdateState.Downloading -> return
            is AppUpdateState.Available, is AppUpdateState.UpToDate, is AppUpdateState.ReadyToInstall,
            is AppUpdateState.FailedFile -> if (!force) return
            is AppUpdateState.FailedList -> if (!force && now < s.retryIn) return
            AppUpdateState.Idle ->
                if (!force && now - lastCheckMs < FirmwareReleases.LIST_TTL_MS) return
        }
        lastCheckMs = now
        publish(AppUpdateState.Checking)
        scope.launch {
            val app = context.applicationContext
            val installed = try {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
            } catch (e: Exception) {
                "?"
            }
            val body = FirmwareReleases.fetchListBody()
            if (body == null) {
                publish(AppUpdateState.FailedList("network", now + FirmwareReleases.RETRY_AFTER_MS))
                return@launch
            }
            val releases = FirmwareReleases.parseReleases(body, FirmwareReleases.APK_ASSET_NAME)
            if (releases == null) {
                publish(AppUpdateState.FailedList("bad_payload", now + FirmwareReleases.RETRY_AFTER_MS))
                return@launch
            }
            val offer = pickOffer(releases, installed)
            if (offer == null) {
                publish(AppUpdateState.UpToDate(installed, releases.firstOrNull()?.version))
            } else if (
                _state.value is AppUpdateState.ReadyToInstall &&
                (_state.value as AppUpdateState.ReadyToInstall).release.version == offer.version
            ) {
                // The file for this offer is already on disk — a refresh does not throw it away.
                publish(_state.value)
            } else {
                publish(AppUpdateState.Available(offer, installed))
            }
        }
    }

    /**
     * Puts [release]'s APK in `cacheDir/apk/`. A cached file whose length matches GitHub's size
     * is used as it stands, so revisiting after an abandoned install costs a probe, not a
     * download — the same courtesy the firmware fetcher extends to its `.bin`.
     */
    fun download(context: Context, release: FirmwareRelease) {
        if (_state.value is AppUpdateState.Downloading) return
        val dir = File(context.cacheDir, APK_DIR)
        if (!dir.isDirectory) dir.mkdirs()
        val target = File(dir, apkNameFor(release.version))
        if (target.isFile && target.length() == release.size) {
            publish(AppUpdateState.ReadyToInstall(release))
            return
        }
        publish(AppUpdateState.Downloading(release, 0L))
        scope.launch {
            val tmp = File(dir, target.name + ".tmp")
            val outcome = try {
                FirmwareReleases.downloadAsset(
                    release.downloadUrl, tmp, MAX_APK_BYTES,
                ) { got ->
                    publish(AppUpdateState.Downloading(release, got))
                }
            } catch (e: IOException) {
                ""
            }
            when {
                // "" is the lane's word for a dead or refused connection.
                outcome.isEmpty() -> {
                    runCatching { tmp.delete() }
                    publish(AppUpdateState.FailedFile("network"))
                }
                outcome == "too_big" -> {
                    runCatching { tmp.delete() }
                    publish(AppUpdateState.FailedFile("too_big"))
                }
                // The length GitHub advertised is the length we must have, or the transfer
                // ended at a redirect page instead of at the bytes.
                outcome.toLongOrNull() != release.size -> {
                    runCatching { tmp.delete() }
                    publish(AppUpdateState.FailedFile("size_mismatch"))
                }
                else -> {
                    runCatching { target.delete() }
                    if (!tmp.renameTo(target)) {
                        publish(AppUpdateState.FailedFile("disk"))
                    } else {
                        pruneApkCache(context, target.name)
                        publish(AppUpdateState.ReadyToInstall(release))
                    }
                }
            }
        }
    }

    /**
     * Hands the downloaded APK to the system installer. The app process is expected to die in
     * that hand — a replaced app restarts, and the next [check] finds [AppUpdateState.UpToDate].
     * Returns null when the installer opened, or a code for [appFileError] when it did not;
     * a refused install changes nothing on the phone.
     */
    fun install(context: Context, release: FirmwareRelease): String? {
        val file = File(File(context.cacheDir, APK_DIR), apkNameFor(release.version))
        if (!file.isFile) return "gone"
        return try {
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file,
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK,
                    ),
            )
            null
        } catch (e: Exception) {
            // ActivityNotFoundException (no package installer), a provider refusal, or an OEM
            // quirk — the owner-visible fact is one sentence, and the app is still running.
            "installer"
        }
    }

    /** The only truth that matters next to INSTALL: whether the owner has granted installs. */
    fun canInstall(context: Context): Boolean = try {
        context.packageManager.canRequestPackageInstalls()
    } catch (e: Exception) {
        false
    }

    /**
     * The system page that grants `REQUEST_INSTALL_PACKAGES` for this app. There is no dialog
     * for a special access — the same shape as the storage card's grant jump, and the same
     * fallback: an OEM that routes the per-app page nowhere at least has the app's details.
     */
    fun openInstallGrant(context: Context): Boolean {
        val pkg = Uri.parse("package:" + context.packageName)
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
        )
        for (intent in intents.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) {
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            }
        }
        return false
    }

    /** The card calls this when the owner is looking at it; it puts the bar dot out. */
    fun markSeen() {
        seenFor = offeredVersion(_state.value)
        _showDot.value = false
    }

    // ------------------------------------------------------------------
    // The parts a test can run without a network or a Context.
    // ------------------------------------------------------------------

    /**
     * Newest-only rule: the release to offer, or null when nothing beats what runs.
     * The comparison is [FirmwareReleases.versionKey], so `2.0.10` outranks `2.0.3` and a
     * scheme this code does not understand (`nightly-*`) never outranks a number — which is
     * also what keeps a pre-release tag from being offered over a real installed version.
     */
    internal fun pickOffer(
        releases: List<FirmwareRelease>,
        installed: String,
    ): FirmwareRelease? {
        val newest = releases.maxByOrNull { FirmwareReleases.versionKey(it.version) } ?: return null
        return newest.takeIf {
            FirmwareReleases.versionKey(it.version) > FirmwareReleases.versionKey(installed)
        }
    }

    /** The cache name for one version's APK, sanitised the way the firmware name is. */
    internal fun apkNameFor(version: String): String {
        val safe = version
            .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
        return "app-$safe.apk"
    }

    // ------------------------------------------------------------------

    /** One door for every state change, so the dot can never disagree with the flow behind it. */
    private fun publish(s: AppUpdateState) {
        _state.value = s
        val offered = offeredVersion(s)
        _showDot.value = offered != null && offered != seenFor
    }

    private fun offeredVersion(s: AppUpdateState): String? = when (s) {
        is AppUpdateState.Available -> s.release.version
        is AppUpdateState.ReadyToInstall -> s.release.version
        else -> null
    }

    /** Keep exactly one APK in the cache — the one just written. 12 MB is not free. */
    private fun pruneApkCache(context: Context, keep: String) {
        File(context.cacheDir, APK_DIR).listFiles()?.forEach { f ->
            if (f.name != keep && f.name.startsWith("app-") && f.name.endsWith(".apk")) {
                runCatching { f.delete() }
            }
        }
    }
}

/** The card's one-line sentence for an app-file failure code. */
fun appFileError(code: String): String = when (code) {
    "network" -> "The download stopped — the phone's network or GitHub dropped it."
    "too_big" -> "That file is bigger than any app build this repository ships."
    "size_mismatch" -> "GitHub advertised a different size than the bytes that arrived."
    "disk" -> "The phone could not keep the downloaded file."
    "gone" -> "The downloaded file is no longer there — fetch it again."
    "installer" -> "The phone's installer refused to open. Check the install-permission setting."
    else -> "Download failed ($code)."
}
