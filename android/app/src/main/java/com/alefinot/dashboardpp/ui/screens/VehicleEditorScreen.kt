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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudConfirmDialog
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudInput
import com.alefinot.dashboardpp.ui.components.HudInputStyle
import com.alefinot.dashboardpp.ui.components.HudOverlayScrim
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.viewmodel.ConnectionViewModel
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeCfgLive
import com.alefinot.dashboardpp.ui.theme.HudeFormLabel
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.hudGlass
import com.alefinot.dashboardpp.vehicles.Vehicle
import com.alefinot.dashboardpp.vehicles.PhotoEdit
import com.alefinot.dashboardpp.vehicles.VehiclePhotoStore
import com.alefinot.dashboardpp.vehicles.VehicleType
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
            Modifier.fillMaxWidth().widthIn(max = 520.dp).padding(horizontal = 24.dp),
            durationMs = HudMotion.SHORT_MS,
            riseDp = 8,
            scaleFrom = 0.98f,
        ) {
            Column(
                Modifier
                    .hudGlass()
                    // The keyboard is an inset this screen must consume itself (Issue 1,
                    // 2026-11-10): with targetSdk 35 edge-to-edge is forced, the window is
                    // not resized for the soft keyboard, and the only inset the app takes
                    // anywhere is DashboardRoot's system bars - so without this the scroll
                    // viewport ran to the physical bottom and SAVE / CANCEL / DELETE sat
                    // under the keys with no scroll that could reach them. imePadding
                    // first, then the scroll: the glass panel and its viewport clamp to
                    // (scrim - keyboard) and the buttons scroll into view. The root's
                    // statusBars/navigationBars padding stays where it is - the IME is a
                    // distinct inset, so nothing is counted twice (PhoneViewportTest).
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
            ) {
                Text(
                    if (isNew) "NAME THIS DASHBOARD" else "EDIT VEHICLE",
                    color = HudColors.Cyan,
                    style = HudeSectionTitle(),
                )
                Spacer(Modifier.height(16.dp))

                PhotoWell(
                    hasPhoto = hasPhoto,
                    preview = wellPreview,
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
                // A photo problem is said under the photo, not at the foot of a scrolling
                // form: the note was produced by this block's buttons, and a fix — pick
                // again — is one glance and one reach away.
                photoNote?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = HudColors.Amber, style = HudeCfgHelp())
                }
                Spacer(Modifier.height(20.dp))

                // The evidence half, read-only. A blank identity is stated rather than left
                // out: it is the reason this card cannot be recognised when it answers, and
                // hiding that would leave the user guessing why the other card exists.
                // The two lines are not interchangeable. The MAC is the dashboard's own claim
                // about itself and survives anything; the paired address is this phone's link to
                // that radio, and it changes if the pairing is redone — which is why it is
                // labelled as a pairing and never offered as an identity.
                Field(
                    "IDENTITY (FROM THE DASHBOARD)",
                    draft.mac ?: "not reported by this dashboard",
                )
                Spacer(Modifier.height(8.dp))
                Field(
                    "PAIRED BY THIS PHONE",
                    draft.bleAddress ?: "no pairing yet — search for it from the vehicle page",
                )
                Spacer(Modifier.height(8.dp))
                Field(
                    "FIRMWARE LAST SEEN",
                    draft.firmwareVersion ?: "unknown",
                )
                Spacer(Modifier.height(20.dp))

                Field(
                    "NAME",
                    name,
                    onValueChange = { name = it },
                    placeholder = "UNTITLED $identityTail",
                )
                Spacer(Modifier.height(12.dp))

                // A row of three rather than a spinner: the app has no dropdown in its
                // furniture, and on a tablet all three choices are visible at once and one
                // tap away, which is what a dropdown was for in the first place.
                Text("TYPE", color = HudColors.TextMuted, style = HudeFormLabel())
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in VehicleType.values()) {
                        Box(Modifier.weight(1f).heightIn(min = 44.dp)) {
                            HudButton(
                                option.name,
                                style = if (option == type) HudButtonStyle.PRIMARY else HudButtonStyle.SECONDARY,
                            ) { type = option }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                Field("PLATE", plate, onValueChange = { plate = it }, placeholder = "plate, if the vehicle has one")
                Spacer(Modifier.height(12.dp))
                Field("MAKER", maker, onValueChange = { maker = it }, placeholder = "Toyota")
                Spacer(Modifier.height(12.dp))
                Field("MODEL", model, onValueChange = { model = it }, placeholder = "Corolla")
                Spacer(Modifier.height(12.dp))
                Field(
                    "YEAR", year,
                    onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) },
                    placeholder = "1998",
                    keyboardType = KeyboardType.Number,
                )
                Spacer(Modifier.height(12.dp))
                Field("NOTES", notes, onValueChange = { notes = it }, placeholder = "anything the card should remember")

                // A save refusal stays here, right above the SAVE that caused it; the
                // photo notes live under the photo well, where they were earned.
                if (refusal != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(refusal, color = HudColors.Red, style = HudeCfgHelp())
                }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).heightIn(min = 48.dp)) {
                        HudButton("SAVE", style = HudButtonStyle.PRIMARY) {
                            // The typed fields and the staged photo decision leave this
                            // screen together, and land together - one SAVE, or none.
                            onSave(
                                draft.copy(
                                    name = name, type = type, plate = plate, maker = maker,
                                    model = model, year = year, notes = notes,
                                ),
                                photoEdit,
                            )
                        }
                    }
                    Box(Modifier.weight(1f).heightIn(min = 48.dp)) {
                        HudButton("CANCEL", style = HudButtonStyle.SECONDARY) { onDismiss() }
                    }
                }

                if (!isNew) {
                    // The hairline is the sentence: what sits under it is not part of the
                    // form. Deleting is the one button here the user should never brush past
                    // on the way to SAVE.
                    Spacer(Modifier.height(22.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(HudColors.Hairline))
                    Spacer(Modifier.height(14.dp))
                    HudButton(
                        "DELETE VEHICLE",
                        modifier = Modifier.fillMaxWidth(),
                        style = HudButtonStyle.DANGER,
                    ) { confirming = true }
                }
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
 * The photo well: the card's picture at the top of the form, and the four things that can
 * be done to it. A card with no photo shows the bar's wheel glyph rather than a blank frame,
 * the same honesty the garage cover keeps - an empty square that shows nothing looks broken,
 * and an empty square that shows the glyph says "vehicle, no photo yet".
 *
 * The two buttons that only mean something for a card that already has a picture — re-frame
 * and remove — appear on their own second row, so a card without a photo reads as two choices
 * and a card with one reads as four. Re-frame comes before remove because it is the answer to
 * "I don't like how this is framed", which is the kinder failure; remove is the one a slip of
 * the finger must not reach first.
 */
@Composable
private fun PhotoWell(
    hasPhoto: Boolean,
    preview: Bitmap?,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onReframe: () -> Unit,
    onRemove: () -> Unit,
) {
    Text("PHOTO", color = HudColors.TextMuted, style = HudeFormLabel())
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier
            .size(WELL_EDGE.dp)
            .clip(HudRadius.CardShape)
            .background(HudColors.Panel)
            .border(1.dp, HudColors.Hairline, HudRadius.CardShape),
        contentAlignment = Alignment.Center,
    ) {
        preview?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "this card's photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(WELL_EDGE.dp),
            )
        }
        if (preview == null) {
            Icon(
                HudIcons.Wheel,
                contentDescription = null,
                tint = HudColors.TextDim,
                modifier = Modifier.size(46.dp),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).heightIn(min = 44.dp)) {
            HudButton("CHOOSE PHOTO", style = HudButtonStyle.SECONDARY, onClick = onGallery)
        }
        Box(Modifier.weight(1f).heightIn(min = 44.dp)) {
            HudButton("TAKE PHOTO", style = HudButtonStyle.SECONDARY, onClick = onCamera)
        }
    }
    if (hasPhoto) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).heightIn(min = 40.dp)) {
                HudButton("RE-FRAME PHOTO", style = HudButtonStyle.SECONDARY, onClick = onReframe)
            }
            Box(Modifier.weight(1f).heightIn(min = 40.dp)) {
                HudButton("REMOVE PHOTO", style = HudButtonStyle.DANGER, onClick = onRemove)
            }
        }
    }
}

/** Edge of the editor's photo well, in dp — exactly twice the card cover's 76 dp. */
private const val WELL_EDGE = 152

/**
 * A label over a value. With [onValueChange] it is an editable field; without it the value
 * is text the user reads but does not type — the same furniture either way, so the read-only
 * rows above the form do not look like a different kind of control.
 */
@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: ((String) -> Unit)? = null,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = HudColors.TextMuted, style = HudeFormLabel())
        Spacer(Modifier.height(6.dp))
        if (onValueChange == null) {
            Text(value, color = HudColors.TextPrimary, style = HudeCfgLive())
        } else {
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
}
