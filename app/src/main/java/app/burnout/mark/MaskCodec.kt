package app.burnout.mark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * PNG encode/decode for WearMask + app-internal autosave.
 *
 * Mark mode persists to <filesDir>/mask_autosave.png only (spec §4.4) — no
 * /sdcard/Download/ Save and no /data/burnout/ commit. Calibrate (Phase 4) reads
 * this autosave on entry.
 */
object MaskCodec {

    fun toBitmap(mask: WearMask): Bitmap {
        val bmp = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(mask.toPixelArray(), 0, mask.width, 0, 0, mask.width, mask.height)
        return bmp
    }

    fun fromBitmap(bitmap: Bitmap): WearMask {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return WearMask.fromPixelArray(pixels, bitmap.width, bitmap.height)
    }

    fun encodePng(mask: WearMask, out: OutputStream) {
        val bmp = toBitmap(mask)
        try {
            check(bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG compression failed" }
        } finally {
            bmp.recycle()
        }
    }

    fun decodePng(input: InputStream): WearMask {
        val bmp = BitmapFactory.decodeStream(input)
            ?: error("PNG decode returned null")
        try {
            return fromBitmap(bmp)
        } finally {
            bmp.recycle()
        }
    }

    fun internalAutoSavePath(context: Context): File =
        File(context.filesDir, "mask_autosave.png")

    fun saveAutoSave(context: Context, mask: WearMask) {
        internalAutoSavePath(context).outputStream().use { encodePng(mask, it) }
    }

    fun loadAutoSave(context: Context): WearMask? {
        val file = internalAutoSavePath(context)
        if (!file.exists()) return null
        return file.inputStream().use { decodePng(it) }
    }
}
