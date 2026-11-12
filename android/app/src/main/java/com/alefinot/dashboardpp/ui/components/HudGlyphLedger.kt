package com.alefinot.dashboardpp.ui.components

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Which mark owns which meaning, in one place.
 *
 * The app grew its icons one screen at a time, so the same verb could be drawn two ways and one
 * drawing could mean four things: seven wordings for destroying something, four for putting
 * something back, and an identical pair of `REFRESH` / `DOWNLOAD` buttons on two halves of the same
 * card with no shared code between them. A set that lives only in the file that draws it is a set
 * that gets re-invented by the next screen, so the vocabulary is written down here and
 * `GlyphLedgerTest` fails the build when a screen draws a mark that is not registered, or registers
 * a meaning a mark already owns without saying why.
 *
 * Two rules the ledger encodes:
 *
 *  - **One id, one mark.** A meaning may not be drawn two ways. If a screen needs the same verb at
 *    two sizes, it asks for the same id at two sizes — that is what [HudGlyphSize] is for.
 *  - **A mark may own several ids only when the meanings are the same act.** [SHARED] records those
 *    and the reason, because each one was a decision and not an accident.
 *
 * Names are lower-case and dotted into an area and a verb: `nav.live`, `file.in`, `action.dismiss`,
 * `value.default`. They are the id a screen reaches for; the mark is the answer, never the thing a
 * caller passes by hand twice.
 */
object HudGlyphLedger {

    /** Meaning → the glyph that owns it. */
    val glyphOf: Map<String, ImageVector> = mapOf(
        // ── Places. The bar, and the same mark on the page it opens. ──
        "nav.live" to HudIcons.Gauge,
        "nav.trips" to HudIcons.Pin,
        "nav.vehicles" to HudIcons.Wheel,
        "nav.unit-settings" to HudIcons.Chip,
        "nav.app-settings" to HudIcons.Cog,

        // ── States of the link. ──
        "link.on" to HudIcons.LinkOn,
        "link.off" to HudIcons.LinkOff,

        // ── Verbs an owner taps. ──
        "action.search" to HudIcons.Search,
        "action.list-open" to HudIcons.Chevron,
        "action.edit" to HudIcons.Edit,
        "action.dismiss" to HudIcons.Close,
        "action.accept" to HudIcons.Check,
        "action.step-up" to HudIcons.Plus,
        "action.step-down" to HudIcons.Minus,
        "action.start" to HudIcons.Play,
        "action.stop" to HudIcons.Stop,
        "action.destroy" to HudIcons.Bin,
        "action.unit-restart" to HudIcons.Power,
        "action.write-to-unit" to HudIcons.Flush,
        "action.file-choose" to HudIcons.File,
        "action.photo-take" to HudIcons.Camera,
        "action.photo-frame" to HudIcons.Frame,
        "action.system-panel" to HudIcons.Cog,

        // ── Files, in both directions. ──
        "file.in" to HudIcons.ImportFile,
        "file.out" to HudIcons.ExportFile,

        // ── Putting something back to where it came from. ──
        "value.default" to HudIcons.Reset,
        "view.reset" to HudIcons.Reset,
        "list.reload" to HudIcons.Reset,
        "trip.reset" to HudIcons.Reset,

        // ── Things the dash reports. A card header's mark: it names the card, it is not pressed. ──
        "readout.speed" to HudIcons.Gauge,
        "readout.trip" to HudIcons.Pin,
        "readout.fuel" to HudIcons.Droplet,
        "readout.gnss" to HudIcons.Signal,
        "readout.power" to HudIcons.Bolt,
        "readout.recording" to HudIcons.Record,
        "readout.stale" to HudIcons.Warn,
        "vehicle.placeholder" to HudIcons.Wheel,
    )

    /**
     * The marks that answer to more than one id, and why that is the same act rather than two
     * meanings wearing one shape.
     *
     * Each of these was chosen over drawing a second mark, and each is a place a future reader will
     * look for a collision — so the reason lives next to the assertion that catches the collision.
     */
    val SHARED: Map<String, String> = mapOf(
        "Cog" to "`app settings` and `open a system screen` are the same idea at different owners: " +
            "a control panel. The sentence on the node says which panel.",
        "Reset" to "All four are `back to where this came from` — a row's stored default, a crop " +
            "before it was moved, a release list asked again, a trip counter zeroed. The circular " +
            "arrow never shares a screen with Power, which is the one restart it must not be " +
            "confused with, and that separation is deliberate (see HudIcons.Power).",
        "Wheel" to "The bar's slot, the placeholder on a card with no photo and `go to the garage` " +
            "all mean this app's subject, so they are one mark, not three.",
        "Gauge" to "The LIVE slot and the speed card are the same reading, and the launcher icon is " +
            "that reading (see HudIcons.Gauge).",
        "Pin" to "The TRIPS slot, a recorded trip and its distance are the same object in three " +
            "places — the map, the list and the card already draw one pin.",
    )

    /** Every glyph name the set draws, for the ledger's own completeness check. */
    val drawnMarks: Set<String> = glyphOf.values.map { it.name.removePrefix("Hud") }.toSet()
}
