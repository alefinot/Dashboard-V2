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
        "nav.vehicles" to HudIcons.Garage,
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
        // The fuel calibration table's CAPTURE: accept the sender reading the unit is making
        // this second, and store it as a point. Same act as [action.accept] — take the thing in
        // front of you — with the thing being live rather than typed, hence one mark.
        "action.live-capture" to HudIcons.Check,
        "action.step-up" to HudIcons.Plus,
        "action.step-down" to HudIcons.Minus,
        "action.start" to HudIcons.Play,
        "action.pause" to HudIcons.Pause,
        "action.stop" to HudIcons.Stop,
        "action.destroy" to HudIcons.Bin,
        "action.unit-restart" to HudIcons.Power,
        "action.write-to-unit" to HudIcons.Flush,
        // The fuel table's LINEAR FILL: rebuild the calibration array as a straight ramp
        // between the tank's two end ohms. A tank mark, not the generic write mark — the
        // maintainer asked for the card to say fuel, and at a glance a down-arrow into a
        // tray says "download" one screen too many. (2026-11-20)
        "action.linear-fill" to HudIcons.FuelTank,
        "action.file-choose" to HudIcons.File,
        "action.photo-take" to HudIcons.Camera,
        "action.photo-frame" to HudIcons.Frame,
        "action.system-panel" to HudIcons.Cog,
        // The Live page's rail over the map: show the readings, or take them off the map.
        // One id, one mark, for what is one act seen from its two sides - which way the press
        // goes is said by the ink (cyan up, dim down) and by the sentence on the node, exactly
        // as the pill's tint says what the recorder is doing. A slashed twin would be a line
        // crossing a line at 22 dp, which is why it was not drawn (see HudIcons.Eye).
        "action.hud-visibility" to HudIcons.Eye,
        // The Live page's rail, under the zoom steps: one press lays the next ground under the
        // map (2026-11-22). The mark names the noun the press swaps — which sheet of the world
        // this drive is drawn on — and the sentence on the node carries the verb, so the mark is
        // not asked to say "cycle", which is the same division of labour as `action.step-up`.
        "action.map-ground" to HudIcons.Layers,

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
        "readout.paused" to HudIcons.Pause,
        "readout.stale" to HudIcons.Warn,
        // The vehicle card's empty cover and the editor's empty well. Same mark as the bar's
        // `nav.vehicles`, and `SHARED["Garage"]` is the reason that is not a collision.
        "vehicle.placeholder" to HudIcons.Garage,
    )

    /**
     * The marks that answer to more than one id, and why that is the same act rather than two
     * meanings wearing one shape.
     *
     * Each of these was chosen over drawing a second mark, and each is a place a future reader will
     * look for a collision — so the reason lives next to the assertion that catches the collision.
     */
    val SHARED: Map<String, String> = mapOf(
        "Check" to "`accept what is in front of you` is one act whether the thing in front of you " +
            "was typed (a crop, a city name, a dropdown row) or measured — the fuel table's CAPTURE " +
            "accepts the sender reading the unit is making at the instant of the press. The word on " +
            "the button is what says which of the two a given press accepts.",
        "Cog" to "`app settings` and `open a system screen` are the same idea at different owners: " +
            "a control panel. The sentence on the node says which panel.",
        "Reset" to "All four are `back to where this came from` — a row's stored default, a crop " +
            "before it was moved, a release list asked again, a trip counter zeroed. The circular " +
            "arrow never shares a screen with Power, which is the one restart it must not be " +
            "confused with, and that separation is deliberate (see HudIcons.Power).",
        "Gauge" to "The LIVE slot and the speed card are the same reading, and the launcher icon is " +
            "that reading (see HudIcons.Gauge).",
        "Pin" to "The TRIPS slot, a recorded trip and its distance are the same object in three " +
            "places — the map, the list and the card already draw one pin.",
        "Garage" to "`the garage tab` and `a vehicle with no photo yet` are the same noun: this " +
            "app's subject. The bar's centre slot, a card's empty cover, the editor's empty well and " +
            "`OPEN VEHICLE PAGE` therefore draw one garage front rather than a tab mark and a " +
            "placeholder mark that happen to look alike.",
        "Pause" to "The PAUSED pill and the PAUSE action are one act in two voices: the pill reports " +
            "the state the control puts the drive in, exactly as `readout.recording` reports what " +
            "the recorder is doing while `action.start` is what begins it.",
    )

    /**
     * The mark that owns a meaning, for a screen that knows the meaning and not the drawing.
     * A typo'd id throws rather than drawing nothing: a screen asking for a meaning the ledger
     * never wrote down is a bug, and the test bench is where it should surface.
     */
    fun mark(id: String): ImageVector = glyphOf.getValue(id)

    /** Every glyph name the set draws, for the ledger's own completeness check. */
    val drawnMarks: Set<String> = glyphOf.values.map { it.name.removePrefix("Hud") }.toSet()
}
