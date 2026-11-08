package com.alefinot.dashboardpp.vehicles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.util.LruCache
import androidx.core.content.FileProvider
import com.alefinot.dashboardpp.storage.AppRoot
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class VehiclePhotoStore(context: Context) {
    private val appContext = context.applicationContext

    /** Decoded photos, [CACHE_BYTES] of them, largest-cost entries evicted first. */
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun dir(): File = AppRoot.vehiclesDir(appContext)

    fun fileFor(photoFile: String?): File? = photoFileIn(dir(), photoFile)

    fun writeFrom(source: File, vehicleId: String): File? =
        commit(decodeScaled(source, MAX_STORED_EDGE), vehicleId, source)

    fun cropFrom(source: File, vehicleId: String, rect: CropRect): File? {
        val pixels = cropByRegion(source, rect, MAX_STORED_EDGE)
            ?: cropByWholeFile(source, rect, MAX_STORED_EDGE)
        return commit(pixels, vehicleId, source)
    }

    private fun commit(pixels: Bitmap?, vehicleId: String, source: File): File? {
        val target = fileForId(vehicleId) ?: return null
        if (pixels == null) return null
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        val sameFile = source.absolutePath == target.absolutePath
        var landed = false
        try {
            if (!writeJpeg(pixels, tmp)) return null
            landed = tmp.renameTo(target)
        } catch (e: Exception) {
        } finally {
            if (!landed) tmp.delete()
        }
        if (!landed) return null
        evict(vehicleId)
        if (!sameFile) source.delete()
        return target
    }

    private fun cropByRegion(f: File, rect: CropRect, maxEdge: Int): Bitmap? {
        if (!f.isFile || rect.size <= 0) return null
        return try {
            FileInputStream(f).use { input ->
                @Suppress("DEPRECATION")
                val dec = BitmapRegionDecoder.newInstance(input, false) ?: return null
                try {
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleForCrop(rect.size, maxEdge)
                    }
                    val box = Rect(rect.x, rect.y, rect.x + rect.size, rect.y + rect.size)
                    shrinkTo(dec.decodeRegion(box, opts) ?: return null, maxEdge)
                } finally {
                    @Suppress("DEPRECATION")
                    dec.recycle()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun cropByWholeFile(f: File, rect: CropRect, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null // torn, truncated, or not an image
        val whole = decodeScaled(f, maxEdge) ?: return null
        val small = PhotoCrop.scaleRectInto(rect, bounds.outWidth, bounds.outHeight, whole.width, whole.height)
            ?: return null
        if (whole.width == whole.height && small.size == whole.width && small.x == 0 && small.y == 0) {
            return whole
        }
        return try {
            Bitmap.createBitmap(whole, small.x, small.y, small.size, small.size)
        } catch (e: Exception) {
            null // defensive: scaleRectInto already keeps the rectangle inside the bitmap
        }
    }

    fun captureFile(vehicleId: String): File? {
        val name = nameOf(vehicleId)
        if (!isPhotoName(name)) return null
        val d = File(appContext.cacheDir, CAPTURE_DIR)
        if (!d.isDirectory && !d.mkdirs()) return null
        return File(d, name)
    }

    fun captureFor(vehicleId: String): Capture? {
        val f = captureFile(vehicleId) ?: return null
        val uri = try {
            FileProvider.getUriForFile(appContext, appContext.packageName + '.' + PROVIDER_SUFFIX, f)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return Capture(f, uri)
    }

    /** A scratch capture and the URI that stands for it to another app. */
    data class Capture(val file: File, val uri: Uri)

    fun stageFrom(source: Uri, vehicleId: String): File? {
        val target = captureFile(vehicleId) ?: return null
        return try {
            val input = appContext.contentResolver.openInputStream(source) ?: return null
            input.use { ins -> FileOutputStream(target).use { out -> ins.copyTo(out, COPY_BUFFER) } }
            if (target.isFile && target.length() > 0) target else { target.delete(); null }
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    fun clearCapture(vehicleId: String) {
        captureFile(vehicleId)?.delete()
    }

    fun read(vehicleId: String, maxEdge: Int): Bitmap? {
        if (maxEdge <= 0) return null // an invisible box must not decode the full file
        val f = fileForId(vehicleId) ?: return null
        if (!f.isFile) {
            evict(vehicleId)
            return null
        }
        val key = cacheKey(f.name, f.lastModified(), maxEdge)
        cache.get(key)?.let { return it }
        val bmp = decodeScaled(f, maxEdge) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun evict(vehicleId: String) {
        val prefix = nameOf(vehicleId) + ":"
        for (key in cache.snapshot().keys) {
            if (key.startsWith(prefix)) cache.remove(key)
        }
    }

    fun delete(vehicleId: String) {
        fileForId(vehicleId)?.delete()
        evict(vehicleId)
    }

    /** `<id>.jpg` resolved through the same guard as a stored `photoFile`. */
    private fun fileForId(vehicleId: String): File? = photoFileIn(dir(), nameOf(vehicleId))

    private fun decodeScaled(f: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null // torn, truncated, or not an image

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        val decoded = BitmapFactory.decodeFile(f.absolutePath, options) ?: return null
        return shrinkTo(decoded, maxEdge)
    }

    private fun shrinkTo(bmp: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(bmp.width, bmp.height)
        if (edge <= maxEdge) return bmp
        val scale = maxEdge.toFloat() / edge
        val w = max(1, (bmp.width * scale).roundToInt())
        val h = max(1, (bmp.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    private fun writeJpeg(bmp: Bitmap, tmp: File): Boolean {
        FileOutputStream(tmp).use { out ->
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return false
            out.flush()
        }
        return true
    }

    private fun nameOf(vehicleId: String) = "$vehicleId$PHOTO_SUFFIX"

    companion object {
        /** Longest edge kept on disk - plan §3 (a card's preview, not a print). */
        const val MAX_STORED_EDGE = 1280

        /** JPEG quality of the stored copy: 85 is visually indistinguishable here. */
        const val JPEG_QUALITY = 85

        private const val PHOTO_SUFFIX = ".jpg"
        private const val TMP_SUFFIX = ".tmp"

        /** Scratch for camera captures and picked images, under the app's cache dir. */
        private const val CAPTURE_DIR = "captures"

        private const val PROVIDER_SUFFIX = "fileprovider"

        /** Copy buffer for [stageFrom]: one 16 KiB buffer per staged image (rule 14). */
        private const val COPY_BUFFER = 16 * 1024

        /** The decoded-photo budget for one store, in bytes (rule 14). */
        private const val CACHE_BYTES = 8 * 1024 * 1024

        fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
            if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
            val longest = if (width > height) width else height
            var sample = 1
            while (longest / sample > maxEdge) sample *= 2
            return sample
        }

        fun sampleForCrop(size: Int, maxEdge: Int): Int {
            if (size <= 0 || maxEdge <= 0) return 1
            var sample = 1
            while (size / (sample * 2) >= maxEdge) sample *= 2
            return sample
        }

        internal fun photoFileIn(dir: File, photoFile: String?): File? {
            val name = photoFile ?: return null
            if (!isPhotoName(name)) return null
            return File(dir, name)
        }

        private fun isPhotoName(name: String): Boolean =
            name.isNotEmpty() &&
                name != "." &&
                !name.contains('/') &&
                !name.contains('\\') &&
                !name.contains("..") &&
                !name.any { it.isISOControl() }

        /** Cache key: a photo plus what it was decoded for. A new file, or a new box, is a new entry. */
        private fun cacheKey(name: String, mtime: Long, maxEdge: Int): String =
            "$name:$mtime:$maxEdge"
    }
}
