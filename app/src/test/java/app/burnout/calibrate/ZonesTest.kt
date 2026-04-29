package app.burnout.calibrate

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
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
}
