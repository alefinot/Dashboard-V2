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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.alefinot.dashboardpp.ble.BleApi
import com.alefinot.dashboardpp.ble.BleJson
import com.alefinot.dashboardpp.ble.BleLink
import com.alefinot.dashboardpp.ble.BleLinkState
import com.alefinot.dashboardpp.ble.BleRuntime
import com.alefinot.dashboardpp.ble.scanFailureText
import com.alefinot.dashboardpp.trips.RecorderSettings
import com.alefinot.dashboardpp.trips.RecorderTarget
import com.alefinot.dashboardpp.vehicles.UnitIdentity
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.vehicles.CropRect
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
     * Bumped every time a card's photo changes on disk. A cover that recomposes on it
     * follows a crop that landed while the editor was open; without it the composable keeps
     * the bitmap it decoded when the screen opened and the card shows a picture the user has
     * already replaced.
     */
    val photoVersion = mutableIntStateOf(0)

    /** The garage, for the vehicle page. */
    fun vehicleCards() = vehicles.all()

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
        adapterOn.value = link.enabled()
        permissionMissing.value = missingBlePermissions().isNotEmpty()
        scope.launch {
            selectedVehicleId.value = vehicles.selectedId()
            startAttempt(ConnectMode.SILENT)
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
        bondNote.value = null
        startAttempt(ConnectMode.SEARCH, pinned = found, preScanned = scanResults.value)
    }

    /**
     * Clear this card's pairing and identity, leaving the card, its name and its photo.
     *
     * The difference from DELETE THE CARD is the whole point of having both: a card is a
     * vehicle, and a pairing is one phone's way of reaching it. Moving a dashboard to
     * another car, or un-pairing a bench unit without losing the trips and the name, is
     * this action; deleting the vehicle from the app's world is the editor's.
     *
     * It also drops the link when that card owns it — forgetting a unit you are standing
     * on would otherwise leave a live session attributed to a card that just said it does
     * not know the unit. Android's bond is out of reach (see [bondNote]), so the page is
     * told when that half is left undone.
     */
    fun forgetUnit(vehicleId: String) {
        scope.launch {
            val card = vehicles.get(vehicleId) ?: return@launch
            val bonded = card.bleAddress?.let { link.isBonded(it) } == true
            val decision = ConnectionCore.forgetUnit(card.bleAddress != null, bonded)
            if (card.bleAddress == null && card.mac == null) {
                // Nothing to forget. Saying so is better than a note that looks like it
                // did something.
                bondNote.value = "That card holds no pairing on this phone."
                return@launch
            }
            vehicles.put(card.copy(bleAddress = null, mac = null))
            if ((uiState.value as? ConnectionUiState.Connected)?.vehicleId == vehicleId) {
                disconnect()
            } else {
                uiState.value = ConnectionUiState.Idle(
                    "Select a vehicle to connect to it over Bluetooth.",
                )
            }
            bondNote.value = decision.note
            unclaimed.value = null
            notice.value = null
        }
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
        attempts = 0
        ladder?.cancel()
        retry?.cancel()
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
        attempts = 0
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
     * Highlight [vehicleId] without reaching for it.
     *
     * A connected session always belongs to the card it connected through, so selecting
     * a different card while one is connected ends that connection - otherwise the next
     * drive would be tagged to a vehicle that is not the one being driven. The recorder
     * stops because the state is no longer `Connected`, which is the same path a
     * DISCONNECT takes. The link itself is left alone: the next attempt dials its own
     * peer, and tearing the radio down here would drop a link the settings page may be
     * using.
     */
    fun selectVehicle(vehicleId: String) {
        attempts = 0
        ladder?.cancel()
        retry?.cancel()
        scope.launch {
            vehicles.select(vehicleId)
            selectedVehicleId.value = vehicleId
            notice.value = null
            unclaimed.value = null
            if (uiState.value is ConnectionUiState.Connected) {
                uiState.value = ConnectionUiState.Idle(null)
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
     */
    fun saveVehicle(typed: Vehicle, onDone: (refusal: String?) -> Unit = {}) {
        scope.launch {
            val all = vehicles.all().first()
            val stored = all.firstOrNull { it.id == typed.id }
            val merged = stored?.let { mergeTypedFields(it, typed) } ?: typed
            val taken = all.filter { it.id != merged.id }.map { it.name }.toSet()
            val finished = merged.copy(name = displayName(merged.name, merged, taken))
            macConflict(finished, all)?.let { clash ->
                onDone(refusalMessage(clash))
                return@launch
            }
            vehicles.put(finished)
            // A card that did not exist before becomes the selected one: the user made it
            // from a unit that is in front of them, and the next thing they do is connect.
            if (stored == null) {
                vehicles.select(finished.id)
                selectedVehicleId.value = finished.id
                unclaimed.value = null
            }
            onDone(null)
        }
    }

    /**
     * Forget a vehicle: its card, its JPEG, and the recorder's session row for the unit it
     * was. The drives it took stay exactly where they are - a trip's file name
     * carries this card's id, and the log outlives the card on purpose.
     *
     * The session row goes *before* the card is removed, because a row is a claim about a
     * unit; if the card disappears first, a crash in between leaves the claim pointing at
     * nothing. Removing the row first leaves the worst case as a card that still exists
     * with a clean identity, which the next connect refills.
     */
    fun deleteVehicle(vehicleId: String) {
        scope.launch {
            val v = vehicles.get(vehicleId) ?: return@launch
            val ownsSession = (uiState.value as? ConnectionUiState.Connected)?.vehicleId == vehicleId
            if (ownsSession) ladder?.cancel()
            tripSettings.clearSessions(listOf(UnitIdentity.key(v.mac, v.bleAddress)))
            vehicles.delete(vehicleId)
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
                uiState.value = ConnectionUiState.Idle(null)
                notice.value = null
                unclaimed.value = null
            }
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
     * Cut the square the user framed into the card's photo, then point the record at it.
     *
     * Two writes, and the order carries the reasoning. The JPEG has to exist before a record
     * claims it, otherwise a saved card can name a file that never arrived - the card would
     * draw a placeholder and the editor would offer to open nothing. So the store lands the
     * picture first, and only then does [VehicleStore.put] store the name. A crash between
     * the two costs a JPEG on disk that no record names yet: invisible, and the next crop
     * overwrites it.
     *
     * The record is read *before* the crop is attempted, which also closes a window: a card
     * deleted while the crop screen was open has no photo to receive, and writing one would
     * leave a picture named after a card that no longer exists.
     *
     * [onDone] runs on [scope] with the file the photo landed at, or `null` when the crop
     * failed - in which case the card keeps the photo it had and the editor says so rather
     * than closing. A user who cancels the crop screen never reaches this method:
     * [cancelPhoto] removes the scratch capture and the previous photo stays, which is the
     * plan's "CANCEL keeps the previous photo".
     */
    fun applyPhotoCrop(source: File, vehicleId: String, rect: CropRect, onDone: (File?) -> Unit) {
        scope.launch {
            val card = vehicles.get(vehicleId) ?: run {
                vehicles.photos.clearCapture(vehicleId)
                onDone(null)
                return@launch
            }
            val landed = vehicles.photos.cropFrom(source, vehicleId, rect)
            if (landed == null) {
                onDone(null)
                return@launch
            }
            vehicles.put(card.copy(photoFile = landed.name))
            photoVersion.intValue++
            onDone(landed)
        }
    }

    /** The user decided against the picture they framed: the scratch capture goes. */
    fun cancelPhoto(vehicleId: String) {
        scope.launch { vehicles.photos.clearCapture(vehicleId) }
    }

    /**
     * Take a card's photo off: the JPEG first, then the record's pointer.
     *
     * That order is the one a crash can survive. A record whose `photoFile` names a file that
     * is gone reads back as "no photo" ([VehiclePhotoStore.read] answers null and the card
     * draws its glyph), which is a cosmetic lie for one boot. The reverse - a file deleted
     * after the record still names it, or a record pointing at a file that was never written
     * - is the case where the editor offers a photo the app cannot show.
     */
    fun removePhoto(vehicleId: String) {
        scope.launch {
            vehicles.photos.delete(vehicleId)
            vehicles.get(vehicleId)?.let { card ->
                if (card.photoFile != null) vehicles.put(card.copy(photoFile = null))
            }
            photoVersion.intValue++
        }
    }

    /** The connected card's DISCONNECT. The selection stays; the unit goes. */
    fun disconnect() {
        ladder?.cancel()
        retry?.cancel()
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
        while (currentCoroutineContext().isActive) {
            val r = ConnectionCore.decide(
                vehicles.all().first(), selectedVehicleId.value,
                tried, scanDone, found, outcome, mode, now(), pinned,
            )
            r.persist?.let { vehicles.put(it) }
            unclaimed.value = r.adoption
            r.notice?.let { notice.value = it }
            when (val n = r.next) {
                is NextAction.Idle -> {
                    uiState.value = ConnectionUiState.Idle(n.reason)
                    return
                }
                is NextAction.Failed -> {
                    uiState.value = ConnectionUiState.Failed(n.reason)
                    return
                }
                is NextAction.Connect -> {
                    applyConnect(n)
                    return
                }
                is NextAction.Scan -> {
                    uiState.value = ConnectionUiState.Connecting(mode, AttemptStage.SCAN)
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
        uiState.value = ConnectionUiState.Connecting(mode, AttemptStage.DIAL)
        val mirror = scope.launch {
            link.state.collect { s ->
                val stage = when (s) {
                    is BleLinkState.Pairing -> AttemptStage.PAIR
                    is BleLinkState.Connecting -> AttemptStage.DIAL
                    else -> null
                }
                if (stage != null) {
                    uiState.value = ConnectionUiState.Connecting(mode, stage)
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
        val perf = link.request("perf")
        val mac = if (perf.ok) BleJson.string(perf.fields, "mac") else null
        val snap = api.snapshot()
        return UnitInfo(mac = mac, version = snap?.version)
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
        attempts = 0
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
                when (s) {
                    is BleLinkState.Ready -> {
                        pushClock(s)
                        if (ladder?.isActive != true) adoptLiveLink(s)
                    }
                    is BleLinkState.Failed -> if (ladder?.isActive != true) {
                        onLinkEvent(LinkEvent.Dropped(s.reason))
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
            timeNote.value = if (reply.ok) {
                null
            } else {
                "This phone could not set the unit's clock. Trip files are stamped by the " +
                    "phone, so they are unaffected; the dashboard's own stamps may be wrong."
            }
        }
    }

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
        r.persist?.let { vehicles.put(it) }
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
        when (step) {
            LadderStep.Linked -> {
                attempts = 0
                retry?.cancel()
            }
            LadderStep.Stop -> Unit
            is LadderStep.GiveUp -> {
                attempts = 0
                retry?.cancel()
                uiState.value = ConnectionUiState.Failed(step.reason)
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

    fun dispose() {
        stopAdapterWatch()
        ladder?.cancel()
        retry?.cancel()
        linkWatch?.cancel()
        job.cancel()
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        /** How long one radio sweep runs; the link panel used the same window. */
        const val BLE_SCAN_MS = 15_000L
    }
}
