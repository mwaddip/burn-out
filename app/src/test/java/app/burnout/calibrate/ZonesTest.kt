package app.burnout.calibrate

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZonesTest {

    private fun blank(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun empty_mask_yields_no_zones() {
        val zones = Zones.computeFromMask(blank(100, 100))
        assertTrue(zones.isEmpty())
    }

    @Test
    fun single_blob_above_min_size_yields_one_zone() {
        val bmp = blank(200, 200)
        for (y in 50..70) for (x in 50..70) bmp.setPixel(x, y, Color.GREEN)  // 21x21 = 441 px
        val zones = Zones.computeFromMask(bmp)
        assertEquals(1, zones.size)
        // Bbox is 50..70 → with 10px PAD: 40..80
        assertEquals(40, zones[0].x0); assertEquals(40, zones[0].y0)
        assertEquals(80, zones[0].x1); assertEquals(80, zones[0].y1)
    }

    @Test
    fun blob_below_min_size_is_dropped() {
        val bmp = blank(200, 200)
        // 6 px is < MIN_CLUSTER_PIXELS (50)
        for (y in 50..52) for (x in 50..51) bmp.setPixel(x, y, Color.GREEN)
        val zones = Zones.computeFromMask(bmp)
        assertTrue(zones.isEmpty())
    }

    @Test
    fun cap_is_twelve() {
        // Place 14 separated 10x10 blobs (100 px each) far apart in a 7000x100 strip; expect cap 12.
        // Need spacing > CLUSTER_MERGE_PX (200) so they don't merge.
        val bmp = blank(7000, 100)
        for (i in 0 until 14) {
            val x0 = i * 400 + 10
            for (y in 40..49) for (x in x0 until x0 + 10) bmp.setPixel(x, y, Color.GREEN)
        }
        val zones = Zones.computeFromMask(bmp)
        assertEquals(12, zones.size)
    }

    @Test
    fun zone_channel_mask_reflects_painted_channels() {
        val bmp = blank(200, 200)
        // Paint only G in this blob.
        for (y in 50..70) for (x in 50..70) bmp.setPixel(x, y, Color.GREEN)
        val zones = Zones.computeFromMask(bmp)
        assertEquals(1, zones.size)
        assertEquals("R should not be flagged", 0f, zones[0].channels[0], 0.001f)
        assertEquals("G should be flagged",     1f, zones[0].channels[1], 0.001f)
        assertEquals("B should not be flagged", 0f, zones[0].channels[2], 0.001f)
    }

    @Test
    fun two_channels_painted_adjacent_yield_two_zones_one_per_channel() {
        val bmp = blank(200, 200)
        // R and G painted in adjacent regions. Per-channel CC means TWO zones, each single-channel.
        for (y in 50..70) for (x in 50..70) bmp.setPixel(x, y, Color.RED)
        for (y in 80..100) for (x in 50..70) bmp.setPixel(x, y, Color.GREEN)
        val zones = Zones.computeFromMask(bmp)
        assertEquals(2, zones.size)
        // Each zone has exactly one channel set.
        for (z in zones) {
            val sum = z.channels.sum()
            assertEquals("zone must have exactly one channel set", 1f, sum, 0.001f)
        }
        // The R zone (top, y=50-70 → centerY ≈ 60) sorts first; G zone (y=80-100 → ≈ 90) second.
        assertEquals(1f, zones[0].channels[0], 0.001f)  // R first
        assertEquals(1f, zones[1].channels[1], 0.001f)  // G second
    }

    @Test
    fun two_channels_overlapping_yield_two_zones_with_overlapping_bbox() {
        val bmp = blank(200, 200)
        // Same area painted in both R and G (e.g. yellow paint pattern).
        for (y in 50..70) for (x in 50..70) {
            bmp.setPixel(x, y, Color.argb(255, 200, 200, 0))   // R+G, no B
        }
        val zones = Zones.computeFromMask(bmp)
        assertEquals(2, zones.size)
        // R zone and G zone have the same bbox (same painted pixels).
        assertEquals(zones[0].x0, zones[1].x0); assertEquals(zones[0].x1, zones[1].x1)
        assertEquals(zones[0].y0, zones[1].y0); assertEquals(zones[0].y1, zones[1].y1)
        // Each is single-channel.
        assertEquals(1f, zones[0].channels.sum(), 0.001f)
        assertEquals(1f, zones[1].channels.sum(), 0.001f)
    }

    @Test
    fun zones_sorted_top_to_bottom() {
        val bmp = blank(200, 1000)
        // Bottom blob first by index, top blob second
        for (y in 800..820) for (x in 50..70) bmp.setPixel(x, y, Color.GREEN)
        for (y in 100..120) for (x in 50..70) bmp.setPixel(x, y, Color.GREEN)
        val zones = Zones.computeFromMask(bmp)
        assertEquals(2, zones.size)
        assertTrue("first zone is the upper one", zones[0].y0 < zones[1].y0)
    }

    // ---- rectBboxFromMarks ----

    @Test
    fun rectBboxFromMarks_returns_null_when_rect_is_empty_of_marks() {
        val bmp = blank(200, 200)
        // Paint a green blob OUTSIDE the rectangle below.
        for (y in 5..15) for (x in 5..15) bmp.setPixel(x, y, Color.GREEN)
        val gd = floatArrayOf(0.08f, 0.08f, 0.08f)
        val zone = Zones.rectBboxFromMarks(bmp, 100, 100, 150, 150, gd)
        assertNull("rectangle with no marks must return null", zone)
    }

    @Test
    fun rectBboxFromMarks_single_channel_returns_tight_bbox_with_pad() {
        val bmp = blank(200, 200)
        // 21x21 G blob at (50..70, 50..70).
        for (y in 50..70) for (x in 50..70) bmp.setPixel(x, y, Color.GREEN)
        // User selects a generous rectangle around it.
        val gd = floatArrayOf(0.10f, 0.20f, 0.30f)
        val zone = Zones.rectBboxFromMarks(bmp, 30, 30, 100, 100, gd)
        assertNotNull(zone)
        zone!!
        // Tight bbox 50..70 + 10px pad → 40..80.
        assertEquals(40, zone.x0); assertEquals(40, zone.y0)
        assertEquals(80, zone.x1); assertEquals(80, zone.y1)
        // Only G is flagged.
        assertEquals(0f, zone.channels[0], 0.001f)
        assertEquals(1f, zone.channels[1], 0.001f)
        assertEquals(0f, zone.channels[2], 0.001f)
        // Initial dim = globalDim[G] since only G is present.
        assertEquals(0.20f, zone.dim, 0.001f)
    }

    @Test
    fun rectBboxFromMarks_multichannel_zone_has_combined_channel_mask() {
        val bmp = blank(200, 200)
        // R blob at (50..60), G blob at (62..72), both within the user's rectangle.
        for (y in 50..60) for (x in 50..60) bmp.setPixel(x, y, Color.RED)
        for (y in 62..72) for (x in 62..72) bmp.setPixel(x, y, Color.GREEN)
        val gd = floatArrayOf(0.10f, 0.30f, 0.50f)
        val zone = Zones.rectBboxFromMarks(bmp, 40, 40, 90, 90, gd)
        assertNotNull(zone)
        zone!!
        // Encloses both blobs: 50..72 + 10px pad → 40..82.
        assertEquals(40, zone.x0); assertEquals(40, zone.y0)
        assertEquals(82, zone.x1); assertEquals(82, zone.y1)
        // Both R and G flagged; B is not.
        assertEquals(1f, zone.channels[0], 0.001f)
        assertEquals(1f, zone.channels[1], 0.001f)
        assertEquals(0f, zone.channels[2], 0.001f)
        // Initial dim = mean(globalDim[R], globalDim[G]) = (0.10 + 0.30) / 2 = 0.20.
        assertEquals(0.20f, zone.dim, 0.001f)
    }

    @Test
    fun rectBboxFromMarks_clamps_pad_at_mask_boundary() {
        val bmp = blank(100, 100)
        // Blob in the corner so 10px pad would go negative.
        for (y in 0..5) for (x in 0..5) bmp.setPixel(x, y, Color.GREEN)
        val gd = floatArrayOf(0.08f, 0.08f, 0.08f)
        val zone = Zones.rectBboxFromMarks(bmp, 0, 0, 50, 50, gd)
        assertNotNull(zone)
        zone!!
        assertEquals(0, zone.x0); assertEquals(0, zone.y0)
        assertEquals(15, zone.x1); assertEquals(15, zone.y1)  // 5 + 10 pad, no clamp on upper
    }
}
