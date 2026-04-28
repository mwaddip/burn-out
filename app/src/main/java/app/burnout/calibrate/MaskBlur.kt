package app.burnout.calibrate

import android.graphics.Bitmap

object MaskBlur {

    fun gaussian(src: Bitmap, sigma: Float): Bitmap {
        if (sigma <= 0.5f) return src.copy(Bitmap.Config.ARGB_8888, false)
        val w = src.width; val h = src.height
        val srcPx = IntArray(w * h); src.getPixels(srcPx, 0, w, 0, 0, w, h)

        val r = (sigma * 3f).toInt().coerceAtLeast(1)
        val kernel = FloatArray(2 * r + 1)
        var ksum = 0f
        for (i in -r..r) {
            val v = Math.exp(-(i * i) / (2.0 * sigma * sigma)).toFloat()
            kernel[i + r] = v; ksum += v
        }
        for (i in kernel.indices) kernel[i] /= ksum

        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var aR = 0f; var aG = 0f; var aB = 0f
                for (k in -r..r) {
                    val xx = (x + k).coerceIn(0, w - 1)
                    val px = srcPx[row + xx]
                    val wt = kernel[k + r]
                    aR += ((px shr 16) and 0xFF) * wt
                    aG += ((px shr  8) and 0xFF) * wt
                    aB += ( px         and 0xFF) * wt
                }
                tmp[row + x] = (0xFF shl 24) or
                    (aR.toInt().coerceIn(0, 255) shl 16) or
                    (aG.toInt().coerceIn(0, 255) shl  8) or
                    (aB.toInt().coerceIn(0, 255))
            }
        }
        val outPx = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var aR = 0f; var aG = 0f; var aB = 0f
                for (k in -r..r) {
                    val yy = (y + k).coerceIn(0, h - 1)
                    val px = tmp[yy * w + x]
                    val wt = kernel[k + r]
                    aR += ((px shr 16) and 0xFF) * wt
                    aG += ((px shr  8) and 0xFF) * wt
                    aB += ( px         and 0xFF) * wt
                }
                outPx[y * w + x] = (0xFF shl 24) or
                    (aR.toInt().coerceIn(0, 255) shl 16) or
                    (aG.toInt().coerceIn(0, 255) shl  8) or
                    (aB.toInt().coerceIn(0, 255))
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    fun globalMaxWearPerChannel(mask: Bitmap): FloatArray {
        val w = mask.width; val h = mask.height
        val px = IntArray(w * h); mask.getPixels(px, 0, w, 0, 0, w, h)
        var mr = 0; var mg = 0; var mb = 0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr  8) and 0xFF
            val b =  p         and 0xFF
            if (r > mr) mr = r
            if (g > mg) mg = g
            if (b > mb) mb = b
        }
        return floatArrayOf(mr / 255f, mg / 255f, mb / 255f)
    }
}
