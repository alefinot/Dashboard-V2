package com.alefinot.dashboardpp.vehicles

/** MACs are compared as bare hex: a retyped or re-cased identity is the same identity. */
private fun macKey(mac: String?): String? =
    mac?.uppercase()?.filter { it.isDigit() || it in 'A'..'F' }?.takeIf { it.isNotEmpty() }

/** The last three bytes of a MAC, as six hex digits — "A4:CF:12:3B:9E:07" gives "3B9E07". */
private fun macTail(mac: String?): String? =
    macKey(mac)?.takeIf { it.length >= 6 }?.takeLast(6)

fun displayName(name: String, v: Vehicle, takenNames: Set<String>): String {
    val typed = name.trim()
    val base = typed.ifBlank {
        "UNTITLED ${macTail(v.mac) ?: v.id.uppercase()}"
    }
    if (base !in takenNames || base == v.name.trim()) return base
    var n = 2
    while ("$base $n" in takenNames) n++
    return "$base $n"
}

fun macConflict(v: Vehicle, others: List<Vehicle>): Vehicle? {
    val key = macKey(v.mac) ?: return null
    return others.firstOrNull { it.id != v.id && macKey(it.mac) == key }
}

fun refusalMessage(clash: Vehicle): String {
    val what = clash.name.trim().ifBlank { "the card ${clash.id}" }
    return "That dashboard is already on a card called $what " +
        "(identity ${clash.mac ?: "unknown"}). Remove it there first."
}

fun mergeTypedFields(stored: Vehicle, typed: Vehicle): Vehicle = stored.copy(
    name = typed.name.trim(),
    type = typed.type,
    plate = typed.plate.trim(),
    maker = typed.maker.trim(),
    model = typed.model.trim(),
    year = typed.year.trim(),
    notes = typed.notes.trim(),
)
