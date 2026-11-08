package com.alefinot.dashboardpp.vehicles

import com.alefinot.dashboardpp.ble.BleJson
import java.io.File

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

    fun read(file: File): GarageRead = try {
        if (!file.isFile) GarageRead.Absent else decode(file.readText())
    } catch (e: Exception) {
        GarageRead.Damaged("the file could not be read (${e.javaClass.simpleName})")
    }

    fun writeAtomically(file: File, text: String): Boolean {
        val dir = file.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val tmp = File(dir, file.name + TMP_SUFFIX)
        var landed = false
        try {
            tmp.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)) }
            landed = tmp.renameTo(file)
        } catch (e: Exception) {
        } finally {
            if (!landed) tmp.delete()
        }
        return landed
    }

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

data class Garage(
    val nextSeq: Int = 0,
    val selectedId: String? = null,
    val vehicles: List<Vehicle> = emptyList(),
)

sealed class GarageRead {
    /** No file where the garage lives. */
    object Absent : GarageRead()

    /** Read in full. The garage may legitimately be empty. */
    data class Ok(val garage: Garage) : GarageRead()

    /** Something is there and it is not safe to overwrite it. */
    data class Damaged(val why: String) : GarageRead()
}
