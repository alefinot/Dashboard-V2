package com.alefinot.dashboardpp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudBanner
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudConfirmDialog
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudInputStyle
import com.alefinot.dashboardpp.ui.components.HudOverlayScrim
import com.alefinot.dashboardpp.ui.components.HudSelectMenu
import com.alefinot.dashboardpp.ui.theme.HudBrushes
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgLive
import com.alefinot.dashboardpp.ui.theme.HudeCfgNote
import com.alefinot.dashboardpp.ui.theme.HudeFormLabel
import com.alefinot.dashboardpp.ui.theme.HudeHeading
import com.alefinot.dashboardpp.ui.theme.hudCard
import com.alefinot.dashboardpp.ui.theme.hudGlass
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.vehicles.PhotoEdit
import com.alefinot.dashboardpp.vehicles.VehiclePhotoStore
import com.alefinot.dashboardpp.vehicles.VehicleType
import com.alefinot.dashboardpp.vehicles.vehicleTypeLabel
import java.io.File

/**
 * One card's details, editable — a panel over the garage, not a route.
 *
 * It is a panel for the same reason the page's delete confirmation is a box inside the card
 * rather than a window: the page behind
 * is the point, and closing this returns the user to their garage with the bar, the pager
 * and their place in it exactly as they left it. A route could only ever drop them back on
 * some other page.
 *
 * The form edits **the user's half** of the record — name, type, plate, maker, model, year,
 * notes. What the app measured is shown and not editable, because it is not the user's to
 * type: the eFuse identity is what binds the card to the physical dashboard behind them, and
 * the remembered addresses are what a return trip is dialled from. Showing them is the
 * honest part: a card that has no identity says so, and that explains why it cannot be
 * recognised automatically.
 *
 * The name field's placeholder is not a decorative example. Leaving the name blank is
 * allowed on purpose (the save fills in the identity tail rather than losing the edit on the
 * back gesture), so the placeholder tells the user what the card will be called if they do —
 * [NAME IT] on an unclaimed unit lands here with the name empty, where that matters most.
 *
 * Deleting asks first, through the app's own confirm dialog, because the consequence is not
 * the card: the drives this vehicle took outlast it by design.
 *
 * The photo well at the top of the form is the third place a picture is asked for - after the
 * garage and before anything else a new card is worth. Both routes end in the same crop step
 * ([PhotoCropPanel]) rather than writing the picture straight in, because the card's cover is
 * a square and a photograph is not, and an automatic crop cuts the plate off one car and the
 * handlebars off another.
 *
 * **Nothing about the photo reaches disk until SAVE** (2026-11-10, the fix for "I pressed
 * CANCEL and the picture changed anyway"). The editor stages a [PhotoEdit] - a pick, a
 * re-frame, or a removal - exactly the way it stages the typed fields, and CANCEL or Back
 * drops it together with them. USE THIS PHOTO only decodes a small preview for the well;
 * [ConnectionViewModel.saveVehicleWithPhoto] crops the real picture from the still-untouched
 * source at SAVE, in the same write that lands the record - which is also why a brand-new
 * card can finally get a photo: there is a record by the time the JPEG needs one. The gallery
 * pick and the camera shot both sit in a scratch capture the editor throws away when it
 * closes, so closing mid-way cannot leave half a photo on a card either.
 *
 * RE-FRAME PHOTO walks a picture through that same crop step - the pending draft's own
 * source if there is one, else the card's **stored original** (`<id>.src.jpg`, kept since
 * 2026-11-10 so an old crop can be widened again instead of zooming inside itself; cards
 * from before then re-frame the square they have). Because everything is draft-scoped, a
 * cancel of any of it - pick, re-frame, or remove - costs nothing but the scratch file.
 */
@Composable
fun VehicleEditorScreen(
    vm: ConnectionViewModel,
    draft: Vehicle,
    isNew: Boolean,
    refusal: String?,
    onSave: (Vehicle, PhotoEdit) -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    // Cancelling the confirmation is a separate Back step from closing the form: the first
    // press should un-arm the destructive one, not throw away the edits beside it.
    var confirming by remember(draft.id) { mutableStateOf(false) }
    BackHandler(enabled = confirming) { confirming = false }
    BackHandler(enabled = !confirming) { onDismiss() }

    var name by remember(draft.id) { mutableStateOf(draft.name) }
    var type by remember(draft.id) { mutableStateOf(draft.type) }
    var customType by remember(draft.id) { mutableStateOf(draft.customType) }
    var plate by remember(draft.id) { mutableStateOf(draft.plate) }
    var maker by remember(draft.id) { mutableStateOf(draft.maker) }
    var model by remember(draft.id) { mutableStateOf(draft.model) }
    var year by remember(draft.id) { mutableStateOf(draft.year) }
    var notes by remember(draft.id) { mutableStateOf(draft.notes) }

    // Photo state, all of it keyed to this card and all of it **draft-scoped** until
    // SAVE. [photoEdit] is the staged decision - Keep / Remove / Crop - the same kind of
    // thing the text fields above are, and the only thing SAVE forwards to the view
    // model. [cropFrom] is a staged scratch capture the user is framing in the panel;
    // [reframeFrom] is an already-existing file (the card's stored original, or a draft
    // crop's own source) opened through the same crop step. [pendingCapture] is the file
    // handed to the camera, kept between the intent and its result because the result
    // callback gets a boolean, not the file.
    // [well] is the *stored* picture, refreshed by the VM's photo version; the well
    // shows the draft crop's preview over it while a decision is pending, so nothing
    // this screen draws can claim a photo that has not been saved.
    var cropFrom by remember(draft.id) { mutableStateOf<File?>(null) }
    var reframeFrom by remember(draft.id) { mutableStateOf<File?>(null) }
    var pendingCapture by remember(draft.id) { mutableStateOf<VehiclePhotoStore.Capture?>(null) }
    var photoEdit by remember(draft.id) { mutableStateOf<PhotoEdit>(PhotoEdit.Keep) }
    var well by remember(draft.id) { mutableStateOf<Bitmap?>(null) }
    var photoNote by remember(draft.id) { mutableStateOf<String?>(null) }

    val wellEdgePx = with(LocalDensity.current) { WELL_EDGE.dp.toPx().toInt() }
    LaunchedEffect(draft.id, vm.photoVersion.intValue) {
        // Asked for at twice the box: the well is drawn from a bitmap that is at least as
        // large as it is, and the store caps the decode at the edge the photo was kept at
        // anyway (rule 14 - a page of cards asks for card-sized pixels, and so does this).
        vm.photo(draft.id, wellEdgePx * 2) { well = it }
    }

    // Whatever scratch this card still has goes when the editor closes: a saved crop's
    // copy is already deleted by the store's commit, a landed picture never lives here,
    // and a picture the user walked away from has no business sitting in the cache.
    // clearCapture only names `cacheDir/captures/<id>.jpg`, so this can never touch a
    // stored photo, not even one this editor staged and then dropped.
    DisposableEffect(draft.id) {
        onDispose { vm.cancelPhoto(draft.id) }
    }

    /**
     * A fresh pick replaces a pending draft framing outright. The reason is the one
     * scratch file per card: [VehiclePhotoStore.stageFrom] and the camera both write
     * `captures/<id>.jpg`, which is exactly where an un-saved draft crop's source lives,
     * so the new picture would be cropped with the old framing's rectangle - the kind of
     * wrong photo nobody can explain afterwards. Dropping the draft when staging starts
     * makes the well show the old photo honestly, until the new framing lands.
     */
    fun dropPendingDraft() {
        if (photoEdit is PhotoEdit.Crop) photoEdit = PhotoEdit.Keep
    }

    val pickFromGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            photoNote = null
            dropPendingDraft()
            vm.stagePickedPhoto(uri, draft.id) { staged ->
                if (staged == null) {
                    photoNote = "Unreadable picture \u2014 card keeps its photo."
                } else {
                    cropFrom = staged
                }
            }
        }
    }

    val shootPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { taken ->
        val capture = pendingCapture
        pendingCapture = null
        when {
            taken && capture != null -> {
                photoNote = null
                cropFrom = capture.file
            }
            // A cancelled shot is not an error, but the scratch file may hold the start of a
            // write, so it goes rather than being mistaken for a capture on the next attempt.
            capture != null -> {
                vm.cancelPhoto(draft.id)
                photoNote = null
            }
            else -> photoNote = "Camera returned nothing."
        }
    }

    /**
     * Ask the VM for a scratch capture, then point the camera at it. The order matters and is
     * the plan's: a device with nothing to offer answers null, and the editor says the camera
     * is not available rather than launching an intent whose photograph would land nowhere.
     */
    fun askForCamera() {
        vm.cameraCapture(draft.id) { capture ->
            if (capture == null) {
                photoNote = "No camera available."
            } else {
                photoNote = null
                // The camera is about to write the same scratch file a draft crop reads.
                dropPendingDraft()
                pendingCapture = capture
                shootPhoto.launch(capture.uri)
            }
        }
    }

    val identityTail = draft.mac?.takeLast(6)?.uppercase() ?: draft.id.uppercase()
    val shownType = vehicleTypeLabel(type, customType)

    // What the well and its buttons describe: the staged decision first, the stored
    // photo only while nothing is pending. This is why CANCEL can be true - the screen
    // never draws a photo the disk does not have, and never forgets one it does.
    val edit = photoEdit
    val hasPhoto = when (edit) {
        PhotoEdit.Remove -> false
        is PhotoEdit.Crop -> true
        PhotoEdit.Keep -> draft.photoFile != null
    }
    val wellPreview = when (edit) {
        is PhotoEdit.Crop -> edit.preview ?: well // a failed preview decode costs a redraw, not the crop
        PhotoEdit.Remove -> null
        PhotoEdit.Keep -> well
    }

    HudOverlayScrim {
        HudEnter(
            Modifier.fillMaxWidth().widthIn(max = 520.dp)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            durationMs = HudMotion.SHORT_MS,
            riseDp = 8,
            scaleFrom = 0.98f,
        ) {
            // The sheet is the page's own black, not the overlay glass: this form is tall,
            // and a translucent sheet that fills the phone lets the garage behind it read
            // through every field. Cards on black are what the rest of the app looks like.
            //
            // `safeDrawingPadding` on the wrapper keeps the sheet inside the system bars and
            // above the keyboard: this overlay is its own surface, so it cannot rely on
            // DashboardRoot's padding the way a page does. It carries the IME on API 30+
            // (where edge-to-edge leaves the keyboard to the app), while API 29 and below
            // resize the window through the manifest's `adjustResize`; one inset, both
            // halves, and it stays outside the scroll so the footer never moves with the
            // cards.
            Column(
                Modifier
                    .hudGlass(fill = HudColors.Bg, elevation = 16.dp, highlight = 0f),
            ) {
                EditorHeader(
                    title = if (isNew) "NAME THIS DASHBOARD" else "EDIT VEHICLE",
                    name = name,
                    typeLabel = shownType,
                    identityTail = identityTail,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.Hairline))

                // Only the cards scroll; the title above and SAVE/CANCEL below stay put, so
                // a long form or an open keyboard cannot hide the two ways out.
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PhotoCard(
                        hasPhoto = hasPhoto,
                        preview = wellPreview,
                        note = photoNote,
                        onGallery = {
                            pickFromGallery.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onCamera = { askForCamera() },
                        onReframe = {
                            // A pending draft crop re-frames its own source, so the user can
                            // adjust the framing before SAVE; anything else opens what is on
                            // disk, and `storedPhotoFile` answers the card's stored *original*
                            // when it has one. Neither door writes: the panel only frames, and
                            // USE THIS PHOTO only stages.
                            photoNote = null
                            val pending = photoEdit as? PhotoEdit.Crop
                            if (pending != null) {
                                reframeFrom = pending.source
                            } else {
                                vm.storedPhotoFile(draft.id) { f ->
                                    if (f == null) {
                                        photoNote = "This card's photo file could not be opened."
                                    } else {
                                        reframeFrom = f
                                    }
                                }
                            }
                        },
                        onRemove = {
                            // Draft-scoped since 2026-11-10: the well shows the wheel glyph and
                            // the files stay exactly where they are until SAVE says otherwise.
                            photoEdit = PhotoEdit.Remove
                            photoNote = null
                        },
                    )

                    EditorCard("VEHICLE") {
                        Field(
                            "NAME",
                            name,
                            onValueChange = { name = it },
                            placeholder = "UNTITLED $identityTail",
                        )

                        // A dropdown now that the menu is longer than a row: twelve choices
                        // would be one-word buttons stacked down the page. The trigger is drawn
                        // like the app's text fields and opens the same popup furniture the
                        // firmware release picker uses. `OTHER` reveals the free-text box under
                        // it; the custom word lives in the same draft as every other field, so
                        // CANCEL and Back discard it the same way.
                        Column(Modifier.fillMaxWidth()) {
                            Text("TYPE", color = HudColors.TextMuted, style = HudeFormLabel())
                            Spacer(Modifier.height(6.dp))
                            TypeDropdown(
                                selected = type,
                                selectedLabel = shownType,
                                onSelect = { type = it },
                            )
                            if (type == VehicleType.OTHER) {
                                Spacer(Modifier.height(8.dp))
                                Field(
                                    "CUSTOM TYPE",
                                    customType,
                                    onValueChange = { customType = it },
                                    placeholder = "your own vehicle type",
                                )
                            }
                        }

                        Field("PLATE", plate, onValueChange = { plate = it }, placeholder = "plate, if the vehicle has one")
                        Field("MAKER", maker, onValueChange = { maker = it }, placeholder = "Toyota")
                        Field("MODEL", model, onValueChange = { model = it }, placeholder = "Corolla")
                        Field(
                            "YEAR", year,
                            onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) },
                            placeholder = "1998",
                            keyboardType = KeyboardType.Number,
                        )
                        Field("NOTES", notes, onValueChange = { notes = it }, placeholder = "anything the card should remember")
                    }

                    // The evidence half, read-only. A blank identity is stated rather than left
                    // out: it is the reason this card cannot be recognised when it answers, and
                    // hiding that would leave the user guessing why the other card exists.
                    // The two lines are not interchangeable. The MAC is the dashboard's own claim
                    // about itself and survives anything; the paired address is this phone's link to
                    // that radio, and it changes if the pairing is redone — which is why it is
                    // labelled as a pairing and never offered as an identity.
                    EditorCard("DASHBOARD") {
                        Text(
                            "Read-only — what the app measured.",
                            color = HudColors.TextDim,
                            style = HudeCfgNote(),
                        )
                        Fact(
                            "IDENTITY (FROM THE DASHBOARD)",
                            draft.mac ?: "not reported by this dashboard",
                        )
                        Fact(
                            "PAIRED BY THIS PHONE",
                            draft.bleAddress ?: "no pairing yet — search for it from the vehicle page",
                        )
                        Fact("FIRMWARE LAST SEEN", draft.firmwareVersion ?: "unknown")
                    }

                    if (!isNew) {
                        // Its own card under a red heading: what lives here is not part of the
                        // form, and deleting is the one button here the user should never
                        // brush past on the way to SAVE.
                        EditorCard("DELETE", titleColor = HudColors.Red) {
                            Text(
                                "Removes the card and drops its pairing. Its drives stay in the trip log.",
                                color = HudColors.TextMuted,
                                style = HudeCfgHelp(),
                            )
                            HudButton(
                                "DELETE VEHICLE",
                                modifier = Modifier.fillMaxWidth(),
                                style = HudButtonStyle.DANGER,
                                glyph = HudIcons.Bin,
                                onClick = { confirming = true },
                            )
                        }
                    }
                }

                // A save refusal stays beside the SAVE that caused it; the photo notes live
                // in the photo card, where they were earned.
                EditorFooter(
                    refusal = refusal,
                    onSave = {
                        // The typed fields and the staged photo decision leave this screen
                        // together, and land together - one SAVE, or none.
                        onSave(
                            draft.copy(
                                name = name, type = type, customType = customType, plate = plate,
                                maker = maker, model = model, year = year, notes = notes,
                            ),
                            photoEdit,
                        )
                    },
                    onCancel = onDismiss,
                )
            }
        }
    }

    // Both overlays sit above the form rather than inside it: the form is scrolled, and a
    // confirmation or a crop that scrolled with it would be off the screen by the time the
    // user reached the bottom of the fields.

    if (confirming) {
        // The plan's sentence, in the app's own dialog - and no card id in it. The drives are
        // found by name now; naming a row key in a warning would be a detail nobody can use.
        HudConfirmDialog(
            message = "Delete this vehicle? Its pairing is dropped as well; its drives stay in " +
                "the trip log.",
            onConfirm = onDelete,
            onDismiss = { confirming = false },
            confirmLabel = "DELETE ${draft.name.ifBlank { "THE CARD" }}",
            cancelLabel = "KEEP IT",
            confirmStyle = HudButtonStyle.DANGER,
            // D3 keeps the words, rule 7 lends the marks: the confirm destroys, so it wears
            // the bin rather than the default check; the cancel is a plain dismissal.
            confirmGlyph = HudIcons.Bin,
            cancelGlyph = HudIcons.Close,
        )
    }

    // One crop step for both doors: a staged scratch capture ([cropFrom]) or an
    // already-existing file being re-framed ([reframeFrom] - the card's stored original,
    // or a pending draft crop's own source). The USE THIS PHOTO handler is identical and
    // **touches no disk**: it decodes a preview for the well and stages a
    // [PhotoEdit.Crop] the panel closes over. Cancel closes over nothing - a scratch
    // capture the pick brought goes, a re-frame of an existing file leaves the file
    // exactly as it was, and in both cases a pending draft decision survives, because
    // its source file survives (scratch captures are cleaned once at editor close).
    val cropSource = cropFrom ?: reframeFrom
    cropSource?.let { staged ->
        PhotoCropPanel(
            source = staged,
            onUse = { rect ->
                vm.previewPhotoCrop(staged, rect, wellEdgePx * 2) { preview ->
                    cropFrom = null
                    reframeFrom = null
                    photoNote = null
                    photoEdit = PhotoEdit.Crop(staged, rect, preview)
                }
            },
            onCancel = {
                val wasScratch = cropFrom != null
                cropFrom = null
                reframeFrom = null
                if (wasScratch) vm.cancelPhoto(draft.id)
            },
        )
    }
}

/**
 * The sheet's heading: the page's `h2` in its gradient, with the live name and type in the
 * `ip-banner` pill the page puts under every heading. The banner is fed from the fields, so
 * naming a new card changes what the sheet says it is while the user types it.
 */
@Composable
private fun EditorHeader(
    title: String,
    name: String,
    typeLabel: String,
    identityTail: String,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
    ) {
        Text(title, style = HudeHeading().copy(brush = HudBrushes.title))
        Spacer(Modifier.height(8.dp))
        HudBanner(
            text = name.ifBlank { "UNTITLED $identityTail" },
            color = HudColors.TextPrimary,
            note = typeLabel,
            noteColor = HudColors.Cyan,
        )
    }
}

/**
 * One section of the form, drawn as the page's `.card` — the same fill, hairline, 8 dp corners
 * and 12x14 padding as every settings card, and `CardHead`'s `.card h4` title. The heading is
 * what turns the editor from one undifferentiated stack of labels into the four records it
 * actually holds: the photo, the user's half, the app's evidence, and the delete action.
 */
@Composable
private fun EditorCard(
    title: String,
    titleColor: Color = HudColors.Cyan,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().hudCard().padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        CardHead(title, titleColor)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/**
 * The photo card: the well beside its two doors, then — only for a card that already has a
 * picture — re-frame and remove on their own row. The well keeps the garage cover's square
 * and its wheel glyph, because the card the user is editing will show this picture at 76 dp
 * under the same corners; the buttons are in a column to its right so the photo, not a row of
 * labels, is what the eye lands on, and re-frame comes before remove because it is the kinder
 * answer to "I don't like this framing".
 */
@Composable
private fun PhotoCard(
    hasPhoto: Boolean,
    preview: Bitmap?,
    note: String?,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onReframe: () -> Unit,
    onRemove: () -> Unit,
) {
    EditorCard("PHOTO") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhotoWell(preview)
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HudButton(
                    "CHOOSE PHOTO",
                    modifier = Modifier.fillMaxWidth(),
                    style = HudButtonStyle.SECONDARY,
                    glyph = HudIcons.File,
                    onClick = onGallery,
                )
                HudButton(
                    "TAKE PHOTO",
                    modifier = Modifier.fillMaxWidth(),
                    style = HudButtonStyle.SECONDARY,
                    glyph = HudIcons.Camera,
                    onClick = onCamera,
                )
                Text(
                    "Shown as a square on the card.",
                    color = HudColors.TextDim,
                    style = HudeCfgNote(),
                )
            }
        }
        if (hasPhoto) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(
                    "RE-FRAME",
                    modifier = Modifier.weight(1f),
                    style = HudButtonStyle.SECONDARY,
                    glyph = HudIcons.Frame,
                    onClick = onReframe,
                )
                HudButton(
                    "REMOVE",
                    modifier = Modifier.weight(1f),
                    style = HudButtonStyle.DANGER,
                    glyph = HudIcons.Bin,
                    onClick = onRemove,
                )
            }
        }
        // A photo problem is said inside the photo card, not at the foot of the form: the
        // note was produced by this card's buttons, and a fix — pick again — is one glance
        // and one reach away.
        note?.let { Text(it, color = HudColors.Amber, style = HudeCfgHelp()) }
    }
}

/**
 * The well itself: the card's picture, or the bar's wheel glyph until it has one. The glyph
 * is the same honesty the garage cover keeps — an empty square that shows nothing looks
 * broken, and one that shows the glyph says "vehicle, no photo yet".
 */
@Composable
private fun PhotoWell(preview: Bitmap?) {
    Box(
        Modifier
            .size(WELL_EDGE.dp)
            .clip(HudRadius.CardShape)
            .background(HudColors.Panel)
            .border(1.dp, HudColors.Hairline, HudRadius.CardShape),
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = "this card's photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                HudIcons.Wheel,
                contentDescription = null,
                tint = HudColors.TextDim,
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

/**
 * Edge of the editor's photo well, in dp. The garage cover is 76 dp; this is the same square
 * at 120 dp — big enough to judge the framing, and small enough to leave the action column
 * beside it room to spell `CHOOSE PHOTO` on a 360 dp phone.
 */
private const val WELL_EDGE = 120

/** One read-only line of the DASHBOARD card: a label over the value the app measured. */
@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = HudColors.TextMuted, style = HudeFormLabel())
        Spacer(Modifier.height(4.dp))
        Text(value, color = HudColors.TextPrimary, style = HudeCfgLive())
    }
}

/**
 * SAVE and CANCEL, pinned under the cards. They used to live at the end of the scroll, which
 * on a filled form is a phone-screenful of fields away from the heading; a footer that stays
 * put is also what lets the keyboard inset live on the sheet instead of on the scroll.
 *
 * A save refusal is drawn here, immediately above the SAVE that caused it, in red and in the
 * same help voice as the photo notes.
 */
@Composable
private fun EditorFooter(
    refusal: String?,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.Hairline))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            refusal?.let {
                Text(it, color = HudColors.Red, style = HudeCfgHelp())
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HudButton(
                    "SAVE",
                    modifier = Modifier.weight(1f),
                    style = HudButtonStyle.PRIMARY,
                    glyph = HudIcons.Check,
                    onClick = onSave,
                )
                HudButton(
                    "CANCEL",
                    modifier = Modifier.weight(1f),
                    style = HudButtonStyle.SECONDARY,
                    glyph = HudIcons.Close,
                    onClick = onCancel,
                )
            }
        }
    }
}

/** One editable field: its label over the input, in the same furniture as every settings row. */
@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = HudColors.TextMuted, style = HudeFormLabel())
        Spacer(Modifier.height(6.dp))
        HudInput(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            style = HudInputStyle.FIELD,
            placeholder = placeholder,
            keyboardType = keyboardType,
            imeAction = ImeAction.Next,
        )
    }
}

/**
 * The TYPE control: a shared HUD option menu over the broad vehicle preset list.
 *
 * The shared menu keeps the field fill, active edge, opaque sheet, flat rows and three-row
 * scroll viewport used by the firmware-version control. `OTHER`'s custom word is the trigger's
 * own label — the card should say `RICKSHAW`, not hide the fact behind a generic category.
 */
@Composable
private fun TypeDropdown(
    selected: VehicleType,
    selectedLabel: String,
    onSelect: (VehicleType) -> Unit,
) {
    val options = remember { VehicleType.values().toList() }
    HudSelectMenu(
        triggerLabel = selectedLabel,
        items = options,
        label = { it.label },
        isSelected = { it == selected },
        onPick = onSelect,
    )
}
