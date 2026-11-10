package com.alefinot.dashboardpp.viewmodel

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.alefinot.dashboardpp.ble.BleApi
import com.alefinot.dashboardpp.ble.BleJson
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ble.BleRuntime
import com.alefinot.dashboardpp.ble.DPP_CONN_LOG_TAG
import com.alefinot.dashboardpp.ble.scanFailureText
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.trips.RecorderTarget
import com.alefinot.dashboardpp.vehicles.UnitIdentity
import com.alefinot.dashboardpp.weather.HostFrames
import com.alefinot.dashboardpp.weather.WeatherRuntime
import com.alefinot.dashboardpp.weather.WeatherSettings
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.vehicles.CropRect
import com.alefinot.dashboardpp.vehicles.PhotoEdit
import com.alefinot.dashboardpp.vehicles.VehiclePhotoStore
import com.alefinot.dashboardpp.vehicles.VehicleStore
import com.alefinot.dashboardpp.vehicles.displayName
import com.alefinot.dashboardpp.vehicles.macConflict
import com.alefinot.dashboardpp.vehicles.mergeTypedFields
import com.alefinot.dashboardpp.vehicles.refusalMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The app's half of "is a dashboard connected, and which vehicle's dashboard is it".
 *
 * The ladder itself is not decided here — [ConnectionCore] decides it one step at a
 * time, and this class is the loop plus the radio calls: it reads the garage, dials
 * what the core says to dial, hands the answer back, and publishes whatever state and
 * records the core produced. Keeping the two apart is what lets the identity rules (a
 * MAC outranks a pairing, an unclaimed unit is offered rather than adopted) be tested
 * without a phone wired to a dashboard.
 *
 * Three things the old version of this class did are deliberately gone:
 *
 *  - **The boot ladder.** Cold start used to check a cached IP, guess the SoftAP, then
 *    run mDNS and a subnet sweep — up to 15 s of network before the app showed
 *    anything, and the thing it was hunting for was *any* Dashboard++. Now cold start
 *    reads the garage, marks the selected card, and dials that card's pairing: one
 *    connection attempt, no multicast lock, and the vehicle page is the first frame
 *    either way.
 *  - **The last-connected cache.** `ConnectionCache` remembered one IP for the whole
 *    app. That is now the card's own `bleAddress`, which is better in every way that
 *    matters: it is per vehicle, it is what Android's own bond table agrees with, and it
 *    is something the user can see and forget.
 *  - **Typing an address as a way in.** An address used to be a thing a user could enter
 *    when the sweep failed. A Bluetooth address is not a thing a human can read off a
 *    unit, and typing one cannot pair: the way in is a scan and a pairing, so the panel
 *    that offered it is gone rather than repurposed.
 *
 * The link is [BleRuntime]'s, not this class's: a GATT connection belongs to the phone,
 * and the settings page and the trip recorder use the same one the ladder brings up.
 */
class ConnectionViewModel(
    context: Context,
    private val vehicles: VehicleStore = VehicleStore(context),
    private val tripSettings: RecorderSettings = RecorderSettings(context),
    /** The one link for the process. Injected so the ladder can be tested without a radio. */
    val link: BleLink = BleRuntime.link(context),
    private val api: BleApi = BleApi(link),
) {
    private val appContext = context.applicationContext
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    /** The attempt the core is driving right now, if any. */
    private var ladder: Job? = null

    /** The retry the reconnect ladder scheduled, if any. One at a time, always replaced. */
    private var retry: Job? = null

    /** Mirrors [BleLink.state] so an unsolicited link change still reaches the ladder. */
    private var linkWatch: Job? = null

    private var adapterReceiver: BroadcastReceiver? = null

    /**
     * Retries already spent on the current failure, which is what [ReconnectLadder]
     * counts. It is held here rather than in the ladder object because the ladder is a
     * pure rule and this is the stateful half of the same design.
     */
    private var attempts = 0

    /**
     * The quiet restart of a ladder that gave up, if one is waiting. One at a time, always
     * replaced, cancelled by anything the user touches.
     */
    private var rearm: Job? = null

    /**
     * Quiet dials already spent on the current failure. It decides only the *pause* before
     * the next one ([ReconnectLadder.rearmDelayMs]) and survives the screen going off, so a
     * page that comes back is still on the five-minute cadence rather than back at minute
     * one. Reset when a link comes up.
     */
    private var rearms = 0

    /**
     * True between the vehicle page's `ON_START` and `ON_STOP`. Every dial the reconnect
     * machinery starts on its own is refused while it is false; see [onForegroundChanged].
     */
    private var foreground = false

    /**
     * True while the last word about the current failure was a give-up — the only state the
     * quiet restart serves. Cleared by a link coming up and by any press, because both mean
     * something else owns the ladder now.
     */
    private var gaveUp = false

    /**
     * The card the running attempt was started for. Set by [startAttempt] and read by the guards
     * that decide whether a press on a card is a new instruction or the same instruction twice —
     * see [ConnectionCore.onReach].
     */
    private var attemptVehicleId: String? = null

    val uiState = mutableStateOf<ConnectionUiState>(ConnectionUiState.Idle(null))

    /** True while a radio sweep is running. */
    val scanning = mutableStateOf(false)

    /**
     * What the last sweep turned up, newest advertisement first as the radio reported
     * it. The vehicle page renders these as PAIR rows; the core decides what they are
     * worth, and this list is only ever what the antenna heard.
     */
    val scanResults = mutableStateOf<List<Found>>(emptyList())

    /** Why the last sweep could not run, in words — never a stack trace, never a code. */
    val scanError = mutableStateOf<String?>(null)

    /** Whether the phone's radio is on, as last heard from the system. */
    val adapterOn = mutableStateOf(false)

    /**
     * True when the radio's runtime permissions are missing. The shell owns the one
     * launcher that asks; this is the flag that keeps the garage honest about why a
     * scan produced nothing.
     */
    val permissionMissing = mutableStateOf(false)

    /**
     * A dashboard that answered and is claimed by no card. The vehicle page turns this
     * into a NAME IT offer; nothing writes it into a record until the user says so,
     * because adopting silently is how two cards end up owning one dashboard.
     */
    val unclaimed = mutableStateOf<UnclaimedUnit?>(null)

    /**
     * Set when a card's pairing was cleared but this phone still holds the Bluetooth
     * **bond**. The app can forget a unit on its own side; Android's bond is the phone's
     * to remove, and `removeBond` is not a public API here, so the page says which half
     * is done and hands over the way to the other. Cleared whenever a new pairing is
     * started, because a fresh ask makes the note stale.
     */
    val bondNote = mutableStateOf<String?>(null)

    /**
     * One line for the vehicle page, set when a connect landed on a card other than the
     * one that was selected — the unit's MAC outranks the pairing that was dialed, so
     * the selection moved (plan §4) and the page has to say why it shows a different
     * vehicle than the card that was pressed.
     */
    val notice = mutableStateOf<String?>(null)

    /** The card the app is currently reaching for. */
    val selectedVehicleId = mutableStateOf<String?>(null)

    /**
     * Why the unit's clock was not set from this phone, or null when it was.
     *
     * A dashboard with no time source stamps its own rows with a clock that reads
     * 1970-01-01, and the WebUI has always fixed that by pushing the phone's clock every
     * time its page loaded. With no page loading any more, the push has to ride the link:
     * once per link, on the way in, from whoever owns the link — this view model, because
     * it is the thing that notices a link came up.
     *
     * It is a **line in the link status**, never a dialog: the app works without it (trip
     * files are stamped by the phone, and the trip timeline is the app's own record), so a
     * modal for a cosmetic loss would train the user to dismiss dialogs without reading
     * them.
     */
    val timeNote = mutableStateOf<String?>(null)

    /** The link whose clock was last pushed; null means "this link, not yet". */
    private var clockPushedFor: String? = null

    /**
     * The link the weather feed was first woken for. Same trick as [clockPushedFor]:
     * `Ready` is re-published when the MTU or the name changes, and each of those would
     * otherwise look like a new link to the feed.
     */
    private var weatherWokenFor: String? = null

    /** The 60 s feed tick. Owned here because this is where a link's life is visible. */
    private var weatherTick: Job? = null

    /**
     * Bumped every time a card's photo changes on disk. A cover that recomposes on it
     * follows a crop that landed while the editor was open; without it the composable keeps
     * the bitmap it decoded when the screen opened and the card shows a picture the user has
     * already replaced.
     */
    val photoVersion = mutableIntStateOf(0)

    /** The garage, for the vehicle page. */
    fun vehicleCards() = vehicles.all()

    /**
     * Why the vehicle page is empty when a garage file is there (see [VehicleStore.damageNote]).
     * Suspends until the file has been read, so null means the app looked and found nothing to
     * apologise about — not that it has not looked yet.
     */
    suspend fun garageDamage(): String? = vehicles.damageNote()

    /**
     * A second `vehicles.json` the app saw and did not use, or null (see
     * [VehicleStore.shadowGarageNote]). It names a folder the owner may be opening while the app
     * reads another one, which is what a moved storage grant leaves behind; the vehicle page says it
     * so a "0 vehicles" screen never stands unexplained.
     */
    suspend fun garageShadow(): String? = vehicles.shadowGarageNote()

    /** The file the cards on screen were read from (see [VehicleStore.garageNote]). */
    suspend fun garageFolder(): String? = vehicles.garageNote()

    /**
     * What the trip recorder has to write as the owner of the files it creates: the
     * link it is pointed at, the card that owns it, that card's name right now,
     * and the MAC the dashboard reported when the link came up.
     *
     * The recorder cannot work this out by itself — the unit's stream carries no
     * identity of the unit that answers, and a trip file outlives the connection —
     * so the app hands it these values when it starts the service [plan §6]. Null
     * when nothing is connected: a file with a half-known owner would be a guess,
     * and an untagged file is the honest version of the same thing.
     */
    suspend fun recorderTarget(): RecorderTarget? {
        val c = uiState.value as? ConnectionUiState.Connected ?: return null
        val name = try {
            vehicles.all().first().firstOrNull { it.id == c.vehicleId }?.name
        } catch (e: Exception) {
            null
        }
        return RecorderTarget(
            // The key the trip files are filed under - the same expression the vehicle card
            // and the session map use, so a drive found by the card is the drive whose
            // session is resumed. Note it is not `c.address`: a Bluetooth address names this
            // phone's pairing, and a re-pair would start a second trip mid-drive.
            UnitIdentity.key(mac = c.mac, bleAddress = c.address),
            c.vehicleId, name.orEmpty(), c.mac.orEmpty(),
        )
    }

    fun boot() {
        watchAdapter()
        watchLink()
        // The weather schedule is the phone's, not the link's: it fetches whether or not a
        // unit is connected, so a unit that comes up between two fetches can be handed the
        // frame in hand (`WeatherCoordinator.unitWoke`) instead of showing an empty card
        // until the interval expires. Started here, once, because "is a fetch due" does not
        // depend on having anyone to send it to yet.
        startWeatherTick()
        adapterOn.value = link.enabled()
        permissionMissing.value = missingBlePermissions().isNotEmpty()
        scope.launch {
            selectedVehicleId.value = vehicles.selectedId()
            startAttempt(ConnectMode.SILENT)
        }
    }

    /**
     * The vehicle page became visible ([on] true) or stopped being visible (false).
     *
     * The view model is `remember`ed inside `setContent`, so nothing above it can tell it
     * about the screen; [com.alefinot.dashboardpp.ui.screens.DashboardRoot] observes the
     * lifecycle and calls this from `ON_START` / `ON_STOP`. It is what turns "no retries
     * with the screen off" from a comment into a rule, and it is the only reason the quiet
     * restart can promise to stop when the phone goes in a pocket.
     *
     * Coming back to the foreground with a give-up in effect dials **at once**: the reason
     * the app was silent is that a ladder ended, not that the unit is unreachable, and
     * waiting out another five-minute tick to prove it would be the machinery making the
     * user wait for something they did not start. The first `ON_START` of a process never
     * dials here, because [gaveUp] is false and [boot] has already dialed.
     */
    fun onForegroundChanged(on: Boolean) {
        Log.d(DPP_CONN_LOG_TAG, "foreground on=$on gaveUp=$gaveUp rearms=$rearms")
        foreground = on
        if (!on) {
            // Only the timer is cancelled. The counter survives, because the screen going
            // off is not a new failure and says nothing about the unit.
            rearm?.cancel()
            rearm = null
            return
        }
        scope.launch {
            if (!gaveUp) return@launch
            if (rearmWantedNow()) dialForRearm() else scheduleRearm()
        }
    }

    /**
     * ADD VEHICLE -> SEARCH: dial what the selected card remembers, then sweep the radio.
     *
     * This is the only path that ever scans, and it exists because the user pressed a
     * button. A BLE sweep costs a runtime permission, an on radio, and the phone
     * listening for advertisements from units it has never met - none of which belongs
     * behind a launch or a reconnect timer.
     */
    fun rescan() {
        attempts = 0
        gaveUp = false
        rearm?.cancel()
        bondNote.value = null
        startAttempt(ConnectMode.SEARCH)
    }

    /**
     * The user picked one of the dashboards the sweep listed.
     *
     * This is the one place a unit is chosen by hand rather than by the ladder, and it is
     * worth the extra parameter in [ConnectionCore.decide]: with two units advertising
     * (a second Dashboard++, or somebody else's), the ladder's rule is "strongest signal
     * first", which is a good guess and still a guess. Tapping a name outranks it.
     *
     * The attempt resumes a sweep that already ran rather than starting a new one — the
     * list on screen **is** the sweep's result, and putting the phone back on the radio
     * for fifteen seconds to re-derive it would be a second ask the user did not make.
     */
    fun pairWith(found: Found) {
        attempts = 0
        gaveUp = false
        rearm?.cancel()
        bondNote.value = null
        startAttempt(ConnectMode.SEARCH, pinned = found, preScanned = scanResults.value)
    }

    /**
     * The radio stopped being forbidden.
     *
     * The launch attempt failed for a reason the phone controlled, so the answer is to
     * reach for the pairing again - quietly, exactly as at launch, because the user did not
     * ask for a search and a permission grant is not a request to put a scan on the radio.
     * Guarded both ways: a grant that lands while an attempt is already running, or after a
     * link came up, must not start a second one.
     */
    fun retryAfterRadio() {
        val st = uiState.value
        if (st is ConnectionUiState.Connecting || st is ConnectionUiState.Connected) return
        startAttempt(ConnectMode.SILENT)
    }

    /**
     * How many vehicle cards this phone holds, read off the store's current value.
     *
     * A cold store reads as zero, which is the wrong answer for a phone with three cards
     * whose cover then offers first-run instructions; the store is opened (and its rows
     * read) before the shell composes, so in practice the value is already there. It is
     * only ever used to choose a sentence.
     */
    suspend fun vehicleCount(): Int = try {
        vehicles.all().first().size
    } catch (e: Exception) {
        0
    }

    /**
     * Reach for [vehicleId] - the vehicle page's CONNECT.
     *
     * [mode] is SEARCH for a card the user wants found (its pairing first, then a radio
     * sweep) and PROBE for a card they want retried now (its pairing only). Either way
     * the previous attempt is cancelled first: one unit at a time, and the recorder
     * follows the state, so it seals and stops on its own.
     */
    fun connectVehicle(vehicleId: String, mode: ConnectMode = ConnectMode.SEARCH) {
        val decision = ConnectionCore.onReach(
            connectedSameVehicle = (uiState.value as? ConnectionUiState.Connected)?.vehicleId == vehicleId,
            attemptRunning = uiState.value is ConnectionUiState.Connecting || ladder?.isActive == true,
            sameVehicleRunning =
                (uiState.value as? ConnectionUiState.Connecting)?.vehicleId == vehicleId ||
                    (ladder?.isActive == true && attemptVehicleId == vehicleId),
        )
        if (decision is ConnectionCore.ReachDecision.Ignore) {
            Log.d(DPP_CONN_LOG_TAG, "connect ignored vehicle=$vehicleId: ${decision.why}")
            return
        }
        attempts = 0
        gaveUp = false
        ladder?.cancel()
        retry?.cancel()
        rearm?.cancel()
        scope.launch {
            vehicles.select(vehicleId)
            selectedVehicleId.value = vehicleId
            notice.value = null
            unclaimed.value = null
            startAttempt(mode)
        }
    }

    /**
     * The shell's CONNECT for the card that is already selected.
     *
     * It goes through the ladder before reaching for the radio, because the two ways
     * this can be hopeless - this phone has no pairing for that card, or Bluetooth is
     * off - have answers the user needs to read, and dialing anyway produces a timeout
     * twenty seconds later that explains nothing.
     */
    fun huntNow(mode: ConnectMode = ConnectMode.PROBE) {
        if (ConnectionCore.onReach(
                connectedSameVehicle = uiState.value is ConnectionUiState.Connected,
                attemptRunning = uiState.value is ConnectionUiState.Connecting || ladder?.isActive == true,
                sameVehicleRunning = uiState.value is ConnectionUiState.Connecting || ladder?.isActive == true,
            ) is ConnectionCore.ReachDecision.Ignore
        ) {
            // The shell's CONNECT button deserves the same swallow as the card's: both are one
            // press away from tearing down the link the previous press was still building.
            Log.d(DPP_CONN_LOG_TAG, "hunt ignored: an attempt is already running")
            return
        }
        attempts = 0
        gaveUp = false
        rearm?.cancel()
        scope.launch {
            val card = selectedCard()
            when (val step = ReconnectLadder.step(
                LinkEvent.UserAsked, attempts, card?.hasPairing == true, link.enabled(),
            )) {
                is LadderStep.GiveUp -> uiState.value = ConnectionUiState.Failed(step.reason)
                else -> startAttempt(mode)
            }
        }
    }

    /**
     * The editor's starting point for a card that already exists: the stored record, so the
     * form opens prefilled with what the app already knows instead of with blanks.
     *
     * [onDraft] runs on [scope], off the main thread; it only assigns Compose state, which
     * is safe from any thread. A card that vanished while the form was being opened answers
     * null, and the screen closes itself rather than editing a ghost.
     */
    fun editDraft(vehicleId: String, onDraft: (Vehicle?) -> Unit) {
        scope.launch { onDraft(vehicles.get(vehicleId)) }
    }

    /**
     * The editor's starting point for a dashboard that answered and belongs to no card.
     *
     * The id is taken from the store here, not when the form is saved, because [VehicleStore.nextId]
     * reserves it in the transaction that reads it - a second adoption started while this form
     * is open cannot then be handed the same id. A cancelled form costs a skipped number, and
     * that is the cheaper mistake: card ids double as trip tags, so an id is never handed out
     * twice.
     *
     * `lastConnectedAtMs` is set because the unit did just answer - the card is born with a
     * real "last seen", not an empty one.
     */
    fun adoptDraft(onDraft: (Vehicle?) -> Unit) {
        val unit = unclaimed.value ?: return
        scope.launch {
            val seen = now()
            onDraft(
                Vehicle(
                    id = vehicles.nextId(),
                    mac = unit.mac,
                    bleAddress = unit.address,
                    firmwareVersion = unit.version,
                    lastConnectedAtMs = seen,
                    createdAtMs = seen,
                ),
            )
        }
    }

    /**
     * The shared half of both save paths: read the garage, merge the user's fields onto
     * the stored record, fill a blank name, and refuse a card carrying another card's
     * MAC. Extracted 2026-11-10 when [saveVehicleWithPhoto] grew the same rules — two
     * copies of the identity rules is two chances to fix one and forget the other.
     *
     * `Allowed` means the write may happen; `Refused` carries the sentence the form shows
     * while staying open.
     */
    private sealed interface CheckedSave {
        /** Validated and merged; [stored] is null when the card does not exist on disk yet. */
        data class Allowed(val finished: Vehicle, val stored: Vehicle?) : CheckedSave
        data class Refused(val reason: String) : CheckedSave
    }

    private suspend fun checkSave(typed: Vehicle): CheckedSave {
        val all = vehicles.all().first()
        val stored = all.firstOrNull { it.id == typed.id }
        val merged = stored?.let { mergeTypedFields(it, typed) } ?: typed
        val taken = all.filter { it.id != merged.id }.map { it.name }.toSet()
        val finished = merged.copy(name = displayName(merged.name, merged, taken))
        macConflict(finished, all)?.let { clash ->
            return CheckedSave.Refused(refusalMessage(clash))
        }
        return CheckedSave.Allowed(finished, stored)
    }

    /**
     * A card that did not exist before becomes the selected one: the user made it from
     * a unit that is in front of them, and the next thing they do is connect.
     */
    private suspend fun adoptSelection(stored: Vehicle?, finished: Vehicle) {
        if (stored == null) {
            vehicles.select(finished.id)
            selectedVehicleId.value = finished.id
            unclaimed.value = null
        }
    }

    /**
     * Write the user's half of a card, or refuse the write. [onDone] gets null when the
     * card is stored and the refusal's wording when it is not, so the form can stay open
     * with the reason in it instead of closing on a write that never happened.
     *
     * Two rules are applied here rather than in the form, so they cannot be skipped by a
     * second caller (task 8's adoption, a future import): a blank name is filled with the
     * unit's identity tail, and a card carrying another card's MAC is refused outright -
     * that is the exact state this refactor exists to make impossible, and a warning the
     * user can press through would leave two cards describing one dashboard.
     *
     * Everything the app measured (`mac`, `bleAddress`, `lastConnectedAtMs`, `firmwareVersion`,
     * `createdAtMs`, the photo) is kept by [mergeTypedFields]; a save that rebuilt the
     * record from the form would drop the evidence that binds the card to the unit.
     *
     * A save that may also land a staged picture goes through [saveVehicleWithPhoto],
     * which runs this same validation before it touches a file.
     */
    fun saveVehicle(typed: Vehicle, onDone: (refusal: String?) -> Unit = {}) {
        scope.launch {
            val check = checkSave(typed)
            if (check is CheckedSave.Refused) {
                onDone(check.reason)
                return@launch
            }
            check as CheckedSave.Allowed
            if (!vehicles.put(check.finished)) {
                // The card stayed on disk as it was. Closing the form here would tell the user the
                // edit happened; the refusal keeps it open with the reason in it.
                onDone(vehicles.saveFailure())
                return@launch
            }
            adoptSelection(check.stored, check.finished)
            onDone(null)
        }
    }

    /**
     * Save the card **and the photo decision the editor staged** ([PhotoEdit]) as one
     * action, so CANCEL or Back can undo both or neither — which is the whole point of
     * draft-scoped photo edits (2026-11-10): the picture behaves like every text field
     * on that form, and a brand-new card can be born with a photo because its record and
     * its JPEGs land in the same save.
     *
     * The order is the method. First [checkSave]'s rules — a refusal there writes nothing
     * at all, not a file and not a record. Then the photo, *before* the single
     * [VehicleStore.put], so the record never points at a JPEG that failed to arrive:
     *
     *  - [PhotoEdit.Keep] touches nothing, and the record keeps its `photoFile` exactly
     *    as [mergeTypedFields] carried it.
     *  - [PhotoEdit.Remove] deletes `<id>.jpg` **and** `<id>.src.jpg` first, then lands
     *    the record with `photoFile = null` — the "file first, then pointer" order the
     *    old `removePhoto` kept, because a record naming a gone file reads back as "no
     *    photo", while the reverse is the editor offering a photo that cannot be shown.
     *  - [PhotoEdit.Crop] crops [PhotoEdit.Crop.source] into `<id>.jpg` (keeping the
     *    original as `<id>.src.jpg` when the source is a fresh capture). A crop that
     *    cannot land answers a refusal and **writes no record**: the card keeps the photo
     *    it had, the form stays open, and the user frames the picture again.
     *
     * [photoVersion] is bumped only when a photo actually changed — Keep must not churn
     * every well and card cover in the app on an ordinary rename.
     */
    fun saveVehicleWithPhoto(typed: Vehicle, edit: PhotoEdit, onDone: (refusal: String?) -> Unit = {}) {
        scope.launch {
            val check = checkSave(typed)
            if (check is CheckedSave.Refused) {
                onDone(check.reason)
                return@launch
            }
            check as CheckedSave.Allowed
            var record = check.finished
            var photoChanged = false
            when (edit) {
                PhotoEdit.Keep -> Unit
                PhotoEdit.Remove -> {
                    vehicles.photos.delete(check.finished.id)
                    record = record.copy(photoFile = null)
                    photoChanged = true
                }
                is PhotoEdit.Crop -> {
                    val landed = vehicles.photos.cropFrom(edit.source, check.finished.id, edit.rect)
                        ?: run {
                            // The framing did not become a file — nothing else moves.
                            onDone("Could not save the photo")
                            return@launch
                        }
                    record = record.copy(photoFile = landed.name)
                    photoChanged = true
                }
            }
            if (!vehicles.put(record)) {
                onDone(vehicles.saveFailure())
                return@launch
            }
            adoptSelection(check.stored, record)
            if (photoChanged) photoVersion.intValue++
            onDone(null)
        }
    }

    /**
     * Delete a vehicle and everything that existed only to reach it.
     *
     * The card, its JPEG, the recorder's session row, the weather cache row and the in-memory
     * frame all go - each is a claim about *this* unit, and a row left behind for a vehicle
     * nobody owns is a coordinate the next connect will dial again. The typed city stays: it is
     * the owner's, not the unit's. The drives it took stay exactly where they are - a trip's
     * file name carries this card's id, and the log is meant to outlive the card, badged with
     * the name it was driven under.
     *
     * Deleting is also how a vehicle is **forgotten** now: the card used to carry a separate
     * FORGET for that half, and it has folded back in here. The app can only clear its own side
     * - removing the card drops the pairing and the identity it held - so [ConnectionCore.forgetUnit]
     * is asked the question a forget asked: did this card hold a pairing, and does Android still
     * hold the **bond**? If it does, [bondNote] says so and hands over the way to the phone's
     * Bluetooth settings, because the bond is the phone's to drop. A card that owns the live
     * link drops it first, so a session is never left running for a vehicle that just stopped
     * existing.
     *
     * The session and cache rows go *before* the card is removed, because a row is a claim about
     * a unit; if the card disappears first, a crash in between leaves the claim pointing at
     * nothing. Removing the rows first leaves the worst case as a card that still exists with a
     * clean identity, which the next connect refills.
     *
     * [onDone] reports the outcome the way [saveVehicle] does: `null` when the file agrees the
     * card is gone, the refusal sentence when it does not. A refused delete is the worst of the
     * garage writes to leave quiet — the editor closed, the card vanished from the screen, and
     * the next start the card is back because the file never took the removal.
     */
    fun deleteVehicle(vehicleId: String, onDone: (refusal: String?) -> Unit = {}) {
        scope.launch {
            val v = vehicles.get(vehicleId) ?: run {
                // Nothing to delete: the card is already gone from the garage, which is the
                // outcome the caller asked for.
                onDone(null)
                return@launch
            }
            val ownsSession = (uiState.value as? ConnectionUiState.Connected)?.vehicleId == vehicleId
            // Deleting a vehicle is also how it is forgotten now (the card used to carry a
            // separate FORGET for this half). Ask what clearing this card's pairing costs the
            // phone *before* the card is gone, because `link.isBonded` needs the address the
            // card still carries.
            val decision = ConnectionCore.forgetUnit(
                v.hasPairing,
                v.bleAddress?.let { link.isBonded(it) } == true,
            )
            // Deleting the card you are standing on ends the session it owns, exactly as a
            // forget did: the ladder, the retries and the re-arm all go with it.
            if (ownsSession) disconnect()
            val key = UnitIdentity.key(v.mac, v.bleAddress)
            tripSettings.clearSessions(listOf(key))
            WeatherSettings(appContext).clearCaches(listOf(key))
            HostFrames.clear(key)
            if (!vehicles.delete(vehicleId)) {
                // The card survived the delete. Its caches went first (that ordering is the one a
                // crash can survive), so what the user is holding is a card that will come back
                // after a restart — which is exactly the thing they must be told, because the
                // editor they just closed said it was gone.
                onDone(vehicles.saveFailure("Card not deleted"))
                return@launch
            }
            if (selectedVehicleId.value == vehicleId) {
                // The store clears its own pointer; the VM's copy has to follow, and the
                // garage needs *some* card selected afterwards or the ladder has nothing to
                // reach for on the next network change. The neighbour takes the slot.
                val next = vehicles.all().first().firstOrNull()
                if (next != null) {
                    vehicles.select(next.id)
                    selectedVehicleId.value = next.id
                } else {
                    selectedVehicleId.value = null
                }
            }
            if (ownsSession) {
                notice.value = null
                unclaimed.value = null
            }
            // The half this app could not do. Removing the card cleared the app's side of the
            // pairing; only Android can drop the bond, so the page is told when that half is
            // left undone - the same note a FORGET used to raise, now raised by the delete.
            bondNote.value = decision.note
            onDone(null)
        }
    }

    /**
     * A card's photo, decoded no larger than the [maxEdgePx] box it is going to be drawn
     * in, answered on [scope] because decoding is file I/O. Page and editor ask for a box
     * rather than for "the photo" - that is what keeps a garage of six cars from holding six
     * full-size bitmaps (rule 14, and the head of [VehiclePhotoStore]).
     *
     * Read it together with [photoVersion]: the version is what tells the screen that the
     * answer it cached is no longer the one on disk.
     */
    fun photo(vehicleId: String, maxEdgePx: Int, onReady: (Bitmap?) -> Unit) {
        scope.launch { onReady(vehicles.photos.read(vehicleId, maxEdgePx)) }
    }

    /**
     * The gallery route: copy what the picker chose into this card's scratch capture, and
     * answer the file the crop screen will show, or `null` when the copy could not be made.
     *
     * The copy is not a formality. A picker hands over a `content://` URI owned by another
     * app, whose stream may not be seekable and whose grant can lapse while the crop screen
     * is open - so everything after this point works on a file this app owns, which is also
     * what makes a retry after a screen rotation possible (see
     * [VehiclePhotoStore.stageFrom]).
     *
     * [onStaged] runs on the **main** thread, unlike most callbacks here. The copy is file
     * I/O and happens on [scope], but what the caller does with the answer is open a panel,
     * and a screen opened from a background thread is a crash rather than a slow frame.
     */
    fun stagePickedPhoto(uri: Uri, vehicleId: String, onStaged: (File?) -> Unit) {
        scope.launch {
            val staged = vehicles.photos.stageFrom(uri, vehicleId)
            withContext(Dispatchers.Main) { onStaged(staged) }
        }
    }

    /**
     * Offer the system camera a scratch capture: the file it writes into and the
     * `content://` URI the intent is launched with, or `null` when there is nothing to
     * offer - which the editor reports as the camera not being available instead of taking
     * a photograph that has nowhere to land and then claiming it worked.
     *
     * Answered on the **main** thread for the same reason as [stagePickedPhoto]: the caller
     * turns this straight into `launch(uri)`, and an intent may only be launched from the
     * main thread. The FileProvider call behind it is not I/O, so there is nothing to move
     * off the UI here; a device with no writable cache dir is the only way it answers null.
     */
    fun cameraCapture(vehicleId: String, onCapture: (VehiclePhotoStore.Capture?) -> Unit) {
        scope.launch {
            val capture = vehicles.photos.captureFor(vehicleId)
            withContext(Dispatchers.Main) { onCapture(capture) }
        }
    }

    /**
     * The square the user just framed, decoded small and answered on the **main**
     * thread — for the editor's photo well to draw while the decision is still a draft.
     *
     * This decodes; it does not write. The crop the card actually gets is produced by
     * [saveVehicleWithPhoto] from the untouched source file, never from these pixels,
     * so what the well shows and what lands agree by geometry rather than by luck
     * (see [PhotoEdit.Crop]).
     */
    fun previewPhotoCrop(
        source: File,
        rect: CropRect,
        maxEdgePx: Int,
        onReady: (Bitmap?) -> Unit,
    ) {
        scope.launch {
            val preview = vehicles.photos.previewCrop(source, rect, maxEdgePx)
            withContext(Dispatchers.Main) { onReady(preview) }
        }
    }

    /**
     * Drop a card's scratch capture: the staged pick or camera shot in
     * `cacheDir/captures/`, never anything the vehicles dir owns. The editor calls this
     * when a crop step is cancelled and once more when the editor itself closes, so a
     * picture the user decided against does not sit in the cache until Android clears
     * it — and a draft crop's source, which SAVE still needs while the form is open,
     * goes exactly when the form stops existing.
     */
    fun cancelPhoto(vehicleId: String) {
        scope.launch { vehicles.photos.clearCapture(vehicleId) }
    }

    /**
     * The file RE-FRAME PHOTO opens for a card that has a photo: the **stored original**
     * (`<id>.src.jpg`) when the card has one, else the crop it currently shows
     * (`<id>.jpg` — every card predating original retention). `null` when the card has
     * no photo file at all. Answered on the **main** thread, like [stagePickedPhoto]:
     * the caller turns it straight into opening the crop step.
     *
     * Opening the negative is the whole point (2026-11-10): `<id>.jpg` is the square
     * somebody cut last time, and re-framing *it* could only ever zoom inside that old
     * cut. The original keeps the field of view, so a crop can be widened and what was
     * cut off comes back. [VehiclePhotoStore.cropFrom] then treats a stored source as
     * something it must never delete or re-encode as a negative — see
     * [VehiclePhotoStore.commitDeletesSource].
     *
     * A draft crop re-frames from its own pending source instead, which the editor
     * already holds — this door is for what is on disk.
     */
    fun storedPhotoFile(vehicleId: String, onFile: (File?) -> Unit) {
        scope.launch {
            val f = vehicles.photos.reframeSourceFor(vehicleId)
            withContext(Dispatchers.Main) { onFile(f) }
        }
    }

    /**
     * A card field the ladder tried to remember and the file would not take — a firmware
     * version, a last-connect time. Not announced: nobody asked for this write, the value is
     * in memory and on screen, and the next successful connect refills it. It is logged
     * because it is the one garage write that can fail with nobody watching, and "the app
     * forgot the version again" should have a line behind it.
     */
    private fun logPersistMiss(vehicleId: String, from: String) {
        Log.w(DPP_CONN_LOG_TAG, "garage write refused ($from) vehicle=$vehicleId — " +
            "identity/version fields stay in memory only")
    }

    /** The connected card's DISCONNECT. The selection stays; the unit goes. */
    fun disconnect() {
        ladder?.cancel()
        retry?.cancel()
        // A deliberate DISCONNECT must not be undone by a timer sixty seconds later: the
        // quiet restart exists to recover a link the user lost, not one they ended.
        rearm?.cancel()
        gaveUp = false
        attempts = 0
        link.disconnect()
        if (uiState.value !is ConnectionUiState.Connected) return
        uiState.value = ConnectionUiState.Idle(null)
    }

    // ------------------------------------------------------------- the attempt loop

    private fun startAttempt(
        mode: ConnectMode,
        pinned: Found? = null,
        preScanned: List<Found>? = null,
    ) {
        Log.d(DPP_CONN_LOG_TAG, "attempt start mode=$mode pinned=${pinned?.address ?: "-"} attempts=$attempts")
        attemptVehicleId = selectedVehicleId.value
        ladder?.cancel()
        ladder = scope.launch { runAttempt(mode, pinned, preScanned) }
    }

    /**
     * Drive [ConnectionCore.decide] until it produces a terminal step.
     *
     * [tried], [scanDone] and [found] are the attempt's memory - the core is pure, so
     * the loop carries what has been dialed, whether the radio has already been swept,
     * and what that sweep turned up, and hands it back on every step. The card list is
     * re-read each step because a step may have written one (a connect adopts an
     * identity), and because a card can be deleted from under an attempt: cancelling
     * this job is how a new selection supersedes an old one, and
     * [currentCoroutineContext] is what notices.
     *
     * A step is not one radio action: [NextAction.Pair] can take twenty seconds (the
     * unit shows a passkey and a person types it), so an attempt touches at most one
     * sweep plus one dial per unit. That is also why [ReconnectLadder] counts attempts
     * instead of pacing a loop - a loop that retries on its own would be a radio doing
     * something nobody asked for.
     */
    private suspend fun runAttempt(
        mode: ConnectMode,
        pinned: Found? = null,
        preScanned: List<Found>? = null,
    ) {
        unclaimed.value = null
        scanError.value = null
        // A sweep the user is already looking at stays on screen: `pairWith` resumes that
        // sweep, and clearing the list would empty the row they just pressed.
        if (preScanned == null) scanResults.value = emptyList()
        var tried = emptyList<String>()
        var scanDone = preScanned != null
        var found = preScanned ?: emptyList()
        var outcome: LinkOutcome? = null
        // This loop's own job, handed to `scheduleRearm` below. An attempt that ends in a
        // failure is allowed to schedule its own restart, and the guard that refuses to
        // dial over a running attempt has to be able to tell that caller from a stranger.
        val me = currentCoroutineContext().job
        while (currentCoroutineContext().isActive) {
            val r = ConnectionCore.decide(
                vehicles.all().first(), selectedVehicleId.value,
                tried, scanDone, found, outcome, mode, now(), pinned,
            )
            r.persist?.let { if (!vehicles.put(it)) logPersistMiss(it.id, "ladder") }
            unclaimed.value = r.adoption
            r.notice?.let { notice.value = it }
            when (val n = r.next) {
                is NextAction.Idle -> {
                    uiState.value = ConnectionUiState.Idle(n.reason)
                    return
                }
                is NextAction.Failed -> {
                    uiState.value = ConnectionUiState.Failed(n.reason)
                    // A search that ended in failure has to let the radio go. The unit
                    // serves one peer at a time, so a phone sitting on a link it never
                    // uses keeps every other phone out of the unit - and since the unit
                    // now holds pairing back for 10 s after a drop, holding on is what
                    // makes a hand-over between two phones impossible.
                    if (mode == ConnectMode.SEARCH && link.state.value != BleLinkState.Idle) {
                        link.disconnect()
                    }
                    // This is the give-up the bench actually hits (2026-11-05): the attempt
                    // dialed the card's own pairing, nothing answered, and the app's interest
                    // in a unit that was merely still booting ended there — foreground, for
                    // as long as anybody cared to watch, with the unit's clock and its weather
                    // card ageing out. A `GiveUp` reached through a link event is the same
                    // verdict from the other direction, and both now end the failure the same
                    // way: the slow tick takes over.
                    gaveUp = true
                    scheduleRearm(except = me)
                    Log.d(DPP_CONN_LOG_TAG, "attempt ended reason=${n.reason} mode=$mode, handing the failure to the slow tick")
                    return
                }
                is NextAction.Connect -> {
                    applyConnect(n)
                    return
                }
                is NextAction.Scan -> {
                    uiState.value = ConnectionUiState.Connecting(mode, AttemptStage.SCAN, attemptVehicleId)
                    scanDone = true
                    found = sweep()
                    outcome = null
                }
                is NextAction.Pair -> {
                    tried = tried + n.address
                    outcome = dial(n, mode)
                }
            }
        }
    }

    /**
     * Bring one link up and read who it is.
     *
     * The stage line follows [BleLink.state] rather than this call stack's progress,
     * because the one stage the user has to act on - the six-digit code on the panel,
     * typed on the phone - is a fact about Android's pairing dialog and not about the
     * order of these lines.
     *
     * The identity is read *after* the link is up, on purpose: `perf.mac` is the unit's
     * eFuse address, the only thing that survives a re-pairing or a second phone, and
     * the version comes from the same snapshot the live tab reads. Nulls are load
     * bearing - with no MAC the core treats the pairing as the whole claim, which is
     * what the ambiguity rules in [ConnectionCore] exist to catch.
     */
    private suspend fun dial(step: NextAction.Pair, mode: ConnectMode): LinkOutcome {
        uiState.value = ConnectionUiState.Connecting(mode, AttemptStage.DIAL, step.vehicleId)
        val mirror = scope.launch {
            link.state.collect { s ->
                val stage = when (s) {
                    is BleLinkState.Pairing -> AttemptStage.PAIR
                    is BleLinkState.Connecting -> AttemptStage.DIAL
                    else -> null
                }
                if (stage != null) {
                    uiState.value = ConnectionUiState.Connecting(mode, stage, step.vehicleId)
                }
            }
        }
        val up = try {
            link.connect(step.address, step.name)
        } finally {
            mirror.cancel()
        }
        if (!up) return LinkOutcome.Fail(step.address)

        val ready = link.state.value as? BleLinkState.Ready
        val info = readIdentity()
        return LinkOutcome.Ok(
            vehicleId = step.vehicleId,
            address = ready?.address ?: step.address,
            name = ready?.name ?: step.name,
            version = info.version,
            mac = info.mac,
            mtu = ready?.mtu ?: 0,
        )
    }

    /**
     * One radio sweep, collected into a list the vehicle page can offer.
     *
     * The list is published as it grows: on a bench the unit is usually the only thing
     * advertising, and a page that shows it the moment the advertisement lands is a page
     * the user can act on while the sweep is still running. [BLE_SCAN_MS] is the window
     * the link panel used - long enough to catch a unit that advertises between
     * connections, short enough that the radio is not left on.
     *
     * Nothing here decides what a found unit is *worth*: that is the core's step
     * function reading [Found], which is why the ordering can be tested without a radio.
     */
    private suspend fun sweep(): List<Found> {
        if (!link.available()) {
            scanError.value = scanFailureText("no_bluetooth")
            return emptyList()
        }
        if (!link.enabled()) {
            scanError.value = scanFailureText("bluetooth_off")
            adapterOn.value = false
            return emptyList()
        }
        val missing = missingBlePermissions()
        if (missing.isNotEmpty()) {
            permissionMissing.value = true
            scanError.value = scanFailureText("permission")
            return emptyList()
        }
        val units = mutableListOf<Found>()
        scanning.value = true
        try {
            withTimeoutOrNull(BLE_SCAN_MS) {
                try {
                    link.scan().collect { list ->
                        units.clear()
                        list.forEach { units.add(Found(it.address, it.name, it.rssi)) }
                        scanResults.value = units.toList()
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    // The sweep's own deadline, delivered here as a cancellation: the
                    // search found nothing, the radio is not broken. Its message names an
                    // internal coroutine class - printing it produced "Bluetooth scan
                    // failed: StandaloneCoroutine was cancelled" on the bench.
                    throw e
                } catch (e: Exception) {
                    scanError.value = scanFailureText(e.message)
                }
            }
        } finally {
            scanning.value = false
        }
        return units
    }

    /** The permissions this Android version needs from the radio, of those not granted. */
    fun missingBlePermissions(): List<String> {
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return wanted.filter {
            ContextCompat.checkSelfPermission(appContext, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * What the shell's one permission launcher should ask for; empty when nothing is
     * missing. One launcher, asked once, for both the scan and the connect: Android
     * groups them under the same two runtime permissions, and a page that asks per
     * action is a page that asks twice.
     */
    fun blePermissionRequest(): List<String> = missingBlePermissions()

    /** The launcher's answer, so the page stops warning about a grant it now has. */
    fun notePermissionsResult(granted: Map<String, Boolean>) {
        permissionMissing.value = missingBlePermissions().isNotEmpty()
        if (!permissionMissing.value) scanError.value = null
    }

    /** The unit's own eFuse MAC and firmware version, read over the link that is up. */
    private suspend fun readIdentity(): UnitInfo {
        // Identity comes from `snapshot`, whose answer is one frame on any link.
        // `perf` carries the MAC too, but that reply measured 1307 B on the bench and
        // arrives as three fragments: a phone that never gets all three waits out its
        // request timeout, has no MAC, can build no card, and shows nothing at all.
        // perf stays as the fallback for firmware that predates snapshot.mac.
        val snap = link.request("snapshot")
        var mac: String? = null
        var version: String? = null
        if (snap.ok) {
            mac = BleJson.string(snap.fields, "mac")?.takeIf { it.isNotEmpty() }
            version = BleJson.string(snap.fields, "ver")
        }
        if (mac == null) {
            val perf = link.request("perf")
            if (perf.ok) mac = BleJson.string(perf.fields, "mac")?.takeIf { it.isNotEmpty() }
        }
        return UnitInfo(mac = mac, version = version)
    }

    /** What one link-up read knows about the unit on the other end. */
    private data class UnitInfo(val mac: String?, val version: String?)

    private suspend fun applyConnect(n: NextAction.Connect) {
        // The step before a Connect may have suspended (reading an identity, writing an
        // adopted one), so a tap on another card can have superseded this attempt while
        // it was in flight. Re-checking here is what keeps a dead attempt from
        // publishing a connection the user has already moved past.
        if (!currentCoroutineContext().isActive) return
        if (n.vehicleId != selectedVehicleId.value) {
            // The unit's MAC outranks the pairing that was dialed, so the selection
            // moves to the card that owns it - and `notice`, already published by the
            // caller, is what tells the user the page changed under them.
            selectedVehicleId.value = n.vehicleId
            scope.launch { vehicles.select(n.vehicleId) }
        }
        // The count of quiet dials this link took to come back, which is the number the
        // cadence is judged by: one means the unit was barely late, ten means the tick is
        // the only reason the app is talking to it at all. Read before the reset, of course.
        Log.d(DPP_CONN_LOG_TAG, "linked name=${n.name} address=${n.address} vehicle=${n.vehicleId} mtu=${n.mtu} rearmsSpent=$rearms")
        attempts = 0
        rearms = 0
        gaveUp = false
        rearm?.cancel()
        uiState.value = ConnectionUiState.Connected(
            address = n.address,
            name = n.name,
            version = n.version,
            mac = n.mac,
            vehicleId = n.vehicleId,
            mtu = n.mtu,
        )
    }

    // ------------------------------------------------------------- the radio's events

    /**
     * Follow the radio so the app stops needing a finger for what the phone already
     * knows: the unit rebooted, Bluetooth got turned on after the app gave up, the
     * tablet walked back into range.
     *
     * There is no `ConnectivityManager` here any more, and that is the whole change: a
     * network callback could mean anything, so acting on one meant either missing a real
     * reconnect or reaching for a stranger's dashboard. A Bluetooth event means exactly
     * one thing, [ReconnectLadder] decides what it is worth, and the only thing a
     * scheduled dial can ever be is the unit this phone is paired with.
     *
     * The app never turns the radio on or off, and never asks: Android 12+ will not let
     * an app enable Bluetooth without a system dialog anyway, and a sheet the app
     * provoked at launch is the kind of surprise this app used to be full of.
     */
    private fun watchAdapter() {
        if (adapterReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR,
                )
                val on = state == BluetoothAdapter.STATE_ON
                adapterOn.value = on
                scope.launch { onLinkEvent(if (on) LinkEvent.AdapterOn else LinkEvent.AdapterOff) }
            }
        }
        adapterReceiver = receiver
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun stopAdapterWatch() {
        val r = adapterReceiver ?: return
        adapterReceiver = null
        try {
            appContext.unregisterReceiver(r)
        } catch (e: Exception) {
            // Already gone: the system drops receivers with the process anyway.
        }
    }

    /**
     * Mirror the link, and let the ladder answer what it reports.
     *
     * The guard against double-driving is [ladder]: while an attempt is running, that
     * loop is the one holding the link and reading its result, so an event from the same
     * failure must not start a second one. A link coming up while no attempt runs is the
     * interesting case - the unit rebooted, or Android reconnected a bond on its own -
     * and [adoptLiveLink] turns it into a session without dialing again.
     */
    private fun watchLink() {
        linkWatch?.cancel()
        linkWatch = scope.launch {
            link.state.collect { s ->
                adapterOn.value = link.enabled()
                // Both keys below mean "this link", not "this process". A unit that is
                // powered off and back on has lost its clock (the phone is its only clock
                // source, and `settimeofday` is RAM) and its weather (`g_weatherData` is RAM
                // too), so the next link has to feed both again. `Ready` is re-published
                // when the MTU or the name changes, which is why the keys are cleared here
                // rather than keyed on the state object. Before this, a reconnect in the
                // same process never re-pushed the clock: the panel's date and time stayed
                // wrong until the app was force-stopped (bench, 2026-11-06).
                if (s !is BleLinkState.Ready) {
                    clockPushedFor = null
                    weatherWokenFor = null
                }
                when (s) {
                    is BleLinkState.Ready -> {
                        pushClock(s)
                        wakeWeather(s)
                        if (ladder?.isActive != true) adoptLiveLink(s)
                    }
                    is BleLinkState.Failed -> {
                        if (ladder?.isActive != true) {
                            onLinkEvent(LinkEvent.Dropped(s.reason))
                        } else {
                            // The case the 2026-11-02 fix was about: a link that fell over while
                            // an attempt was running is that attempt's business, and the ladder
                            // in front of it will report the outcome. Logged, because "the app
                            // swallowed its own retry" is exactly the thing a capture has to be
                            // able to rule in or out.
                            Log.d(DPP_CONN_LOG_TAG, "link failed reason=${s.reason} while an attempt runs; that attempt owns it")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Set the unit's clock once per link, and keep the answer on the page if it fails.
     *
     * Keyed on the address rather than on the state object because `Ready` is re-published
     * when the MTU or the name of the same link changes — re-pushing on every one of those
     * would be an RPC the user cannot tell apart from the unit's own traffic. Leaving any
     * non-ready state resets the key, which is what makes the rule "every link", not "once
     * per process".
     *
     * Not awaited by the ladder: a unit that will not answer `time.set` is a unit whose
     * telemetry still arrives, and making the clock a step in the connection sequence
     * would let a cosmetic RPC failure look like a connection failure.
     */
    private fun pushClock(ready: BleLinkState.Ready) {
        if (clockPushedFor == ready.address) return
        clockPushedFor = ready.address
        scope.launch {
            val reply = api.pushTime()
            // Under DPP-CONN because "the app re-linked and the panel is still showing the
            // wrong time" is only answerable if the clock write is on the capture.
            Log.d(
                DPP_CONN_LOG_TAG,
                "clock pushed ${if (reply.ok) "ok" else "failed err=${reply.error}"} " +
                    "address=${ready.address}",
            )
            timeNote.value = if (reply.ok) {
                null
            } else {
                "Unit clock not set."
            }
        }
    }

    /**
     * Feed the unit's weather card as soon as a link comes up, and keep this host's
     * heartbeat while the link lives.
     * [BleRuntime] owns the link's status independently of the trip service, so without
     * this the card fills in only while a recording is running.
     *
     * Not awaited, and never part of the connection sequence: a unit that will not take
     * a weather frame is a unit whose telemetry still arrives, and the plan's whole point
     * is that weather answers to the link rather than to the trip recorder.
     */
    private fun wakeWeather(ready: BleLinkState.Ready) {
        if (weatherWokenFor != ready.address) {
            weatherWokenFor = ready.address
            val address = ready.address
            scope.launch {
                // `unitWoke`, not `wake`: the question is "this unit has nothing to show",
                // not "is a fetch due". It hands over whatever this process is holding and
                // fetches only when there is nothing to hand over.
                WeatherRuntime.coordinator(appContext).unitWoke(weatherKeyFor(address))
            }
        }
    }

    /**
     * Every minute: enough for the five-minute rule to land within a minute of its due time
     * and for the 2 km rule to notice within a couple of kilometres at motorway speed.
     * The wake itself is cheap when nothing is due — the coordinator reads its own memory
     * first and asks the unit for nothing.
     *
     * It runs whether or not there is a link, and is not cancelled when one falls over:
     * the fetch is for the phone's own schedule, and a unit that comes up five minutes into
     * an interval is the case that benefits.
     */
    private fun startWeatherTick() {
        if (weatherTick?.isActive == true) return
        weatherTick = scope.launch {
            while (isActive) {
                delay(WEATHER_TICK_MS)
                val key = feedUnitKey() ?: continue
                WeatherRuntime.coordinator(appContext).wake("tick", key)
            }
        }
    }

    /**
     * The unit the feed runs for: the connected one when there is one, otherwise the card
     * the owner selected. With no link there is no address to key on, so the card's MAC is
     * the identity — the same key the trip files and the weather cache already use.
     */
    private suspend fun feedUnitKey(): String? {
        (uiState.value as? ConnectionUiState.Connected)?.let {
            return UnitIdentity.key(it.mac, it.address)
        }
        val selected = vehicles.all().first().firstOrNull { it.id == selectedVehicleId.value }
            ?: return null
        // Neither a MAC nor a pairing means no identity to file a forecast under.
        return UnitIdentity.key(selected.mac, selected.bleAddress)
            .takeIf { it != UnitIdentity.UNKNOWN }
    }

    /**
     * The cache key for a link: the card's MAC when a card knows this address, so the
     * weather cache hangs off the same identity as the trip files and the session, not off
     * a re-pair. `UnitIdentity.key` falls back to the BLE address when no card claims it.
     */
    private suspend fun weatherKeyFor(address: String): String =
        UnitIdentity.key(
            vehicles.all().first().firstOrNull { it.bleAddress == address }?.mac,
            address,
        )

    /**
     * A link that came up under us: name it, resolve which card it belongs to, publish
     * it. The same identity rules as a dial, because "who does this belong to" does not
     * depend on who asked for the link.
     */
    private suspend fun adoptLiveLink(ready: BleLinkState.Ready) {
        val current = uiState.value as? ConnectionUiState.Connected
        if (current != null && current.address == ready.address) {
            attempts = 0
            return
        }
        val info = readIdentity()
        val r = ConnectionCore.decide(
            vehicles.all().first(), selectedVehicleId.value,
            tried = listOf(ready.address), scanDone = true, found = emptyList(),
            outcome = LinkOutcome.Ok(
                vehicleId = null, address = ready.address, name = ready.name,
                version = info.version, mac = info.mac, mtu = ready.mtu,
            ),
            mode = ConnectMode.SILENT, nowMs = now(),
        )
        r.persist?.let { if (!vehicles.put(it)) logPersistMiss(it.id, "silent identity read") }
        unclaimed.value = r.adoption
        r.notice?.let { notice.value = it }
        when (val n = r.next) {
            is NextAction.Connect -> applyConnect(n)
            is NextAction.Failed -> uiState.value = ConnectionUiState.Failed(n.reason)
            is NextAction.Idle -> uiState.value = ConnectionUiState.Idle(n.reason)
            else -> Unit // unreachable: an answer is in hand, so nothing is left to try
        }
    }

    /** One radio event, one ladder decision, at most one scheduled dial. */
    private suspend fun onLinkEvent(event: LinkEvent) {
        val card = selectedCard()
        val step = ReconnectLadder.step(
            event, attempts, card?.hasPairing == true, link.enabled(),
        )
        Log.d(DPP_CONN_LOG_TAG, "event ${event::class.simpleName} attempts=$attempts pairing=${card?.hasPairing == true} radio=${link.enabled()} -> $step")
        when (step) {
            LadderStep.Linked -> {
                attempts = 0
                rearms = 0
                gaveUp = false
                rearm?.cancel()
                retry?.cancel()
            }
            LadderStep.Stop -> Unit
            is LadderStep.GiveUp -> {
                attempts = 0
                retry?.cancel()
                uiState.value = ConnectionUiState.Failed(step.reason)
                // "The ladder is over until a finger moves" was the design, and on the bench
                // it was a defect: a unit that rebooted unattended stayed unreachable until
                // somebody tapped something, and with it its clock and its weather. The tick
                // below is the finger that does not need a person.
                gaveUp = true
                scheduleRearm()
            }
            is LadderStep.Retry -> {
                // The guard is about the link, not about the dial. A dial still in flight is not
                // a reason to forget the failure - it is usually the dial that *reported* this very
                // event, and dropping the retry there leaves the phone silent with the unit
                // advertising: after a firmware install rebooted the unit on the bench
                // (2026-11-02) the app dialed once, swallowed its own retry, and never tried
                // again for four minutes. What must not be dialled over is an attempt to build a
                // link that is actually progressing - that one will report again on its own.
                val now = link.state.value
                if (now is BleLinkState.Ready || now is BleLinkState.Connecting ||
                    now is BleLinkState.Pairing
                ) {
                    return
                }
                attempts++
                retry?.cancel()
                // The ladder is dialing again, so the slow tick has nothing to say until it
                // stops: a press or a rung of the ladder owns the failure, not the calendar.
                gaveUp = false
                rearm?.cancel()
                retry = scope.launch {
                    delay(step.delayMs)
                    if (currentCoroutineContext().isActive) {
                        // A reconnect never sweeps: the unit this phone is paired with
                        // is the only thing worth dialing, and a sweep mid-reconnect is
                        // how a phone in a car park ends up bonded to somebody else's
                        // dashboard.
                        startAttempt(ConnectMode.PROBE)
                    }
                }
            }
        }
    }

    /** The selected card, read from the store. Null when nothing is selected. */
    private suspend fun selectedCard(): Vehicle? {
        val id = selectedVehicleId.value ?: return null
        return try {
            vehicles.all().first().firstOrNull { it.id == id }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- the quiet restart

    /**
     * The five guards of [ReconnectLadder.rearmWanted], read as of now.
     *
     * [except] is the caller's own job when the caller is an attempt: an attempt that just
     * failed is the one dial that is definitely not in flight, and a guard that counted its
     * own caller would refuse every restart before it was scheduled.
     */
    private suspend fun rearmWantedNow(except: Job? = null): Boolean {
        val card = selectedCard()
        val s = link.state.value
        return ReconnectLadder.rearmWanted(
            foreground = foreground,
            hasPairing = card?.hasPairing == true,
            adapterOn = link.enabled(),
            // A link that is up already answers the question, and one being built reports
            // for itself. This is also the OTA guard, and it needs no new signal: a firmware
            // install holds the link in `Ready` from `begin_ok` to the reboot it triggers, so
            // a re-arm can never dial over an update in progress.
            linkBusy = s is BleLinkState.Ready || s is BleLinkState.Connecting ||
                s is BleLinkState.Pairing,
            dialInFlight = ladder?.isActive == true && ladder !== except,
        )
    }

    /**
     * Put the next quiet dial in the calendar — if [rearmWantedNow] permits it.
     *
     * One tick at a time, always replaced, so every path a finger owns (a press, a rung of
     * the ladder, a selection) cancels it by replacing it with nothing. The wait is a single
     * [delay] rather than a loop for the same reason the ladder is a ladder: nothing in this
     * class may put the radio on a timer that outlives the decision it was made for.
     */
    private suspend fun scheduleRearm(except: Job? = null) {
        if (!rearmWantedNow(except)) {
            // Named, because a capture that shows no dials has to be able to tell "the tick
            // was never armed" from "the tick was armed and the guards said no".
            Log.d(DPP_CONN_LOG_TAG, "rearm not scheduled: guards closed (foreground=$foreground pairing=${selectedCard()?.hasPairing == true} radio=${link.enabled()} state=${link.state.value::class.simpleName} dialInFlight=${ladder?.isActive == true})")
            return
        }
        val waitMs = ReconnectLadder.rearmDelayMs(rearms)
        rearm?.cancel()
        Log.d(DPP_CONN_LOG_TAG, "rearm scheduled in ${waitMs} ms rearmsSoFar=$rearms")
        rearm = scope.launch {
            delay(waitMs)
            // Re-read on waking: the radio can have gone off, a press can have taken the
            // ladder, and a link can have come up by itself in the meantime.
            if (currentCoroutineContext().isActive && rearmWantedNow()) dialForRearm()
        }
    }

    /**
     * One quiet dial of the stored pairing.
     *
     * `PROBE`, and never `SEARCH`: restarting a ladder is not a licence to put an automatic
     * sweep on the radio ([ConnectionMode.PROBE] keeps that rule where it belongs), and the
     * unit this phone is paired with is still the only thing worth reaching for. The attempt
     * counter goes back to zero because this is a new failure, not a fourth rung of the old
     * one — whatever the link reports next gets the ladder's full patience again.
     */
    private fun dialForRearm() {
        rearms++
        attempts = 0
        // Still unlinked: the restart regime continues past this dial, whether it answers or
        // not, and `onForegroundChanged` has to be able to tell that from a fresh boot.
        gaveUp = true
        Log.d(DPP_CONN_LOG_TAG, "rearm dial rearms=$rearms mode=PROBE")
        startAttempt(ConnectMode.PROBE)
    }

    fun dispose() {
        stopAdapterWatch()
        ladder?.cancel()
        retry?.cancel()
        rearm?.cancel()
        linkWatch?.cancel()
        weatherTick?.cancel()
        job.cancel()
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        /** How long one radio sweep runs; the link panel used the same window. */
        const val BLE_SCAN_MS = 15_000L

        /** How often this host wakes the weather feed while a link is up. */
        const val WEATHER_TICK_MS = 60_000L
    }
}
