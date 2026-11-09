package com.alefinot.dashboardpp.vehicles

import com.alefinot.dashboardpp.ble.BleJson
import java.io.File

/**
 * The garage as one file: `Documents/Dashboard++/vehicles.json` (shared storage plan §3).
 *
 * One file replaced the `vehicle_store` preferences — its `vehicle:<id>` rows, its `vehicle:last`
 * pointer and its `vehicle:nextseq` counter — so that the whole garage is something the owner can
 * open, read and repair in a text editor, in the same folder as their drive CSVs. The shape is
 * deliberately boring and self-describing:
 *
 * ```json
 * {"version":1,"nextSeq":7,"selectedId":"v3","vehicles":[ {"v":"v1","id":"v3",…} ]}
 * ```
 *
 * Each entry of `vehicles` is [VehicleCodec.encode] verbatim: this file adds a container, not a
 * second record format, so the rules that decide what a damaged *field* does (default that field,
 * keep the card) are the ones already written and tested there.
 *
 * **The container is not forgiving, and that is the point.** [VehicleCodec] tolerates a torn field
 * because one bad field should not cost one card. Here a torn file answers [GarageRead.Damaged],
 * because the unit of storage is now the whole garage: if the app decoded a truncated file as
 * "empty garage" and then saved, every card in the file would be gone by the time the user
 * noticed. So [VehicleStore] refuses to write over a file it cannot read, and says so, rather than
 * guessing. [GarageRead.Damaged] and [GarageRead.Absent] are therefore separate outcomes — "there
 * is nothing here yet" and "there is something here I dare not touch" must never be confused.
 */
object GarageFile {

    /** Container format tag. A file that says anything else is not half-read: it is [Damaged]. */
    const val VERSION = 1

    /** The text to write: compact, one line, records in the order [Garage.vehicles] holds them. */
    fun encode(garage: Garage): String = buildString {
        append("{\"version\":").append(VERSION)
        append(",\"nextSeq\":").append(garage.nextSeq)
        append(",\"selectedId\":")
        append(if (garage.selectedId == null) "null" else BleJson.quote(garage.selectedId))
        append(",\"vehicles\":[")
        garage.vehicles.joinTo(this, separator = ",") { VehicleCodec.encode(it) }
        append("]}")
    }

    /**
     * Read the garage from [file]: [GarageRead.Absent] when there is no such file, [GarageRead.Ok]
     * when it parsed, [GarageRead.Damaged] when there is something there this build will not write
     * over. It never throws — a vanished volume and a torn file reach the caller the same way, as
     * damage, because the safe response to both is the same: do not write.
     */
    fun read(file: File): GarageRead = try {
        if (!file.isFile) GarageRead.Absent else decode(file.readText())
    } catch (e: Exception) {
        GarageRead.Damaged("the file could not be read (${e.javaClass.simpleName})")
    }

    /**
     * The garage, wherever it actually is: [writeTarget] — the file the app would overwrite — and
     * [fallback], the copy under the app's own folder. The rule behind the order is that only the
     * first of the two is ever at risk from a write, so only the first of the two can be a hole.
     *
     * Both roots have held this file: the storage grant moves it (shared storage plan §3), and a
     * volume unmounting briefly moves it by itself. A reader that asks only where the app is
     * *currently* allowed to write answers "no garage here" exactly where the owner left their
     * cards — and an empty answer is the one answer this class lets the store write over. Bench,
     * 2026-11-09: that is how a garage of one card became an empty file.
     *
     * What each outcome means, and why:
     *
     * - **write target reads clean** → it wins, whatever the other holds. Two garages is a state
     *   the app does not merge; the Trips list shows both folders instead, and silently preferring
     *   a merged mix of two garages would be worse than either.
     * - **write target cannot be read** → [GarageScan.Blocked], even if the fallback is perfect.
     *   Falling back there would let the next write overwrite a file the app could not read, which
     *   is the silent delete [read] exists to prevent.
     * - **write target empty, fallback holds a garage** → [GarageScan.Found] from the fallback:
     *   the cards come back, and the next write moves them to where the app is now looking.
     * - **write target empty, fallback unreadable** → [GarageScan.Blocked], naming the fallback.
     *   "Nothing here yet" and "there is something here I dare not touch" must never be confused,
     *   and here the something is the only copy of the owner's cards.
     * - **neither exists** → [GarageScan.Nothing], and writing freely is correct.
     *
     * The two arguments may name the same file — on a device with no shared grant, [writeTarget]
     * *is* the private root. Reading it twice is cheap and it keeps this free of a special case.
     */
    fun readBoth(writeTarget: File, fallback: File): GarageScan =
        when (val primary = read(writeTarget)) {
            is GarageRead.Ok -> GarageScan.Found(primary.garage, writeTarget)
            is GarageRead.Damaged -> GarageScan.Blocked(writeTarget, primary.why)
            GarageRead.Absent -> when (val other = read(fallback)) {
                is GarageRead.Ok -> GarageScan.Found(other.garage, fallback)
                is GarageRead.Damaged -> GarageScan.Blocked(fallback, other.why)
                GarageRead.Absent -> GarageScan.Nothing
            }
        }

    /**
     * Write [text] to [file] so that the file either holds the whole new garage or still holds the
     * old one: encode into `<name>.tmp` in the same directory, then rename over the target — the
     * [VehiclePhotoStore] commit discipline, applied to a JSON file. `false` means the bytes did
     * not land, and the file that was there is untouched.
     *
     * The temp file goes in the target's own directory because a rename is only atomic within one
     * filesystem, and `finally { delete() }` because a half-written `vehicles.json.tmp` in the
     * user's Documents folder is litter that looks like data.
     */
    fun writeAtomically(file: File, text: String): Boolean {
        val dir = file.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val tmp = File(dir, file.name + TMP_SUFFIX)
        var landed = false
        try {
            tmp.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)) }
            landed = tmp.renameTo(file)
        } catch (e: Exception) {
            // IOException from a full disk, SecurityException from a permission revoked mid-form:
            // the caller reports the refusal, and the previous file is still on disk either way.
        } finally {
            if (!landed) tmp.delete()
        }
        return landed
    }

    /**
     * Read the whole text as a garage. Never throws, and never half-succeeds: either every record
     * decoded, or the answer is [GarageRead.Damaged] with the reason that makes writing over this
     * file unsafe.
     *
     * What counts as damage, and why each one is not tolerated:
     *
     * - **not JSON, not an object, or empty** — a truncated write (the OS stopped us mid-`rename`,
     *   a user edited it and lost a brace). Saving would be a silent delete.
     * - **`version` absent or not [VERSION]** — a later build's file, whose new fields this build
     *   would drop on the next write.
     * - **`vehicles` absent or not an array** — the same truncation story one level down; a
     *   missing key is a file that was never written correctly, not an empty garage.
     * - **one record that will not decode** — with one file, one torn record is a torn file. This
     *   is stricter than the old per-row store, where a bad row cost one card; the strictness is
     *   what stops a repair-by-overwrite.
     * - **two records with the same `id`** — a hand-edit slip, and ids are trip tags: writing that
     *   file back would merge two people's drives under one card.
     *
     * An empty `vehicles` array is *not* damage — that is a garage with no cards, and the app is
     * free to write it.
     */
    fun decode(text: String): GarageRead {
        if (text.isBlank()) return GarageRead.Damaged("the file is empty")
        val parsed = VehicleCodec.parseValue(text)
        if (parsed !is Map<*, *>) return GarageRead.Damaged("the file is not a JSON object")

        val version = if (parsed.containsKey("version")) VehicleCodec.intOrNull(parsed, "version") else null
        if (version != VERSION) {
            return GarageRead.Damaged(
                "the file declares version ${VehicleCodec.textOf(parsed["version"])}; this build writes $VERSION",
            )
        }

        val records = parsed["vehicles"]
        if (records !is List<*>) {
            return GarageRead.Damaged(
                if (parsed.containsKey("vehicles")) "\"vehicles\" is not a list" else "\"vehicles\" is missing",
            )
        }

        val out = ArrayList<Vehicle>(records.size)
        val seen = HashSet<String>(records.size)
        for ((i, raw) in records.withIndex()) {
            if (raw !is Map<*, *>) return GarageRead.Damaged("vehicle ${i + 1} is not an object")
            val v = VehicleCodec.decode(raw)
                ?: return GarageRead.Damaged("vehicle ${i + 1} cannot be read by this build")
            if (v.id.isBlank()) return GarageRead.Damaged("vehicle ${i + 1} has no id")
            if (!seen.add(v.id)) return GarageRead.Damaged("two cards are both called ${v.id}")
            out.add(v)
        }

        // The counter and the pointer are bookkeeping, not cards: a damaged one is worth fixing,
        // not worth losing the garage over. Absent or nonsense restarts the counter at the highest
        // id in the file (never below it: an id already used as a trip tag must not be handed out
        // again), and an unknown selected card is simply no card selected — sorted() already
        // treats a pointer that matches nothing as "nothing first".
        val highest = out.mapNotNull { it.id.removePrefix("v").toIntOrNull() }.maxOrNull() ?: 0
        val seq = VehicleCodec.intOrNull(parsed, "nextSeq")
        return GarageRead.Ok(
            Garage(
                nextSeq = if (seq != null && seq >= 1 && seq >= highest) seq else highest,
                selectedId = parsed["selectedId"] as? String,
                vehicles = out,
            ),
        )
    }

    /** Suffix of the scratch file [writeAtomically] renames. Not `.json.tmp`: see its doc. */
    private const val TMP_SUFFIX = ".tmp"
}

/**
 * Everything the app knows about the garage at one moment: the cards, the next id to hand out,
 * and which card the pager opens on.
 *
 * [vehicles] is in no particular order — [VehicleStore.sorted] is the only authority on display
 * order, and it is applied on read, not written into the file.
 */
data class Garage(
    val nextSeq: Int = 0,
    val selectedId: String? = null,
    val vehicles: List<Vehicle> = emptyList(),
)

/**
 * What one read of `vehicles.json` answered. Three outcomes because two of them must never be
 * conflated: an absent file is a new install (write freely), a damaged file is someone's data
 * (write nothing).
 */
sealed class GarageRead {
    /** No file where the garage lives. */
    object Absent : GarageRead()

    /** Read in full. The garage may legitimately be empty. */
    data class Ok(val garage: Garage) : GarageRead()

    /** Something is there and it is not safe to overwrite it. */
    data class Damaged(val why: String) : GarageRead()
}

/**
 * What [GarageFile.readBoth] answered when it looked in both places the garage can live.
 *
 * Separate from [GarageRead] because the questions differ: that one asks "what is in this file",
 * this one asks "is there anything here the app may overwrite, and which file would that be".
 */
sealed class GarageScan {
    /** A garage, and the file it came from — not necessarily the one the app writes to. */
    data class Found(val garage: Garage, val from: File) : GarageScan()

    /** Neither location holds anything. A new install, and writing is free. */
    object Nothing : GarageScan()

    /**
     * A file the app will not write over. [where] is that file, [why] is why it cannot be read:
     * together they are the sentence the vehicle page shows, because the owner has to open that
     * particular file to get their cards back.
     */
    data class Blocked(val where: File, val why: String) : GarageScan()
}
