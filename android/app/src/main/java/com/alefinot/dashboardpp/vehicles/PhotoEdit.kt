package com.alefinot.dashboardpp.vehicles

import android.graphics.Bitmap
import java.io.File

/**
 * A photo decision made inside the vehicle editor, **staged until SAVE**.
 *
 * The text half of the editor has always been draft-scoped: name, plate, notes — the
 * fields at `VehicleEditorScreen` — only reach disk through SAVE, and CANCEL drops them.
 * The photo half used to commit the moment a button was pressed, which made CANCEL a
 * liar (the card came back with a different picture than the garage had left) and made
 * a brand-new card unable to get a photo at all (there was no record to point at the
 * JPEG yet). Since 2026-11-10 the photo behaves like the text: the editor holds one of
 * these, and only [ConnectionViewModel.saveVehicleWithPhoto]-style code that also writes
 * the record touches disk.
 *
 * It lives in `vehicles`, not beside the screen, because it crosses the same line the
 * [Vehicle] record does — the editor stages it, the view model executes it — and both
 * sides must mean the same thing by it.
 */
sealed interface PhotoEdit {

    /** Nothing was touched: whatever photo the card has on disk, it keeps. */
    data object Keep : PhotoEdit

    /**
     * The card loses its photo. On SAVE this deletes `<id>.jpg` **and** its stored
     * original `<id>.src.jpg` (files first, then the record's pointer — the order
     * [VehiclePhotoStore.delete] and the old `removePhoto` kept because a record naming
     * a gone file reads as "no photo", which is a cosmetic lie, while a pointer left
     * behind a deleted file is not).
     */
    data object Remove : PhotoEdit

    /**
     * The card gains [source] cropped to [rect] as its photo. On SAVE the store crops
     * [source] into `<id>.jpg` (keeping the original as `<id>.src.jpg` when [source] is
     * a fresh capture) and the record lands pointing at it in the same write.
     *
     * [preview] is a display-only square for the editor's photo well, decoded once at
     * the well's size. It is deliberately *never* the thing that gets saved — the crop
     * is decoded afresh from [source] on SAVE, so a framing the user staged stays as
     * sharp as the file they picked. A failed preview decode answers `null` and costs a
     * redraw, never the crop: the panel has already proven it can read the file.
     */
    data class Crop(val source: File, val rect: CropRect, val preview: Bitmap?) : PhotoEdit
}
