package com.alefinot.dashboardpp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudRadius
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.hudGlass
import com.alefinot.dashboardpp.vehicles.CropRect
import com.alefinot.dashboardpp.vehicles.PhotoCrop
import com.alefinot.dashboardpp.vehicles.VehiclePhotoStore
import java.io.File

@Composable
fun PhotoCropPanel(
    source: File,
    onUse: (CropRect) -> Unit,
    onCancel: () -> Unit,
) {
    var frame by remember { mutableStateOf(IntSize.Zero) }
    var full by remember(source) { mutableStateOf<Pair<Int, Int>?>(null) }
    var preview by remember(source) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var unreadable by remember(source) { mutableStateOf(false) }
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var offX by remember(source) { mutableFloatStateOf(0f) }
    var offY by remember(source) { mutableFloatStateOf(0f) }

    BackHandler(enabled = true) { onCancel() }

    LaunchedEffect(source) {
        val bounds = readBounds(source)
        if (bounds == null) unreadable = true else full = bounds
    }

    LaunchedEffect(source, frame.width) {
        val img = full ?: return@LaunchedEffect
        if (frame.width <= 0 || preview != null) return@LaunchedEffect
        preview = decodePreview(source, img.first, img.second, frame.width)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(HudColors.Scrim)
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        HudEnter(
            Modifier
                .fillMaxSize()
                .widthIn(max = 620.dp)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            durationMs = HudMotion.SHORT_MS,
            riseDp = 8,
            scaleFrom = 0.98f,
        ) {
            Column(Modifier.hudGlass().fillMaxSize().padding(20.dp)) {
                Text("FRAME THE PHOTO", color = HudColors.Cyan, style = HudeSectionTitle())
                Spacer(Modifier.height(10.dp))
                Text(
                    if (unreadable) {
                        "That picture could not be read. The card keeps the photo it had."
                    } else {
                        "Drag the photo to move it, spread to zoom in. The square is what the " +
                            "card will show; the picture is cropped to it, not stretched."
                    },
                    color = HudColors.TextMuted,
                    style = HudeCfgHelp(),
                )
                Spacer(Modifier.height(14.dp))

                // The leftover height, and the square inside it.
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val side = minOf(maxWidth, maxHeight)
                        Box(
                            Modifier
                                .size(side)
                                .clip(HudRadius.CardShape)
                                .background(HudColors.Panel)
                                .border(1.dp, HudColors.Hairline, HudRadius.CardShape)
                                .onSizeChanged { frame = it }
                                .pointerInput(source, full, frame.width) {
                                    if (frame.width <= 0) return@pointerInput
                                    detectTransformGestures { _, pan, zoomFactor, _ ->
                                        val img = full ?: return@detectTransformGestures
                                        val next = (zoom * zoomFactor).coerceIn(1f, MAX_ZOOM)
                                        zoom = next
                                        val (maxX, maxY) = PhotoCrop.maxOffsetPx(
                                            img.first, img.second, frame.width, next,
                                        )
                                        offX = (offX + pan.x).coerceIn(-maxX, maxX)
                                        offY = (offY + pan.y).coerceIn(-maxY, maxY)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            preview?.let { bmp ->
                                val img = full ?: return@let
                                val density = LocalDensity.current
                                val cover = frame.width.toFloat() / minOf(img.first, img.second)
                                val w = (img.first * cover * zoom).toInt().coerceAtLeast(1)
                                val h = (img.second * cover * zoom).toInt().coerceAtLeast(1)
                                with(density) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "the photo being framed for this card",
                                        contentScale = ContentScale.FillBounds,
                                        modifier = Modifier
                                            .size(w.toDp(), h.toDp())
                                            .graphicsLayer {
                                                translationX = offX
                                                translationY = offY
                                            },
                                    )
                                }
                            }
                            if (preview == null && !unreadable) {
                                Text("reading the photo…", color = HudColors.TextDim, style = HudeCfgHelp())
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).heightIn(min = 48.dp)) {
                        HudButton(
                            "USE THIS PHOTO",
                            style = HudButtonStyle.PRIMARY,
                            enabled = !unreadable,
                        ) {
                            val img = full ?: return@HudButton
                            val rect = PhotoCrop.rectFor(
                                img.first, img.second, frame.width, zoom, offX, offY,
                            )
                            if (rect != null) onUse(rect)
                        }
                    }
                    Box(Modifier.weight(1f).heightIn(min = 48.dp)) {
                        HudButton("CANCEL", style = HudButtonStyle.SECONDARY) { onCancel() }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Cancelling leaves the photo the card already has.",
                    color = HudColors.TextDim,
                    style = HudeCfgHelp(),
                )
            }
        }
    }
}

private suspend fun readBounds(f: File): Pair<Int, Int>? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!f.isFile) return@withContext null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else o.outWidth to o.outHeight
    }

private suspend fun decodePreview(
    f: File,
    imgW: Int,
    imgH: Int,
    framePx: Int,
): android.graphics.Bitmap? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val edge = (framePx * 2).coerceAtMost(PREVIEW_EDGE).coerceAtLeast(256)
    val o = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = VehiclePhotoStore.sampleSizeFor(imgW, imgH, edge)
    }
    android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
}

/** Longest edge of the framing preview: about 3.7 MB, the transient this screen is allowed. */
private const val PREVIEW_EDGE = 1280

/** Pinch ceiling. Past this the preview is a blur and no crop is worth having. */
private const val MAX_ZOOM = 8f
