package com.alefinot.dashboardpp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.alefinot.dashboardpp.vehicles.VehiclePhotoStore
import com.alefinot.dashboardpp.vehicles.VehicleType
import java.io.File

@Composable
fun VehicleEditorScreen(
    vm: ConnectionViewModel,
    draft: Vehicle,
    isNew: Boolean,
    refusal: String?,
    onSave: (Vehicle) -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
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

    var cropFrom by remember(draft.id) { mutableStateOf<File?>(null) }
    var pendingCapture by remember(draft.id) { mutableStateOf<VehiclePhotoStore.Capture?>(null) }
    var hasPhoto by remember(draft.id) { mutableStateOf(draft.photoFile != null) }
    var well by remember(draft.id) { mutableStateOf<Bitmap?>(null) }
    var photoNote by remember(draft.id) { mutableStateOf<String?>(null) }

    val wellEdgePx = with(LocalDensity.current) { WELL_EDGE.dp.toPx().toInt() }
    LaunchedEffect(draft.id, vm.photoVersion.intValue) {
        vm.photo(draft.id, wellEdgePx * 2) { well = it }
    }

    val pickFromGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            photoNote = null
            vm.stagePickedPhoto(uri, draft.id) { staged ->
                if (staged == null) {
                    photoNote = "That picture could not be read. The card keeps the photo it had."
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
            capture != null -> {
                vm.cancelPhoto(draft.id)
                photoNote = null
            }
            else -> photoNote = "The camera left nothing usable to frame."
        }
    }

    fun askForCamera() {
        vm.cameraCapture(draft.id) { capture ->
            if (capture == null) {
                photoNote = "No camera is available here. Choose a photo instead."
            } else {
                photoNote = null
                pendingCapture = capture
                shootPhoto.launch(capture.uri)
            }
        }
    }

    val identityTail = draft.mac?.takeLast(6)?.uppercase() ?: draft.id.uppercase()

    Box(
        Modifier
            .fillMaxSize()
            .background(HudColors.Scrim)
            // Consumes the tap so nothing behind the scrim can be hit by accident.
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        HudEnter(
            Modifier.fillMaxWidth().widthIn(max = 520.dp).padding(horizontal = 24.dp),
            durationMs = HudMotion.SHORT_MS,
            riseDp = 8,
            scaleFrom = 0.98f,
        ) {
            Column(
                Modifier
                    .hudGlass()
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
            ) {
                Text(
                    if (isNew) "NAME THIS DASHBOARD" else "EDIT VEHICLE",
                    color = HudColors.Cyan,
                    style = HudeSectionTitle(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (isNew) {
                        "A dashboard answered that no card carries. Give it a name and the app " +
                            "keeps a card for it — the address book, the photo and the trips " +
                            "all hang off that card."
                    } else {
                        "What this card says about the vehicle, and what the app measured. " +
                            "Only the first half is edited here."
                    },
                    color = HudColors.TextMuted,
                    style = HudeCfgHelp(),
                )
                Spacer(Modifier.height(16.dp))

                PhotoWell(
                    hasPhoto = hasPhoto,
                    preview = well,
                    onGallery = {
                        pickFromGallery.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onCamera = { askForCamera() },
                    onRemove = {
                        hasPhoto = false
                        well = null
                        photoNote = null
                        vm.removePhoto(draft.id) { refusal -> photoNote = refusal }
                    },
                )
                Spacer(Modifier.height(16.dp))

                Field(
                    "IDENTITY (FROM THE DASHBOARD)",
                    draft.mac ?: "not reported by this dashboard",
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    "PAIRED BY THIS PHONE",
                    draft.bleAddress ?: "no pairing yet — search for it from the vehicle page",
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    "FIRMWARE LAST SEEN",
                    draft.firmwareVersion ?: "unknown",
                )
                Spacer(Modifier.height(16.dp))

                Field(
                    "NAME",
                    name,
                    onValueChange = { name = it },
                    placeholder = "UNTITLED $identityTail",
                )
                Spacer(Modifier.height(12.dp))

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
                Spacer(Modifier.height(14.dp))

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

                if (refusal != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(refusal, color = HudColors.Red, style = HudeCfgHelp())
                }
                photoNote?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = HudColors.Amber, style = HudeCfgHelp())
                }

                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).heightIn(min = 48.dp)) {
                        HudButton("SAVE", style = HudButtonStyle.PRIMARY) {
                            onSave(
                                draft.copy(
                                    name = name, type = type, plate = plate, maker = maker,
                                    model = model, year = year, notes = notes,
                                ),
                            )
                        }
                    }
                    Box(Modifier.weight(1f).heightIn(min = 48.dp)) {
                        HudButton("CANCEL", style = HudButtonStyle.SECONDARY) { onDismiss() }
                    }
                }

                if (!isNew) {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).heightIn(min = 44.dp)) {
                            HudButton("DELETE VEHICLE", style = HudButtonStyle.DANGER) { confirming = true }
                        }
                    }
                }
            }
        }
    }

if (confirming) {
        HudConfirmDialog(
            message = "The card goes, its photo goes with it, and the addresses it remembered " +
                "stop being dialled. The drives this vehicle took stay in the trip log.",
            onConfirm = onDelete,
            onDismiss = { confirming = false },
            confirmLabel = "FORGET ${draft.name.ifBlank { "THE CARD" }}",
            cancelLabel = "KEEP IT",
            confirmStyle = HudButtonStyle.DANGER,
        )
    }

    cropFrom?.let { staged ->
        PhotoCropPanel(
            source = staged,
            onUse = { rect ->
                vm.applyPhotoCrop(staged, draft.id, rect) { landed, refusal ->
                    cropFrom = null
                    when {
                        landed == null -> photoNote =
                            "The photo could not be cut down to a file. The card keeps " +
                            "the picture it had."
                        refusal != null -> {
                            hasPhoto = true
                            photoNote = refusal
                        }
                        else -> {
                            hasPhoto = true
                            photoNote = null
                        }
                    }
                }
            },
            onCancel = {
                cropFrom = null
                vm.cancelPhoto(draft.id)
            },
        )
    }
}

@Composable
private fun PhotoWell(
    hasPhoto: Boolean,
    preview: Bitmap?,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
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
                HudIcons.Car,
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
                HudButton("REMOVE PHOTO", style = HudButtonStyle.DANGER, onClick = onRemove)
            }
        }
    }
}

/** Edge of the editor's photo well, in dp. Twice the card cover, and still one hand's width. */
private const val WELL_EDGE = 164

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
