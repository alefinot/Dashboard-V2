package com.alefinot.dashboardpp.vehicles

import android.content.Context
import com.alefinot.dashboardpp.storage.AppRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The garage on disk (vehicle page plan §3, shared storage plan §3): one JSON file,
 * `vehicles.json`, holding every card, the id counter and the selected card.
 *
 * It used to be a preferences DataStore — a `vehicle:<id>` row per card plus `vehicle:last` and
 * `vehicle:nextseq` — and the move to a file is not decoration. A garage the owner can open in a
 * file manager, next to their drive CSVs, is a garage they can back up, read and repair; a
 * directory of protobuf preference rows under `Android/data` is none of those things. The format
 * and its damage rules live in [GarageFile]; the split this class keeps is the one it always had:
 * [sorted] and [nextIdName] are pure functions in the companion because they are the decisions
 * worth arguing about, and everything that touches the file is a thin, boring read or one
 * [put]-style write under a lock.
 *
 * **One instance of the state, however many times this class is built.** The DataStore delegate
 * used to guarantee that ("one DataStore instance per name, per process"); a plain file gives the
 * guarantee back only if the code asks for it. So the cards, the counter and the mutation lock
 * live in [GarageState], which [forApp] hands out once per process, while this class stays a
 * lightweight handle. Two `VehicleStore`s with two in-memory garages would be two writers
 * overwriting each other's cards — the pager's store and the editor's store would each publish a
 * garage the other had never seen.
 *
 * **Nothing here decides identity:** a record's `mac` is stored and returned verbatim, and
 * [VehicleMatch] is the only place that text is interpreted. Nor does anything sort except through
 * [sorted].
 *
 * **Nothing here interprets a photo either:** the JPEG belongs to [VehiclePhotoStore], which this
 * store owns as [photos] so that deleting a card cannot leave its picture behind.
 */
class VehicleStore(context: Context) {
    private val appContext = context.applicationContext

    /**
     * The store that owns this garage's JPEGs, exposed so callers use *this* one:
     * [VehiclePhotoStore]'s decoded-photo cache is bounded in bytes per instance, so a second
     * instance would double the RAM budget and, worse, would not see the evictions the other one
     * makes when [delete] removes a picture.
     *
     * Unlike the garage itself, this is per handle rather than per process — as it was when the
     * garage lived in preferences. The pager and the editor each get their own cache today; the
     * garage could not tolerate that (two copies of the cards), a photo cache only costs RAM.
     */
    val photos: VehiclePhotoStore = VehiclePhotoStore(context)

    private val garage = forApp(appContext)

    /**
     * The garage in display order, live.
     *
     * The first emission waits for the file to have been read once, which is what every
     * `all().first()` caller in this app assumes: answering `emptyList()` before the read finished
     * would let the adoption path hand out `v1` while the file already contains `v3`.
     * Everything after that first emission is a change to the in-memory list, published by the
     * write that made it — no polling, no re-read.
     *
     * [flowOn] is load-bearing in the same way: the read is a real file on a real volume, and
     * Compose collects this flow on the main thread.
     */
    fun all(): Flow<List<Vehicle>> = flow {
        emit(garage.snapshot())
        emitAll(garage.cards)
    }.flowOn(Dispatchers.IO)

    /** One card by id, or null if it is gone. */
    suspend fun get(id: String): Vehicle? = garage.snapshot().firstOrNull { it.id == id }

    /**
     * Insert or replace, and report whether it reached the file. Renaming a card is a [put] of
     * the same `id` with a different `name` — there is no rename, because the id is what trips are
     * tagged with and it must never change.
     *
     * `id` must come from [nextId]: a card handed an id another card already holds would be
     * folded into it by the next read, and [GarageFile] answers "damaged" rather than picking a
     * winner.
     *
     * `false` means the file still holds what it held. The in-memory garage keeps the change — the
     * user typed it, and taking it back off the screen without saying so would be its own lie —
     * so the caller must say the sentence ([saveFailure]) instead of closing the form.
     */
    suspend fun put(v: Vehicle): Boolean = garage.put(v)

    /**
     * Remove one card, and report whether the file agrees.
     *
     * The pointer to the deleted card is dropped in the same write, so the pager never wakes up on
     * an id that no longer exists ([sorted] tolerates a stale pointer, but the UI would have
     * nothing to highlight).
     *
     * The card's JPEG goes with it, and only once the file says the card is gone: a photo removal
     * the user did not ask for is the worse failure, so a card whose file could not be deleted
     * costs disk space, not a card the user still wanted. [VehiclePhotoStore.delete] removes
     * `vehicles/<id>.jpg` and every cached decode of it, so a later card handed a fresh id cannot
     * inherit the deleted vehicle's picture.
     */
    suspend fun delete(id: String): Boolean {
        if (!garage.delete(id)) return false
        withContext(Dispatchers.IO) { photos.delete(id) }
        return true
    }

    /**
     * Point the garage at [id], and report whether the file agrees.
     *
     * Not validated against the cards on purpose: the caller chooses from [all], and checking here
     * would mean reading inside the write lock. A pointer to a card that no longer exists is
     * harmless — [sorted] puts nothing first, and [delete] clears it when it can.
     */
    suspend fun select(id: String): Boolean = garage.select(id)

    /** Which card the garage opens on, or null if none was ever picked. */
    suspend fun selectedId(): String? = garage.selectedId()

    /**
     * The next free card id — `v1`, `v2`, ... — reserved by writing the counter as it is read, so
     * two callers asking at once cannot be handed the same one.
     *
     * The counter is separate from the highest existing id on purpose: [delete] frees a row but
     * never its name, because a trip already tagged with `v3` must not find a different vehicle
     * when `v3` is handed out again.
     *
     * The name comes back even if the file could not be written: the caller is about to try to
     * save a card with it, and that is the failure worth reporting. A cancelled form costs a
     * skipped number.
     */
    suspend fun nextId(): String = garage.nextId()

    /**
     * Why the last write did not happen, in the one sentence the editor can show under the form.
     *
     * Two very different reasons get two different sentences, because they ask the user for two
     * different things. A write that simply failed (a full disk, a volume that vanished, a
     * permission revoked while the form was open) is retried once the phone is unstuck. A garage
     * whose file could not be parsed is *not* retried by pressing save again: the app is refusing
     * to write, because the alternative is writing an empty garage over someone's cards, and the
     * only fix is the file itself — which is why the sentence names it.
     *
     * [what] is the thing that did not happen, said the way the user would say it — "Photo not
     * attached", "Pairing not forgotten", "Card not deleted" — because a refusal that only says
     * "not saved" under a photo the user just attached tells them nothing about which action
     * failed. The default is the card form's own wording, which is where this started.
     */
    /**
     * Why the garage is being shown as empty when there is a file sitting there, or null when
     * there is nothing to explain. After the first read of the file, either way.
     *
     * This exists because [saveFailure] can only speak when asked: a save is an action the user
     * took, and the page that shows nothing at all invites none. Without this, an owner whose
     * `vehicles.json` got a brace deleted in a file manager sees "Nothing has been met yet" — the
     * sentence for a garage that never existed, over their cards still being on disk.
     */
    suspend fun damageNote(): String? = garage.damageNote()?.let { why ->
        // Which file matters now that the app looks in two: the shared folder and the app's own are
        // different places to open, and the owner has to open the right one to get their cards.
        val where = garage.damageWhere() ?: return@let why
        "$why, in $where"
    }

    /**
     * A second `vehicles.json` the app saw and did not use, or null when only one folder has a
     * garage. It names a folder the owner may be looking at while the app reads another one — the
     * shape the storage grant leaves behind when it moves — so the vehicle page says it out loud
     * instead of letting a "0 vehicles" screen stand unexplained.
     */
    suspend fun shadowGarageNote(): String? = garage.shadowGarageNote()

    /** The file the cards currently on screen came from. Null before the first read. */
    suspend fun garageNote(): String? = garage.garageNote()

    fun saveFailure(what: String = "Not saved"): String {
        val why = garage.damage()
        val where = garage.damageWhere() ?: AppRoot.sharedRootLabel() + "/" + AppRoot.GARAGE_FILE
        return if (why == null) {
            "$what \u2014 garage file could not be written. Try again."
        } else {
            "$what \u2014 $where unreadable ($why), not overwriting it."
        }
    }

    companion object {
        /**
         * One garage state per process, per app. [GarageState] carries the in-memory cards and the
         * mutation lock, so this is the "one instance per file, per process" promise the
         * `preferencesDataStore` delegate used to make; the two handles this app builds
         * (`ConnectionViewModel`, `TripsViewModel`) reach the same state through it.
         */
        private val shared = AtomicReference<GarageState>()

        private fun forApp(app: Context): GarageState {
            val existing = shared.get()
            if (existing != null) return existing
            val created = GarageState(app.applicationContext)
            // setIfAbsent rather than a field + synchronized: two ViewModels built on different
            // threads at startup, and the loser's instance is dropped before it can load anything.
            if (!shared.compareAndSet(null, created)) return shared.get()!!
            return created
        }

        /**
         * Garage order: the selected card first, then by most recent connect (newest first),
         * never-connected cards last, and ties broken by the older card first.
         *
         * Selected first because the garage opens where the user was. Connected before
         * never-connected because a card that has answered is a dashboard the user owns and a card
         * that has not is a draft. Newest first because that is the unit most likely to be in
         * front of them now. Never-connected cards have no `lastConnectedAtMs`, so they sort on the
         * minimum of that column — and among themselves the older card is the one they made first
         * and would look for first.
         *
         * A `selectedId` that matches no card (the card was deleted while the pointer still named
         * it) changes nothing: no row is ranked first, so the rest keep their order.
         */
        fun sorted(records: List<Vehicle>, selectedId: String?): List<Vehicle> =
            records.sortedWith(
                compareBy<Vehicle> { if (it.id == selectedId) 0 else 1 }
                    .thenByDescending { it.lastConnectedAtMs ?: Long.MIN_VALUE }
                    .thenBy { it.createdAtMs },
            )

        /**
         * The id to hand out given the last counter value [seq] and the ids that already have a
         * card, [taken].
         *
         * The skip is the point: a record restored by a future import, or written into the file by
         * hand, must not be overwritten by the next card the user makes. The scan only ever moves
         * forward, so a name that a [delete] freed is not handed out again — card ids double as trip
         * tags, and a new vehicle wearing a dead card's id would inherit its drives.
         */
        fun nextIdName(seq: Int, taken: Set<String>): String {
            // A missing or damaged counter restarts at v1 rather than producing "v0" or "v-3",
            // which would be an id no reader of this store expects.
            var n = if (seq < 1) 1 else seq + 1
            while ("v$n" in taken) n++
            return "v$n"
        }
    }
}

/**
 * The garage as in-memory state with one file behind it, and the only thing in the process that
 * holds either.
 *
 * Every mutation is read-modify-write under [mutex] and lands as one whole-file write, which is
 * what the format makes possible ([GarageFile] holds everything, so there is no partial update to
 * lose). The lock covers the write, not the screen: while the editor is open the file can be
 * written by nothing else in this process, and anything outside it (a file manager on the same
 * folder) is a race this app has never claimed to win.
 *
 * **A file it cannot read is never written to.** The first read that answers
 * [GarageScan.Blocked] sets [damage] and every later mutation refuses; the UI shows an empty
 * garage (there is no safe way to show a file the app does not understand), and recovery is a hand
 * edit of the file rather than a guess by this class. [VehicleStore.saveFailure] is the sentence
 * that explains it, and [write] is the gate that makes the promise true for every caller.
 *
 * The file is resolved once per session, by [ensure], and remembered: [GarageFile.readBoth] reads
 * both roots and names the one that holds the cards, and every later write goes to that same file.
 * The two are not resolved twice on purpose. They used to be — the write asked [AppRoot] again, so
 * a storage grant that moved mid-session wrote the cards into a different folder from the one the
 * next read would trust — and that split, plus a hollow `{"vehicles":[]}` left in the folder the app
 * happened to be looking at, is the second way this owner's garage went missing on 2026-11-09.
 * Neither root is ever merged with the other: the file with cards in it wins, an empty one loses to
 * a file that has cards, and a file this build cannot read blocks every write and says so
 * ([GarageFile.readBoth] holds those rules). What the store will never do is decide a garage is
 * empty because it looked in one folder instead of two — that is the mistake that deleted a user's
 * only card on 2026-11-09, and [write] is what keeps it deleted-in-one-place only, ever again.
 */
private class GarageState(private val appContext: Context) {

    /** Written only under [mutex]; read by the UI through [cards], never mutated in place. */
    private val _cards = MutableStateFlow<List<Vehicle>>(emptyList())

    /** Every change to the cards, published by the write that made it. */
    val cards: StateFlow<List<Vehicle>> = _cards.asStateFlow()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var garage = Garage()
    private var loaded = false
    private var damage: String? = null

    /** The file [damage] is about, in words the owner can open. Null while nothing blocks a write. */
    private var damageWhere: String? = null

    /**
     * Whether a write is allowed at all: true only once [ensure] has looked at both places the
     * garage can live and found nothing it must not touch.
     *
     * This is the invariant the whole store rests on, and it used to be enforced by convention —
     * every mutation was *supposed* to route through [mutate], which checks [damage]. One mutation
     * did not, and the convention is what a garage died behind on 2026-11-09: `nextId` wrote the
     * in-memory garage straight to the file, and the in-memory garage was the untouched default
     * because the read had produced nothing. So the check does not live in the callers any more.
     */
    private var writable = false

    /**
     * The file the cards in memory came from, and the file every later write goes to. Set once by
     * [ensure] from [GarageScan.Found.from]; null while nothing was read or when neither root held
     * a garage, in which case [write] falls back to where the app is currently allowed to write.
     */
    private var activeGarage: File? = null

    /**
     * A second `vehicles.json` the read noticed and did not use: the other root holding cards of its
     * own, or a hollow `{"vehicles":[]}` sitting in the root the app is looking at. Not an error —
     * the cards are shown and written safely either way — but it is the fingerprint of a storage
     * grant that has moved under the app, so it is kept for the screen and the log.
     */
    private var shadowGarage: File? = null

    init {
        // Start the read as soon as the first handle appears, so the file is warm by the time the
        // garage is drawn; every reader goes through ensure() anyway, under the same lock.
        scope.launch { mutex.withLock { ensure() } }
    }

    /**
     * Snapshot of the cards, waiting for the first read of the file so that a caller asking
     * immediately after process start is not handed the empty list the flow starts on.
     *
     * The fallback after [ensure] is unreachable in practice — [ensure] publishes before it lets
     * go of the lock, and the list only ever goes from empty to the file's contents — but writing
     * `!!` about a value the UI is about to draw is not worth the confidence.
     */
    suspend fun snapshot(): List<Vehicle> = withContext(Dispatchers.IO) {
        mutex.withLock { ensure() }
        _cards.value
    }

    suspend fun selectedId(): String? = withContext(Dispatchers.IO) {
        mutex.withLock { ensure() }
        garage.selectedId
    }

    suspend fun put(v: Vehicle): Boolean = mutate { g ->
        val at = g.vehicles.indexOfFirst { it.id == v.id }
        val cards = g.vehicles.toMutableList()
        if (at >= 0) cards[at] = v else cards.add(v)
        g.copy(vehicles = cards)
    }

    suspend fun delete(id: String): Boolean = mutate { g ->
        g.copy(
            vehicles = g.vehicles.filterNot { it.id == id },
            selectedId = if (g.selectedId == id) null else g.selectedId,
        )
    }

    suspend fun select(id: String): Boolean = mutate { g -> g.copy(selectedId = id) }

    suspend fun nextId(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensure()
            val name = VehicleStore.nextIdName(garage.nextSeq, garage.vehicles.map { it.id }.toSet())
            // The counter is bumped in memory and then written: its whole job is to stop a later
            // process re-handing the name. A failed write leaves the reservation in memory only,
            // and the card that came with it fails to save too, which is what the caller reports.
            //
            // This is the mutation that does not go through [mutate], which is why [write] rather
            // than the caller decides whether a write may happen at all: on 2026-11-09 this line
            // wrote an empty garage over a user's cards, because the read behind it had produced
            // nothing and nothing here asked whether that was safe.
            garage = garage.copy(nextSeq = name.substring(1).toIntOrNull() ?: garage.nextSeq)
            write()
            publish()
            name
        }
    }

    /** Why writes are refused, or null while the app believes it can write. */
    fun damage(): String? = damage

    /** The file [damage] is about, in words the owner can open. Null while nothing blocks a write. */
    fun damageWhere(): String? = damageWhere

    /**
     * The file the loaded garage came from, in the words [AppRoot.garageLabel] uses. Null before the
     * first read.
     */
    suspend fun garageNote(): String? = withContext(Dispatchers.IO) {
        mutex.withLock { ensure() }
        activeGarage?.let { AppRoot.garageLabel(it) }
    }

    /**
     * The other `vehicles.json` the read saw and did not use, in words the owner can open; null when
     * only one root has a garage. See [shadowGarage].
     */
    suspend fun shadowGarageNote(): String? = withContext(Dispatchers.IO) {
        mutex.withLock { ensure() }
        shadowGarage?.let { AppRoot.garageLabel(it) }
    }

    /**
     * [damage] *after* the file has actually been read.
     *
     * The plain accessor answers null before the first read, which is correct for the write path
     * (it runs under the lock, after `ensure()`) and wrong for a screen asking at startup: a page
     * that checked it before the read would tell the owner nothing is wrong with a file it has not
     * opened yet. This waits for the read, so an answer of null means the file was looked at.
     */
    suspend fun damageNote(): String? = withContext(Dispatchers.IO) {
        mutex.withLock { ensure() }
        damage
    }

    /** Read → change → write → publish, or refuse outright on a damaged file. */
    private suspend fun mutate(change: (Garage) -> Garage): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensure()
            if (damage != null) return@withLock false
            garage = change(garage)
            // The in-memory garage keeps the change whatever the file says, so the screen the user
            // typed into does not quietly disagree with itself; the caller checks the answer.
            publish()
            write()
        }
    }

    /** Read the garage once, in both places it can live. Called under [mutex]. */
    private fun ensure() {
        if (loaded) return
        loaded = true
        when (val scan = GarageFile.readBoth(
            AppRoot.garageFile(appContext),
            AppRoot.privateGarageFile(appContext),
        )) {
            // Cards found. `from` is where they live and where they go back: the store does not
            // re-pick a folder between the read and the write, so a storage grant that moves during
            // the session cannot split the garage across two files. The migration to a newly granted
            // shared root happens at the next start, by this same read.
            is GarageScan.Found -> {
                garage = scan.garage
                writable = true
                activeGarage = scan.from
                shadowGarage = scan.shadow
            }
            // Nothing anywhere: a new install. The empty default stands, and writing it is allowed.
            GarageScan.Nothing -> writable = true
            // A file this build cannot read, in either root. No write is allowed until it is
            // repaired: the cards may be inside it, and "empty garage" is the one answer that
            // would delete them without a word.
            is GarageScan.Blocked -> {
                damage = scan.why
                damageWhere = AppRoot.garageLabel(scan.where)
            }
        }
        publish()
    }

    private fun publish() {
        _cards.value = VehicleStore.sorted(garage.vehicles, garage.selectedId)
    }

    /**
     * Write the whole garage through [GarageFile.writeAtomically], so there is one atomic writer in
     * the app and one set of failure rules. A failed write leaves the previous file in place, which
     * is the only reason the refusal in [VehicleStore.saveFailure] is truthful.
     *
     * The target is the file [ensure] read the cards from, not a fresh answer from [AppRoot]. It
     * used to be resolved here on every write "so a grant given while the app runs moves the garage
     * to the shared folder", and that is the other half of how a garage was lost on 2026-11-09: a
     * reader that follows the cards and a writer that follows the storage grant can name two
     * different files in the same breath, and then the copy in the second one is what the *next*
     * read finds. One garage, one file, per session: the move to a newly granted shared root happens
     * at the next start, when [ensure] reads and picks the root in one decision.
     *
     * **The first line is the store's real safety rule**, and it is here rather than in the callers
     * because a caller once forgot it: nothing reaches the file unless [ensure] actually looked at
     * both roots and permitted writing. A garage the read never produced — a file that will not
     * parse, a folder the app only started looking at while the cards are in the other one — is an
     * empty garage as far as [GarageFile.encode] is concerned, and encoding it is a delete.
     */
    private fun write(): Boolean {
        if (!writable) return false
        return GarageFile.writeAtomically(activeGarage ?: AppRoot.garageFile(appContext), GarageFile.encode(garage))
    }
}
