package app.burnout.mark

import android.graphics.Bitmap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MaskCodecTest {

    @Test
    fun `WearMask to Bitmap preserves pixel values (top-of-bucket)`() {
        val mask = WearMask(2, 2)
        mask.mark(0, 0, Channel.R, Severity(2))   // R=127 = 0x7F
        mask.mark(1, 1, Channel.G, Severity(4))   // G=255 = 0xFF
        val bmp: Bitmap = MaskCodec.toBitmap(mask)
        assertEquals(2, bmp.width)
        assertEquals(2, bmp.height)
        assertEquals(0xFF7F0000.toInt(), bmp.getPixel(0, 0))
        assertEquals(0xFF00FF00.toInt(), bmp.getPixel(1, 1))
    }

    @Test
    fun `Bitmap to WearMask preserves channel severities (bucket boundaries)`() {
        val bmp = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, 0xFF400000.toInt())   // R=64 -> bucket 2 -> Severity(2)
        bmp.setPixel(1, 0, 0xFF00C000.toInt())   // G=192 -> bucket 4 -> Severity(4)
        val mask = MaskCodec.fromBitmap(bmp)
        assertEquals(Severity(2), mask.getSeverity(0, 0, Channel.R))
        assertEquals(Severity(4), mask.getSeverity(1, 0, Channel.G))
    }

    @Test
    fun `encode to PNG bytes and decode round-trips`() {
        val original = WearMask(4, 3)
        original.mark(1, 1, Channel.R, Severity(1))
        original.mark(2, 2, Channel.B, Severity(4))

        val bytes = ByteArrayOutputStream().use { out ->
            MaskCodec.encodePng(original, out)
            out.toByteArray()
        }
        assertEquals(true, bytes.size > 0)

        val decoded = ByteArrayInputStream(bytes).use { MaskCodec.decodePng(it) }

        for (x in 0 until 4) for (y in 0 until 3) {
            for (channel in listOf(Channel.R, Channel.G, Channel.B)) {
                assertEquals(
                    original.getSeverity(x, y, channel),
                    decoded.getSeverity(x, y, channel),
                    "mismatch at ($x,$y) $channel"
                )
            }
        }
    }
}
