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
import java.util.concurrent.atomic.AtomicReference

class VehicleStore(context: Context) {
    private val appContext = context.applicationContext

    val photos: VehiclePhotoStore = VehiclePhotoStore(context)

    private val garage = forApp(appContext)

    fun all(): Flow<List<Vehicle>> = flow {
        emit(garage.snapshot())
        emitAll(garage.cards)
    }.flowOn(Dispatchers.IO)

    /** One card by id, or null if it is gone. */
    suspend fun get(id: String): Vehicle? = garage.snapshot().firstOrNull { it.id == id }

    suspend fun put(v: Vehicle): Boolean = garage.put(v)

    suspend fun delete(id: String): Boolean {
        if (!garage.delete(id)) return false
        withContext(Dispatchers.IO) { photos.delete(id) }
        return true
    }

    suspend fun select(id: String): Boolean = garage.select(id)

    /** Which card the garage opens on, or null if none was ever picked. */
    suspend fun selectedId(): String? = garage.selectedId()

    suspend fun nextId(): String = garage.nextId()

    suspend fun damageNote(): String? = garage.damageNote()

    fun saveFailure(what: String = "Not saved"): String {
        val why = garage.damage()
        val where = AppRoot.sharedRootLabel() + "/" + AppRoot.GARAGE_FILE
        return if (why == null) {
            "$what — the garage file could not be written, and nothing on disk changed. Try again " +
                "once the phone has room or the storage permission is back."
        } else {
            "$what — $where could not be read ($why), and the app will not write over a garage it " +
                "cannot read. Nothing on disk changed."
        }
    }

    companion object {
        private val shared = AtomicReference<GarageState>()

        private fun forApp(app: Context): GarageState {
            val existing = shared.get()
            if (existing != null) return existing
            val created = GarageState(app.applicationContext)
            if (!shared.compareAndSet(null, created)) return shared.get()!!
            return created
        }

        fun sorted(records: List<Vehicle>, selectedId: String?): List<Vehicle> =
            records.sortedWith(
                compareBy<Vehicle> { if (it.id == selectedId) 0 else 1 }
                    .thenByDescending { it.lastConnectedAtMs ?: Long.MIN_VALUE }
                    .thenBy { it.createdAtMs },
            )

        fun nextIdName(seq: Int, taken: Set<String>): String {
            var n = if (seq < 1) 1 else seq + 1
            while ("v$n" in taken) n++
            return "v$n"
        }
    }
}

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

    init {
        scope.launch { mutex.withLock { ensure() } }
    }

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
            garage = garage.copy(nextSeq = name.substring(1).toIntOrNull() ?: garage.nextSeq)
            write()
            publish()
            name
        }
    }

    /** Why writes are refused, or null while the app believes it can write. */
    fun damage(): String? = damage

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
            publish()
            write()
        }
    }

    /** Read the file once. Called under [mutex]. */
    private fun ensure() {
        if (loaded) return
        loaded = true
        when (val read = GarageFile.read(AppRoot.garageFile(appContext))) {
            GarageRead.Absent -> Unit
            is GarageRead.Ok -> garage = read.garage
            is GarageRead.Damaged -> damage = read.why
        }
        publish()
    }

    private fun publish() {
        _cards.value = VehicleStore.sorted(garage.vehicles, garage.selectedId)
    }

    private fun write(): Boolean =
        GarageFile.writeAtomically(AppRoot.garageFile(appContext), GarageFile.encode(garage))
}
