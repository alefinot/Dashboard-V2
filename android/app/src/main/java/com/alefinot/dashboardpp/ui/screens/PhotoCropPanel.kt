package com.alefinot.dashboardpp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.alefinot.dashboardpp.ui.components.HudButton
import com.alefinot.dashboardpp.ui.components.HudButtonStyle
import com.alefinot.dashboardpp.ui.components.HudEnter
import com.alefinot.dashboardpp.ui.components.HudIcons
import com.alefinot.dashboardpp.ui.components.HudOverlayScrim
import com.alefinot.dashboardpp.ui.components.HudStepButton
import com.alefinot.dashboardpp.ui.theme.HudColors
import com.alefinot.dashboardpp.ui.theme.HudMotion
import com.alefinot.dashboardpp.ui.theme.HudeCfgHelp
import com.alefinot.dashboardpp.ui.theme.HudeSectionTitle
import com.alefinot.dashboardpp.ui.theme.hudGlass
import com.alefinot.dashboardpp.vehicles.CropRect
import com.alefinot.dashboardpp.vehicles.PhotoCrop
import com.alefinot.dashboardpp.vehicles.VehiclePhotoStore
import kotlin.math.roundToInt
import java.io.File

/**
 * The step between "a picture arrived" and "this card has a photo" (vehicle page plan §5).
 *
 * It exists because of an aspect problem, not a decoration problem. A camera gives a
 * landscape 4:3 frame; a card's cover wants a square. Somebody has to decide which square,
 * and it should be the person who took the picture - an automatic centre crop cuts the number
 * plate off one car and the handlebars off another. So the user drags and pinches, and the
 * square they framed is what the store is asked for, in the original file's pixels
 * ([PhotoCrop.rectFor]) - which is how a crop off a 4000x3000 shot is decoded without this
 * app ever holding the whole photograph.
 *
 * **What is shown while framing is a preview, and only a preview.** It is decoded once at up
 * to [PREVIEW_EDGE] on the longest edge - about 3.7 MB of pixels, which is the biggest
 * transient this screen is allowed - and it is drawn at the aspect the full file has, so what
 * the user frames matches what they get while the pixels under a finger stay cheap. The one
 * promise the draw makes is that the displayed pixels sit at exactly the geometry
 * [PhotoCrop.rectFor] assumes — same cover fit, same zoom, same pan — because a preview that
 * disagrees with the maths shows the user one photo and saves another. The crop itself is
 * decoded a second time, from the untouched source file — a scratch capture, or the card's own
 * stored JPEG when the framing of an existing photo is being corrected — at the size actually
 * kept. Anyone tempted to reuse the preview bitmap for the crop should not: it is already
 * sub-sampled once, so the card would come out soft at exactly the moment the user made the
 * picture sharp.
 *
 * **The square is sized by the leftover height, not by the panel's width.** That is the one
 * layout fact on this screen worth a paragraph, because getting it wrong is invisible on a
 * wide tablet and broken on a phone: a 1:1 box as wide as a 620 dp panel is 620 dp tall,
 * which on a 640 dp-tall phone leaves the panel's own title off the top of the screen. So the
 * words and the buttons take what they need, the middle gets the rest (`weight(1f)`), and the
 * window is the shorter side of what is left.
 *
 * **The whole leftover box is the viewport; the centred square is the crop window.** The
 * photo is drawn in the crop window's own box, so it covers the brackets exactly and the
 * viewport outside the square stays dim - what the card keeps is what the eye holds. The
 * window - dimmed outside, bracketed at its corners, ruled in thirds - is the square the
 * card keeps, and it sits at exactly the centre and the side the preview geometry and
 * [PhotoCrop.rectFor] have always assumed: photo, chrome and maths share one measurement,
 * the box that `onSizeChanged` reports into [frame], so the guarantee from the earlier
 * bench pass stands: what the eye frames is what the maths asks the file for, to the pixel.
 * Pinch and drag work anywhere on the photo, and the \u2212 / round-arrow / + steppers put the
 * same two numbers under a thumb for anyone whose fingers will not pinch.
 *
 * A tap on the scrim does nothing, and Back cancels: the plan is explicit that CANCEL keeps
 * the previous photo, and the previous photo is whatever is already on disk. This panel
 * writes nothing - not on USE THIS PHOTO either, which since 2026-11-10 hands the framing
 * to the editor as a draft; disk belongs to SAVE.
 */
@Composable
fun PhotoCropPanel(
    source: File,
    onUse: (CropRect) -> Unit,
    onCancel: () -> Unit,
) {
    // The measured side of the crop window, in pixels. Every number [PhotoCrop] works with
    // comes from here or from the file's header, which is why nothing on this screen guesses
    // at a size.
    var frame by remember { mutableStateOf(IntSize.Zero) }
    var full by remember(source) { mutableStateOf<Pair<Int, Int>?>(null) }
    var preview by remember(source) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var unreadable by remember(source) { mutableStateOf(false) }
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var offX by remember(source) { mutableFloatStateOf(0f) }
    var offY by remember(source) { mutableFloatStateOf(0f) }

    BackHandler(enabled = true) { onCancel() }

    // The ZOOM buttons speak through the same two numbers a pinch writes: the new zoom,
    // then the offsets re-clamped through the very function the drag clamps itself with.
    // A button that skipped the clamp would park the photo where the next drag has to
    // fight the clamp from - a jump under the finger, invented by the button.
    fun nudgeZoom(direction: Int) {
        val img = full ?: return
        if (frame.width <= 0) return
        val step = if (direction > 0) ZOOM_STEP else 1f / ZOOM_STEP
        val next = (zoom * step).coerceIn(1f, MAX_ZOOM)
        zoom = next
        val (maxX, maxY) = PhotoCrop.maxOffsetPx(img.first, img.second, frame.width, next)
        offX = offX.coerceIn(-maxX, maxX)
        offY = offY.coerceIn(-maxY, maxY)
    }

    // Dimensions first, on their own: they come from the file's header without allocating a
    // single pixel, so the panel can say "this picture cannot be read" - and size the preview
    // decode - before anything expensive happens.
    LaunchedEffect(source) {
        val bounds = readBounds(source)
        if (bounds == null) unreadable = true else full = bounds
    }

    // The preview waits for BOTH facts it sizes itself from: the measured frame and the
    // header's dimensions. `full` is a key for the same reason `frame.width` is - the two
    // arrive from different effects in unpredictable order, and an effect that ran while
    // either was still missing must be woken when it lands. Bench (2026-11-10, a 4000x3000
    // pick): the header read lost that race, the effect returned early once, nothing ever
    // re-ran it, and the panel sat on "reading the photo…" forever over a perfectly good
    // picture. The `preview != null` guard keeps a second wake-up from decoding twice.
    LaunchedEffect(source, frame.width, full) {
        val img = full ?: return@LaunchedEffect
        if (frame.width <= 0 || preview != null) return@LaunchedEffect
        // A null here is a decode that genuinely failed - bounds read fine, pixels did not
        // (an OOM on a low-RAM device, or a file torn between the two reads). Say so
        // instead of lying "reading the photo…" forever, and let USE THIS PHOTO disable
        // itself rather than cropping a file the panel could not even display.
        preview = decodePreview(source, img.first, img.second, frame.width)
            ?: run { unreadable = true; null }
    }

    HudOverlayScrim {
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
                        "Unreadable picture \u2014 card keeps its photo."
                    } else {
                        "The brackets are the square this card keeps. Drag the photo " +
                            "inside them; pinch, or step with \u2212 and + \u2014 the round arrow starts over."
                    },
                    color = HudColors.TextMuted,
                    style = HudeCfgHelp(),
                )
                Spacer(Modifier.height(14.dp))

                // The leftover height, and the crop window centred inside it.
                //
                // The whole box is the viewport and the photo is drawn across it, so the
                // user sees what a crop cuts away - the rest is the window (dim beyond,
                // bracketed, ruled in thirds). Gestures live on the viewport so the whole
                // visible photo answers a finger, while [frame] keeps measuring the
                // window: the invisible square is where the crop maths' geometry lives,
                // and the photo's centre and the window's centre are the same point (both
                // centred in this box), so the cover fit, the zoom and the pan mean
                // exactly what they meant when the photo lived inside the square. The
                // clip is now the viewport's - the photo may leave the window, but never
                // this box and certainly never over the buttons below it.
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val side = minOf(maxWidth, maxHeight)
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .pointerInput(source, full, frame.width) {
                                    if (frame.width <= 0) return@pointerInput
                                    detectTransformGestures { _, pan, zoomFactor, _ ->
                                        val img = full ?: return@detectTransformGestures
                                        val next = (zoom * zoomFactor).coerceIn(1f, MAX_ZOOM)
                                        zoom = next
                                        // Clamped to the amount of picture that actually hangs
                                        // outside the frame. Past that the finger keeps moving
                                        // while the crop stops, which reads as the app ignoring
                                        // the gesture rather than as the photo running out.
                                        val (maxX, maxY) = PhotoCrop.maxOffsetPx(
                                            img.first, img.second, frame.width, next,
                                        )
                                        offX = (offX + pan.x).coerceIn(-maxX, maxX)
                                        offY = (offY + pan.y).coerceIn(-maxY, maxY)
                                    }
                                },
                        ) {
                            // The crop window: measured, invisible, and the only square
                            // the crop maths know. Its side is `side`, its centre is this
                            // box's centre - which is also where the photo below is centred.
                            Box(
                                Modifier
                                    .size(side)
                                    .align(Alignment.Center)
                                    .onSizeChanged { frame = it },
                            )
                            preview?.let { bmp ->
                                val img = full ?: return@let
                                // Cover fit at zoom 1, then the user's zoom — all of it in
                                // **screen pixels**, and this time with no dp round-trip to
                                // lose a density factor on the way. The old build measured
                                // this image through Modifier.size(Dp, Dp) built by dividing
                                // those pixel numbers through the density; on the bench Tab A
                                // (@213 dpi) the layout came back one density smaller than
                                // the numbers say, so a fresh photo drew 1092x820 inside the
                                // 1092x1092 window — bands of empty frame top and bottom —
                                // and zooming it to reach the brackets overshot the crop
                                // USE THIS PHOTO performs. Modifier.layout measures and places
                                // in raw px (Constraints.fixed, place(Int, Int)): the drawn
                                // box is [PhotoCrop]'s cover fit, per pixel, and photo,
                                // window and chrome are all anchored to the same [frame]
                                // measurement. FillBounds stays safe because the box keeps
                                // the file's aspect by construction.
                                val cover = frame.width.toFloat() / minOf(img.first, img.second)
                                val wPx = (img.first * cover * zoom).roundToInt().coerceAtLeast(1)
                                val hPx = (img.second * cover * zoom).roundToInt().coerceAtLeast(1)
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "the photo being framed for this card",
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.layout { measurable, constraints ->
                                        val placeable = measurable.measure(Constraints.fixed(wPx, hPx))
                                        val vw = constraints.maxWidth
                                        val vh = constraints.maxHeight
                                        layout(vw, vh) {
                                            placeable.place(
                                                (vw - wPx) / 2 + offX.toInt(),
                                                (vh - hPx) / 2 + offY.toInt(),
                                            )
                                        }
                                    },
                                )
                            }
                            if (preview == null && !unreadable) {
                                Text(
                                    "reading the photo…",
                                    color = HudColors.TextDim,
                                    style = HudeCfgHelp(),
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                            // Dim outside, brackets and thirds inside: the crop metaphor,
                            // drawn from the window's own measurement - the same `frame`
                            // the photo and the crop maths use - so the three squares
                            // cannot drift apart and disagree with [PhotoCrop.rectFor].
                            CropChrome(frame.width, Modifier.fillMaxSize().align(Alignment.Center))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                // The zoom bar is steppers now, not three word buttons: `ZOOM -` and `ZOOM +`
                // are the step marks every numeric row in the app already wears, and RESET is
                // `view.reset` - the circular arrow every "put it back the way it came" draws.
                // The a11y sentences move into the descriptions, where the glyph-only grammar
                // requires them.
                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HudStepButton(HudIcons.Minus, "Zoom out", true) { nudgeZoom(-1) }
                        HudStepButton(HudIcons.Reset, "Reset the framing", true) {
                            zoom = 1f
                            offX = 0f
                            offY = 0f
                        }
                        HudStepButton(HudIcons.Plus, "Zoom in", true) { nudgeZoom(1) }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).heightIn(min = 48.dp)) {
                        HudButton(
                            "USE THIS PHOTO",
                            style = HudButtonStyle.PRIMARY,
                            glyph = HudIcons.Check,
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
                        HudButton(
                            "CANCEL",
                            style = HudButtonStyle.SECONDARY,
                            glyph = HudIcons.Close,
                            onClick = onCancel,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/**
 * The crop metaphor, drawn over the viewport: everything outside the centred square is
 * dimmed (that part of the photo is what the card will *not* keep), the square carries a
 * rule-of-thirds grid and corner brackets, and its outline is the window's own.
 *
 * The square's side is [edgePx] - the px measurement the crop window reported when it was
 * laid out, the very number [PhotoCrop.rectFor] is worked from - so chrome, photo and
 * maths all agree on one square by construction. A second, independent measurement of
 * "the same" square is exactly how a 2026-11-10 bench build ended up bracketing a 1092 px
 * square around a photo and maths that lived in an 820 px one: two measurements, two
 * answers, and a crop tool whose frame did not match its crop.
 */
@Composable
private fun CropChrome(edgePx: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val edge = edgePx.toFloat()
        if (edge <= 0f) return@Canvas
        val left = (size.width - edge) / 2f
        val top = (size.height - edge) / 2f
        // Dimmed outside: the answer to "which part is the photo now?" without a guess.
        val dim = Color(0x8C000000)
        drawRect(dim, Offset.Zero, Size(size.width, top))
        drawRect(dim, Offset(0f, top + edge), Size(size.width, size.height - top - edge))
        drawRect(dim, Offset.Zero, Size(left, edge))
        drawRect(dim, Offset(left + edge, top), Size(size.width - left - edge, edge))
        // The window's edge, so the square still reads when the photo does not reach it.
        drawRect(
            color = HudColors.Hairline,
            topLeft = Offset(left, top),
            size = Size(edge, edge),
            style = Stroke(width = 1.dp.toPx()),
        )
        // Rule of thirds: where the plate wants to sit is a thing a crop tool says.
        val grid = Color(0x40FFFFFF)
        for (f in 1..2) {
            drawLine(
                grid,
                Offset(left + edge * f / 3f, top),
                Offset(left + edge * f / 3f, top + edge),
                1.dp.toPx(),
            )
            drawLine(
                grid,
                Offset(left, top + edge * f / 3f),
                Offset(left + edge, top + edge * f / 3f),
                1.dp.toPx(),
            )
        }
        // Corner brackets: a viewfinder says "frame it", a plain box says "there, done".
        val bracket = HudColors.Cyan.copy(alpha = 0.9f)
        val len = edge * 0.12f
        val w = 3.dp.toPx()
        val corners = listOf(
            Offset(left, top) to Offset(1f, 1f),
            Offset(left + edge, top) to Offset(-1f, 1f),
            Offset(left, top + edge) to Offset(1f, -1f),
            Offset(left + edge, top + edge) to Offset(-1f, -1f),
        )
        for ((corner, dir) in corners) {
            drawLine(bracket, corner, Offset(corner.x + dir.x * len, corner.y), w, StrokeCap.Round)
            drawLine(bracket, corner, Offset(corner.x, corner.y + dir.y * len), w, StrokeCap.Round)
        }
    }
}

/**
 * Header-only read of the file: dimensions without pixels. `null` when the file is torn,
 * truncated, or not an image at all - which the panel reports instead of showing an empty box.
 */
private suspend fun readBounds(f: File): Pair<Int, Int>? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!f.isFile) return@withContext null
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else o.outWidth to o.outHeight
    }

/**
 * The framing preview: as large as the frame it is shown in (twice over, for a finger that
 * will zoom later), never larger than [PREVIEW_EDGE], and sub-sampled by the store's own rule
 * so the decoder is never asked to allocate a frame the screen cannot use.
 *
 * Twice the frame rather than once because a pinch that starts before the pixels arrive would
 * otherwise be stretching a bitmap smaller than the box, and softness during the gesture reads
 * as a broken crop.
 */
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

/** Per-press factor of the ZOOM buttons - one deliberate thumb-tap of zoom, not a crawl. */
private const val ZOOM_STEP = 1.4f
