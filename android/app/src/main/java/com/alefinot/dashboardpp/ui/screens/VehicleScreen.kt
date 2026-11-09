package com.alefinot.dashboardpp.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudBanner
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudPage
import com.alefinot.dashboardpp.ui.components.HudPageHeader
import com.alefinot.dashboardpp.ui.components.ScanRing
import com.alefinot.dashboardpp.ui.components.hudPage
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.HudePerfLabel
import com.alefinot.dashboardpp.ui.theme.HudePerfValue
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.hudCard
import com.alefinot.dashboardpp.viewmodel.ConnectMode
import com.alefinot.dashboardpp.viewmodel.Found
import com.alefinot.dashboardpp.viewmodel.ConnectionUiState
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import kotlinx.coroutines.delay

/**
 * The vehicle page — the garage, and the app's home.
 *
 * A card here is a **vehicle**, not an address: a thing that exists in the world and that
 * a phone can be near, which is why it can be picked while nothing is answering, and why
 * "which car am I in?" is a question this page can answer with Wi-Fi off. That
 * distinction is the reason the hunt was rewritten (see [ConnectionCore] and
 * [ConnectionViewModel]): an address is not a vehicle, and a list of addresses is not a
 * garage.
 *
 * What this page owns:
 *  - the list, one card per paired vehicle — the selected one first, then the ones that
 *    have connected most recently ([com.alefinot.dashboardpp.vehicles.VehicleStore.sorted]);
 *  - the card tap itself: tapping a card reaches for that vehicle (making it the one the app
 *    connects to and dials it), or ends the session if that card is the connected one. A
 *    vehicle that has never been seen on a network is still tappable; the ladder is what says
 *    why it cannot be reached;
 *  - the honest state of each card: connected, saved, being reached for, or never seen.
 *
 * A card with nothing to show says so with a placeholder instead of a stock picture. When
 * a card is selected and the app is trying to reach it, or has failed to, the card carries
 * the [ConnectionUiState]'s own words **verbatim** — the same sentence the view model
 * gives the covers, because a paraphrased failure is how an app ends up saying
 * "searching" about a network that already answered.
 *
 * Forgetting a vehicle lives in the editor it opens from, not on the card: DELETE asks for
 * the name that is written on the card before it goes — photo, record, saved sessions, and the
 * pairing it held go with it (the phone's own Bluetooth bond is the one half only the phone's
 * settings can clear, so the page says so). Recorded trips stay, badged with the name they were
 * driven under.
 *
 * This page is also the app's **only** place for connect actions — reaching for a vehicle and
 * dropping its session (both now a tap on the card itself), SEARCH and the PAIR rows a sweep
 * produces. The Live and ESP covers deliberately carry none of them: a device belongs to a
 * vehicle, so reaching for one starts on a card, and a second copy of the same control somewhere
 * else is what makes a connection impossible to explain.
 *
 * The sweep list sits here too, on the ADD VEHICLE card rather than on a page of its own.
 * It used to be a hunt that ended wherever it ended; now it is a question this page asks
 * ("which dashboard is out there?") and answers with names, and the row the user taps
 * becomes that vehicle's pairing.
 */
@Composable
fun VehicleScreen(
    connection: ConnectionViewModel,
    onEdit: (String) -> Unit,
    onNameUnclaimed: () -> Unit,
) {
    val context = LocalContext.current
    // Collected once per view model: `vehicleCards()` hands back a cold flow, and calling
    // it in place would rebuild and restart the collection on every recomposition.
    val cards = remember(connection) { connection.vehicleCards() }
    val vehicles by cards.collectAsStateWithLifecycle(initialValue = emptyList())
    // Why this page is empty, when empty is not the truth. A garage file that will not parse is
    // shown as no cards (the app refuses to write over what it cannot read), and the only line
    // about it has to come from the store, because nothing else on the page can tell the two
    // apart. Re-asked when the cards change, so a repaired file stops the warning on its own.
    val garageDamage by produceState<String?>(initialValue = null, vehicles) {
        value = connection.garageDamage()
    }
    val selectedId = connection.selectedVehicleId.value
    val state = connection.uiState.value
    val notice by connection.notice
    val unclaimed by connection.unclaimed
    // Whether the sweep button has to read SEARCH AGAIN instead of SEARCH FOR DASHBOARD++. It
    // is decided here, from the same tested mapping the covers use ([ShellRouting]), rather than
    // handed down by the shell: the rule and the sentence it changes belong on the same page,
    // and a pass-through parameter is one more thing that can drift out of agreement with the
    // state it was computed from.
    val findIsRetrySearch = ShellRouting.view(state).findIsRetrySearch

    // What the last sweep heard, and why it may have heard nothing. Both are read here
    // rather than passed down: the list belongs under the button that started it, and a
    // sweep with no visible answer is indistinguishable from a sweep that found nothing
    // unless the page says which one happened.
    val found = connection.scanResults.value
    val scanWhy = connection.scanError.value
    val radioMissing = connection.adapterOn.value == false
    val bondNote = connection.bondNote.value

    // The clock the derived status lines ("LAST SEEN YESTERDAY") are measured against.
    // Read once per composition and refreshed once a minute while this page is on screen:
    // a card should not have to be revisited to stop saying "today" after midnight, and a
    // per-frame clock would rebuild every card sixty times a second for a sentence that
    // changes once a day.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMs = System.currentTimeMillis()
        }
    }

    Column(
        Modifier
            .hudPage()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HudPage.Gutter)
            .padding(top = 10.dp, bottom = 24.dp),
    ) {
        // `.header`: the page's `h2`, its status pill, then the line about what the page is.
        // Centred, capped, and inside `.main-content` - the column this page never had, which
        // put a 13 sp card title at the far left of a 1920 px screen and its buttons under the
        // right edge of it.
        val selectedName = vehicles.firstOrNull { it.id == selectedId }?.name?.ifBlank { null }
        HudPageHeader(title = "Vehicles") {
            HudBanner(
                buildString {
                    append(if (vehicles.size == 1) "1 vehicle" else "${vehicles.size} vehicles")
                    if (selectedName != null) append("  ·  $selectedName selected")
                },
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(16.dp))

        if (vehicles.isEmpty()) {
            // One line. The card below already explains what its two buttons do, so an
            // empty garage only has to say that nothing has been met yet - a second
            // paragraph about what a search is would be the page talking to itself.
            //
            // Unless it is not empty at all. A file the app will not parse is a different
            // sentence, in a different colour, because the fix is the file and not a search.
            Text(
                garageDamage?.let {
                    "Garage file unreadable ($it) \u2014 not overwriting it."
                }
                    ?: "No dashboards paired yet.",
                color = if (garageDamage == null) HudColors.TextMuted else HudColors.Amber,
                style = HudeCfgNote(),
            )
            Spacer(Modifier.height(14.dp))
        } else {
            for (v in vehicles) {
                VehicleCard(
                    vehicle = v,
                    selected = v.id == selectedId,
                    // Only the selected card can be mid-attempt: the others are
                    // records, not candidates, and saying "connecting" about a card
                    // the app is not reaching for would be a lie. The connected card
                    // is matched separately so it can always offer its own way out.
                    state = if (v.id == selectedId) state else null,
                    connected = connectedCard(v, state),
                    nowMs = nowMs,
                    // There is no sweep percentage to show: a BLE scan reports units,
                    // not progress, so the ring spins (P3 replaces the dial with the
                    // pair/search split, which has no ring at all).
                    ringProgress = 0f,
                    photo = cardPhoto(connection, v.id),
                    onConnect = { connection.connectVehicle(v.id, ConnectMode.SEARCH) },
                    onDisconnect = { connection.disconnect() },
                    onEdit = { onEdit(v.id) },
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        // Two sentences that are not this page's to write, and that no card can
        // represent: a unit answered that no card claims, or the unit that answered
        // belongs to a different card than the one selected. Both are published by the
        // view model and would otherwise be lost, and either one means a card on this
        // list is about to be described wrongly.
        notice?.let {
            NoticeCard("THE APP AND THE CARD DISAGREE", it, HudColors.Amber)
            Spacer(Modifier.height(10.dp))
        }
        unclaimed?.let {
            // The unit is named the way it advertises (`DPP-<eFuse>`), not by the
            // Bluetooth address: an address is this phone's handle for a link, it is
            // printed nowhere on the unit, and asking someone to name a car after it
            // would be asking them to adopt a serial number. The MAC is the identity,
            // and it is shown because that is the string worth checking.
            NoticeCard(
                "A DASHBOARD ANSWERED THAT NO CARD CLAIMS",
                "${it.name} (firmware ${it.version ?: "unknown"}, " +
                    "MAC ${it.mac.uppercase()})",
                HudColors.Cyan,
                actionLabel = "NAME IT",
                onAction = onNameUnclaimed,
            )
            Spacer(Modifier.height(10.dp))
        }

        bondNote?.let {
            // The half this app could not do. `removeBond` is not a public API, so the honest
            // sentence is "the delete dropped the card's side, this phone has not" plus the way
            // to the screen where it can be finished.
            NoticeCard(
                "THIS PHONE IS STILL BONDED TO THAT UNIT",
                it,
                HudColors.Amber,
                actionLabel = "PHONE BLUETOOTH SETTINGS",
                onAction = { openPhoneBluetoothSettings(context) },
            )
            Spacer(Modifier.height(10.dp))
        }

        AddVehicleCard(
            onSearch = { connection.rescan() },
            found = found,
            scanWhy = scanWhy,
            radioMissing = radioMissing,
            retry = findIsRetrySearch,
            onPair = { connection.pairWith(it) },
        )
    }
}

/**
 * A card's photo, decoded at the size the cover is drawn and no larger, and recomposed when
 * [ConnectionViewModel.photoVersion] says the picture on disk changed under us.
 *
 * Twice the cover's edge rather than once: the cover is a 76 dp square, which is 113 px on this
 * tablet's density but 228 px on a modern phone, and a bitmap decoded at the smaller number
 * goes soft the moment the app runs on hardware denser than the one the layout was sketched
 * on. [VehiclePhotoStore.read] takes the request as a ceiling, not a promise - it never
 * decodes larger than the JPEG it kept (1280 px at most, usually much less), so asking for
 * twice the box costs nothing on the cards that have no photo to speak of.
 *
 * The one sharp edge is a slow read answering late: the request runs on the VM's scope, so a
 * stale answer can land after a fresh one and paint the previous picture for a moment. It is
 * self-correcting (the next recomposition keyed on [ConnectionViewModel.photoVersion] asks
 * again) and it is a card cover, not a diagnosis, so the race is left as it is rather than
 * bought with a request serial number on every card on the page.
 */
@Composable
private fun cardPhoto(connection: ConnectionViewModel, vehicleId: String): Bitmap? {
    val edgePx = with(LocalDensity.current) { 76.dp.toPx().toInt() * 2 }
    val version = connection.photoVersion.intValue
    val photo by produceState<Bitmap?>(initialValue = null, vehicleId, version) {
        connection.photo(vehicleId, edgePx) { value = it }
    }
    return photo
}

/**
 * The cover panel: photo, scan ring, or wheel glyph - one of the three, in that order.
 *
 * The panel is square because the picture is: the editor frames a stored photo 1:1, so a
 * square cover shows the whole framed square edge to edge instead of a cropped band of it.
 * [ContentScale.Crop] stays only as the guard for a bitmap that is not exactly square.
 *
 * The photo wins over the scan ring, which is a change from the placeholder-only cover: a
 * card being hunted for still says so in its status line and in the footer's phase line, and
 * the ring in the cover was never the only notice. Repeating it over a user's own photograph
 * would be the app asking to be looked at instead of looking at the car.
 */
@Composable
private fun CardCover(
    photo: Bitmap?,
    selected: Boolean,
    scanning: Boolean,
    ringProgress: Float,
) {
    Box(
        Modifier
            .size(76.dp)
            .clip(HudRadius.CardShape)
            .background(HudColors.Panel)
            .border(1.dp, HudColors.Hairline, HudRadius.CardShape),
        contentAlignment = Alignment.Center,
    ) {
        photo?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "photo of this vehicle",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(76.dp),
            )
        }
        if (photo == null) {
            if (scanning) {
                ScanRing(ringProgress, Modifier.size(28.dp))
            } else {
                Icon(
                    HudIcons.Wheel,
                    contentDescription = null,
                    tint = if (selected) HudColors.CyanDim else HudColors.TextDim,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

/**
 * One vehicle card, drawn with the page's `.card` skin like the trip rows: same fill,
 * same hairline, same corners, so a list of vehicles and a list of drives read as the
 * same kind of thing on the same page.
 *
 * What a card *is* decides how it behaves, and the card is now itself the connect control: tapping
 * its body reaches that vehicle, or ends the session it owns if it has one (the connected card can
 * be disconnected even after the selection has moved on, because it is the card with a session to
 * end). The two things that could not live inside that one tap are corner glyphs — an EDIT pencil
 * upper-right, and a link mark lower-right that reads joined-green when the card holds the live
 * session and broken-dim otherwise. A card is a record about a vehicle, so every card can be
 * edited and tapped, whether or not it is the one being reached for; there is no longer a wall of
 * connection buttons to press on each card.
 *
 * The outline carries the same fact at a glance: green on the card that holds the link, cyan on the
 * one the app is reaching for, a plain hairline on the rest. The selection is still an edge and a
 * faint top highlight — a standing fact about the app rather than a status that comes and goes —
 * but on the connected card green wins, because a live link outranks which card is picked.
 *
 * The cover shows the vehicle's photo when the card has one, and until then it is a placeholder
 * panel drawn with the bar's own wheel glyph so the shape means "vehicle" in both places — drawn dim
 * on purpose: an empty frame that shows the glyph is honest about being empty, one that shows a
 * stock photo is not. The cover is square, so the whole 1:1 picture the editor framed shows with
 * nothing cropped away (see [CardCover]). While the app is hunting for **this** card's unit the
 * cover becomes the sweep's ring instead: that is where a user is
 * looking when they reach for a vehicle, and the full-screen ring that used to answer the question
 * is gone.
 *
 * Every line of text — the name, the spec, the status and the TAP TO hint — lives in the one text
 * column beside the cover, so they share a left edge; a hint that started under the cover would
 * read as the card's caption, not as the next line of the vehicle.
 *
 * Deleting a vehicle is not on the card - it is the editor's DELETE THE CARD - and it now does the
 * whole job: it removes the card *and* drops the pairing and identity it held, so there is no longer
 * a separate FORGET to clear a pairing while the card stays. "This phone should stop reaching that
 * dashboard" and "this vehicle does not exist" are one action now.
 */
@Composable
private fun VehicleCard(
    vehicle: Vehicle,
    selected: Boolean,
    state: ConnectionUiState?,
    connected: Boolean,
    nowMs: Long,
    ringProgress: Float,
    photo: Bitmap?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
) {
    val status = cardStatusLine(vehicle, state, nowMs)
    // The card *is* the connect control now: tapping its body reaches this vehicle, or ends the
    // session it owns. It used to be a row of buttons under the cover (CONNECT / DISCONNECT /
    // EDIT), and the maintainer folded that decision into the card itself on 2026-11-08 - a card is
    // one act, "get in this car", not three. What could not live inside that single tap went to the
    // corners: an EDIT pencil upper-right, and a link mark lower-right that says whether this is
    // the card with a live session. The outline is green on the card that holds the link, cyan on
    // the one the app is reaching for, a plain hairline otherwise.
    Box(
        Modifier
            .fillMaxWidth()
            .hudCard(
                borderColor = when {
                    connected -> HudColors.Green
                    selected -> HudColors.Cyan
                    else -> HudColors.CardHairline
                },
                highlight = if (selected) 0.05f else 0f,
            )
            .clickable(
                onClickLabel = (if (connected) "Disconnect " else "Connect ") +
                    vehicle.name.ifBlank { "vehicle" },
            ) { if (connected) onDisconnect() else onConnect() }
            .padding(12.dp),
    ) {
        // The body is inset 30 dp from the right so its text never runs under either corner glyph;
        // the cover and every line still read edge to edge across the rest of the card.
        Column(Modifier.padding(end = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardCover(photo, selected, showScanRing(state), ringProgress)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        vehicle.name.ifBlank { "UNNAMED VEHICLE" },
                        color = when {
                            connected -> HudColors.Green
                            selected -> HudColors.Cyan
                            else -> HudColors.TextPrimary
                        },
                        style = HudeHeading().copy(fontSize = 17.sp),
                    )
                    // What the user called this vehicle, in their own words: the plate is the
                    // thing they look for in a car park, and the spec line is the difference
                    // between two cards with similar names. A blank field draws no line at all
                    // (see [plateLine]) - a card with empty gaps reads like a form.
                    plateLine(vehicle)?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = HudColors.TextDim, style = HudePerfLabel())
                    }
                    specLine(vehicle).takeIf { it.isNotEmpty() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, color = HudColors.TextMuted, style = HudeCfgNote())
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        status.text,
                        color = statusColor(status.tone),
                        style = HudePerfLabel(),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The unit's identity is reference, not headline: it is what the app knows it
                    // reached, and it is the string to check the card against at the bench. A card
                    // that leads with a radio handle is a card that is still thinking of itself as a
                    // bookmark - the MAC is shown because it is burnt into the dashboard, and the
                    // pairing is only ever a label next to it.
                    if (!connected) {
                        vehicle.identityLabel().takeIf { it.isNotEmpty() }?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, color = HudColors.TextDim, style = HudePerfValue())
                        }
                    }
                    // A line that says what tapping does, because the buttons that used to spell
                    // it out are gone: the card is the control now, and a control whose meaning
                    // lives only in the code is a control nobody learns. It lives in the text
                    // column rather than under the cover, so it starts on the name's left edge.
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (connected) "TAP TO DISCONNECT" else "TAP TO CONNECT",
                        color = if (connected) HudColors.Green else HudColors.Cyan,
                        style = HudeCfgNote(),
                    )
                }
            }
        }

        // Upper-right EDIT, a corner icon rather than the last button in a row that no longer
        // exists. It sits inside the card's Box and after its clickable, so it owns its own corner:
        // reaching for the pencil opens the editor and does not also reach for the radio. 30 dp of
        // hit box around an 18 dp mark - the smallest box that stays a target on a windscreen.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(30.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Edit ${vehicle.name}") { onEdit() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                HudIcons.Edit,
                contentDescription = null,
                tint = HudColors.TextDim,
                modifier = Modifier.size(18.dp),
            )
        }

        // Lower-right, the link state: two joined rings, green on the card that holds the session,
        // a broken join dim on every other. It is smaller than the pencil and takes no tap - the
        // tap is the whole card - because it reports the state, it does not change it.
        Icon(
            imageVector = if (connected) HudIcons.LinkOn else HudIcons.LinkOff,
            contentDescription = if (connected) "Connected" else "Disconnected",
            tint = if (connected) HudColors.Green else HudColors.TextDim,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(16.dp),
        )
    }
}

/**
 * One dashboard the radio heard, by the name it advertises and the signal it arrived on.
 *
 * The address is deliberately absent from the row: it is the handle this phone would
 * invent for the link, not something printed on the unit, and a list of addresses is how
 * a sweep stops being answerable ("is that my car or the neighbour's?"). RSSI is kept
 * because it is the one thing that does answer that - the dashboard in the boot room is
 * the loud one.
 */
@Composable
private fun SweepRow(unit: Found, onPair: (Found) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .hudCard()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(unit.name, color = HudColors.TextPrimary, style = HudePerfLabel())
            Spacer(Modifier.height(2.dp))
            Text("signal ${unit.rssi} dBm", color = HudColors.TextDim, style = HudeCfgNote())
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.heightIn(min = 44.dp)) {
            HudButton("PAIR", style = HudButtonStyle.PRIMARY) { onPair(unit) }
        }
    }
}

/**
 * Hand off to the phone's own Bluetooth screen. Some devices route that action to a
 * vendor screen that is not there, so the fall back is the settings home rather than a
 * crash: the note above the button is the information, the button is a convenience.
 */
private fun openPhoneBluetoothSettings(context: android.content.Context) {
    val intents = listOf(
        Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            continue
        }
    }
}

/**
 * A fact the connection published that no card can carry: either the unit that answered
 * belongs to another card, or it belongs to none, or this phone is still bonded to a unit
 * the card just stopped trusting. All three are drawn here because the page has to
 * contradict its own cards out loud rather than let a wrong card stand.
 */
@Composable
private fun NoticeCard(
    title: String,
    text: String,
    accent: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudCard(borderColor = accent.copy(alpha = 0.35f))
            .padding(14.dp),
    ) {
        Text(title, color = accent, style = HudeSectionTitle())
        Spacer(Modifier.height(8.dp))
        Text(text, color = HudColors.TextMuted, style = HudeCfgNote())
        if (actionLabel != null && onAction != null) {
            // The offer is only worth making if it can be accepted where it is made: a
            // notice the user has to go hunting for a control on to act on is a notice they
            // will dismiss instead.
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.heightIn(min = 44.dp)) {
                    HudButton(actionLabel, style = HudButtonStyle.PRIMARY) { onAction() }
                }
            }
        }
    }
}

/**
 * The last thing in the garage, and the only way a new vehicle gets in: [plan §5] puts
 * every entry point on this page because it is the page a user is already standing on.
 * SEARCH is the button: it dials what the selected card remembers and then sweeps the
 * radio, which is everything the app can do about a dashboard it has not met.
 *
 * What the sweep heard is listed under the button, as named rows the user can pick. That
 * list is the reason the box next to it is gone rather than renamed: a Bluetooth address
 * is not printed anywhere on a Dashboard++, typing one cannot pair with anything, and the
 * unit's own name is on its screen and in this list - so the way in is a sweep and a tap,
 * and the pairing happens here [plan §4].
 *
 * A tap is not decoration: it pins that unit to the front of the ladder ([Found] goes to
 * [ConnectionViewModel.pairWith]), which is what makes two dashboards in range, or a
 * neighbour's, an actual choice instead of a race between signal strengths.
 */
@Composable
private fun AddVehicleCard(
    onSearch: () -> Unit,
    found: List<Found>,
    scanWhy: String?,
    radioMissing: Boolean,
    retry: Boolean,
    onPair: (Found) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .hudCard()
            .padding(14.dp),
    ) {
        Text("ADD VEHICLE", color = HudColors.TextDim, style = HudeSectionTitle())
        Spacer(Modifier.height(10.dp))
        HudButton(
            if (retry) "SEARCH AGAIN" else "SEARCH FOR DASHBOARD++",
            modifier = Modifier.fillMaxWidth(),
            style = HudButtonStyle.PRIMARY,
        ) { onSearch() }
        Spacer(Modifier.height(10.dp))

        // A sweep that could not run, or found nothing, has to say which. Silence here is
        // what made the old network hunt untrustworthy: an empty list and a blocked radio
        // looked the same, so the user pressed the button again.
        if (found.isEmpty()) {
            scanWhy?.let {
                Text(it, color = HudColors.Amber, style = HudeCfgNote())
                Spacer(Modifier.height(10.dp))
            }
            if (radioMissing) {
                Text(
                    "Bluetooth is off on this phone. Turn it on, then search.",
                    color = HudColors.Amber,
                    style = HudeCfgNote(),
                )
                Spacer(Modifier.height(10.dp))
            }
        } else {
            Text("IN RANGE", color = HudColors.TextDim, style = HudeSectionTitle())
            Spacer(Modifier.height(8.dp))
            for (unit in found) {
                SweepRow(unit, onPair)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
