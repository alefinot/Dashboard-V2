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
