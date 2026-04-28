package app.burnout.calibrate

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MaskBlurTest {

    @Test
    fun small_sigma_returns_copy() {
        val src = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val out = MaskBlur.gaussian(src, sigma = 0.4f)
        assertNotSame("must return a new bitmap", src, out)
        assertEquals(src.width, out.width); assertEquals(src.height, out.height)
    }

    @Test
    fun globalMaxWearPerChannel_finds_per_channel_maxima() {
        val bmp = Bitmap.createBitmap(3, 1, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, Color.argb(255, 200,  10,   5))   // R=200
        bmp.setPixel(1, 0, Color.argb(255,  10, 180,  20))   // G=180
        bmp.setPixel(2, 0, Color.argb(255,  20,  30, 250))   // B=250
        val per = MaskBlur.globalMaxWearPerChannel(bmp)
        assertEquals(200f / 255f, per[0], 0.001f)
        assertEquals(180f / 255f, per[1], 0.001f)
        assertEquals(250f / 255f, per[2], 0.001f)
    }

    @Test
    fun blank_mask_has_zero_max_wear() {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val per = MaskBlur.globalMaxWearPerChannel(bmp)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), per, 0.001f)
    }
}
