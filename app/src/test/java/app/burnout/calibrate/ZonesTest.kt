package app.burnout.calibrate

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val zone = Zones.rectBboxFromMarks(bmp, 100, 100, 150, 150, gd,
            activeChannel = 1, existingZones = emptyList())
        assertNull("rectangle with no marks must return null", zone)
    }

    @Test
    fun rectBboxFromMarks_single_channel_returns_tight_bbox_with_pad() {
        val bmp = blank(200, 200)
        // 21x21 G blob at (50..70, 50..70).
        for (y in 50..70) for (x in 50..70) bmp.setPixel(x, y, Color.GREEN)
        // User selects a generous rectangle around it.
        val gd = floatArrayOf(0.10f, 0.20f, 0.30f)
        val zone = Zones.rectBboxFromMarks(bmp, 30, 30, 100, 100, gd,
            activeChannel = 1, existingZones = emptyList())
        assertNotNull(zone)
        zone!!
        // Tight bbox 50..70 + 10px pad → 40..80.
        assertEquals(40, zone.x0); assertEquals(40, zone.y0)
        assertEquals(80, zone.x1); assertEquals(80, zone.y1)
        // Only G is flagged.
        assertEquals(0f, zone.channels[0], 0.001f)
        assertEquals(1f, zone.channels[1], 0.001f)
        assertEquals(0f, zone.channels[2], 0.001f)
        // Initial dim = globalDim[G].
        assertEquals(0.20f, zone.dim, 0.001f)
    }

    @Test
    fun rectBboxFromMarks_only_active_channel_paint_counts() {
        val bmp = blank(200, 200)
        // R blob at (50..70, 50..70), G blob at (100..120, 100..120). Rect covers both.
        for (y in 50..70) for (x in 50..70) bmp.setPixel(x, y, Color.RED)
        for (y in 100..120) for (x in 100..120) bmp.setPixel(x, y, Color.GREEN)
        val gd = floatArrayOf(0.10f, 0.20f, 0.30f)
        // activeChannel = R (0): bbox should fit only R blob, ignore G.
        val zone = Zones.rectBboxFromMarks(bmp, 30, 30, 150, 150, gd,
            activeChannel = 0, existingZones = emptyList())
        assertNotNull(zone); zone!!
        // R blob 50..70 + 10px pad → 40..80
        assertEquals(40, zone.x0); assertEquals(40, zone.y0)
        assertEquals(80, zone.x1); assertEquals(80, zone.y1)
        assertEquals(1f, zone.channels[0], 0.001f)
        assertEquals(0f, zone.channels[1], 0.001f)
        assertEquals(0f, zone.channels[2], 0.001f)
        assertEquals(0.10f, zone.dim, 0.001f)  // globalDim[R]
    }

    @Test
    fun rectBboxFromMarks_returns_null_when_no_active_channel_paint_in_rect() {
        val bmp = blank(200, 200)
        // Only G paint inside rect; activeChannel = R.
        for (y in 50..70) for (x in 50..70) bmp.setPixel(x, y, Color.GREEN)
        val gd = floatArrayOf(0.10f, 0.20f, 0.30f)
        val zone = Zones.rectBboxFromMarks(bmp, 30, 30, 150, 150, gd,
            activeChannel = 0, existingZones = emptyList())
        assertNull(zone)
    }

    @Test
    fun rectBboxFromMarks_rejects_when_overlaps_same_channel_zone() {
        val bmp = blank(200, 200)
        for (y in 50..70) for (x in 50..70) bmp.setPixel(x, y, Color.RED)
        val gd = floatArrayOf(0.10f, 0.20f, 0.30f)
        // Existing R zone at 30..90 overlaps with the new bbox 40..80.
        val existing = listOf(Zone(30, 30, 90, 90, 0.10f, floatArrayOf(1f, 0f, 0f)))
        val zone = Zones.rectBboxFromMarks(bmp, 30, 30, 150, 150, gd,
            activeChannel = 0, existingZones = existing)
        assertNull("must reject — overlaps existing R zone", zone)
    }

    @Test
    fun rectBboxFromMarks_succeeds_when_overlaps_different_channel_zone() {
        val bmp = blank(200, 200)
        for (y in 50..70) for (x in 50..70) bmp.setPixel(x, y, Color.RED)
        val gd = floatArrayOf(0.10f, 0.20f, 0.30f)
        // Existing G zone at the same spot — different channel, no rejection.
        val existing = listOf(Zone(30, 30, 90, 90, 0.20f, floatArrayOf(0f, 1f, 0f)))
        val zone = Zones.rectBboxFromMarks(bmp, 30, 30, 150, 150, gd,
            activeChannel = 0, existingZones = existing)
        assertNotNull("must succeed — only same-channel collisions reject", zone)
    }

    @Test
    fun rectBboxFromMarks_clamps_pad_at_mask_boundary() {
        val bmp = blank(100, 100)
        // Blob in the corner so 10px pad would go negative.
        for (y in 0..5) for (x in 0..5) bmp.setPixel(x, y, Color.GREEN)
        val gd = floatArrayOf(0.08f, 0.08f, 0.08f)
        val zone = Zones.rectBboxFromMarks(bmp, 0, 0, 50, 50, gd,
            activeChannel = 1, existingZones = emptyList())
        assertNotNull(zone)
        zone!!
        assertEquals(0, zone.x0); assertEquals(0, zone.y0)
        assertEquals(15, zone.x1); assertEquals(15, zone.y1)  // 5 + 10 pad, no clamp on upper
    }

    // ---- canSplitZone ----

    @Test
    fun canSplitZone_true_when_horizontal_gap_exceeds_threshold() {
        val bmp = blank(200, 200)
        // Two horizontal stripes with a 30-row gap (mark-free) between them.
        for (y in 50..60) for (x in 50..150) bmp.setPixel(x, y, Color.GREEN)
        for (y in 91..101) for (x in 50..150) bmp.setPixel(x, y, Color.GREEN)
        // Pretend the parent zone tightly encloses both: bbox 40..160 x 40..111.
        val parent = Zone(40, 40, 160, 111, 0.1f, floatArrayOf(0f, 1f, 0f))
        // Gap rows 61..90 = 30 rows of empty → >= threshold 10.
        assertTrue(Zones.canSplitZone(bmp, parent))
    }

    @Test
    fun canSplitZone_true_when_vertical_gap_exceeds_threshold() {
        val bmp = blank(200, 200)
        // Two vertical stripes separated by an empty column band.
        for (y in 50..150) for (x in 50..60) bmp.setPixel(x, y, Color.GREEN)
        for (y in 50..150) for (x in 91..101) bmp.setPixel(x, y, Color.GREEN)
        val parent = Zone(40, 40, 111, 160, 0.1f, floatArrayOf(0f, 1f, 0f))
        assertTrue(Zones.canSplitZone(bmp, parent))
    }

    @Test
    fun canSplitZone_false_when_blob_is_solid() {
        val bmp = blank(200, 200)
        // Single solid 21x21 blob — no internal gap.
        for (y in 50..70) for (x in 50..70) bmp.setPixel(x, y, Color.GREEN)
        val parent = Zone(40, 40, 80, 80, 0.1f, floatArrayOf(0f, 1f, 0f))
        assertFalse(Zones.canSplitZone(bmp, parent))
    }

    // ---- splitZone ----

    @Test
    fun splitZone_horizontal_gap_splits_top_bottom_with_inherited_metadata() {
        val bmp = blank(200, 200)
        // Top blob 50..60, bottom blob 91..101, gap rows 61..90 (30 rows, midpoint 75).
        for (y in 50..60) for (x in 50..150) bmp.setPixel(x, y, Color.GREEN)
        for (y in 91..101) for (x in 50..150) bmp.setPixel(x, y, Color.GREEN)
        val parent = Zone(40, 40, 160, 111, 0.42f, floatArrayOf(0f, 1f, 0f))
        val split = Zones.splitZone(bmp, parent)
        assertNotNull(split)
        val (top, bottom) = split!!
        // Top half: marks 50..60 → bbox + 10 pad.
        assertEquals(40, top.x0); assertEquals(40, top.y0)
        assertEquals(160, top.x1); assertEquals(70, top.y1)
        // Bottom half: marks 91..101 → bbox + 10 pad.
        assertEquals(40, bottom.x0); assertEquals(81, bottom.y0)
        assertEquals(160, bottom.x1); assertEquals(111, bottom.y1)
        // Both halves inherit parent's dim and channel mask.
        assertEquals(0.42f, top.dim, 0.001f); assertEquals(0.42f, bottom.dim, 0.001f)
        assertEquals(1f, top.channels[1], 0.001f); assertEquals(1f, bottom.channels[1], 0.001f)
        assertEquals(0f, top.channels[0], 0.001f); assertEquals(0f, bottom.channels[2], 0.001f)
        // Channel arrays are independent (no aliasing).
        top.channels[0] = 99f
        assertEquals(0f, bottom.channels[0], 0.001f)
    }

    @Test
    fun splitZone_vertical_gap_splits_left_right() {
        val bmp = blank(200, 200)
        // Left blob 50..60, right blob 91..101, columns 61..90 empty.
        for (y in 50..150) for (x in 50..60) bmp.setPixel(x, y, Color.GREEN)
        for (y in 50..150) for (x in 91..101) bmp.setPixel(x, y, Color.GREEN)
        val parent = Zone(40, 40, 111, 160, 0.33f, floatArrayOf(1f, 0f, 0f))
        val split = Zones.splitZone(bmp, parent)
        assertNotNull(split)
        val (left, right) = split!!
        // Left half marks 50..60 → 40..70 with pad.
        assertEquals(40, left.x0); assertEquals(70, left.x1)
        // Right half marks 91..101 → 81..111 with pad.
        assertEquals(81, right.x0); assertEquals(111, right.x1)
        // Y bounds: marks 50..150 + pad → 40..160.
        assertEquals(40, left.y0); assertEquals(160, left.y1)
        assertEquals(40, right.y0); assertEquals(160, right.y1)
        // Inherited dim and channels.
        assertEquals(0.33f, left.dim, 0.001f); assertEquals(0.33f, right.dim, 0.001f)
        assertEquals(1f, left.channels[0], 0.001f); assertEquals(1f, right.channels[0], 0.001f)
    }

    @Test
    fun splitZone_returns_null_when_no_clear_gap() {
        val bmp = blank(200, 200)
        for (y in 50..70) for (x in 50..70) bmp.setPixel(x, y, Color.GREEN)
        val parent = Zone(40, 40, 80, 80, 0.1f, floatArrayOf(0f, 1f, 0f))
        assertNull(Zones.splitZone(bmp, parent))
    }

    // ---- maxBucketPresent (Phase 9A.2) ----

    @Test
    fun maxBucketPresent_single_channel_finds_highest_severity_in_zone() {
        val bmp = blank(200, 200)
        // G channel: paint sev-1 (63) and sev-3 (191) marks within the zone.
        for (y in 50..55) for (x in 50..55) bmp.setPixel(x, y, Color.argb(255, 0, 63, 0))
        for (y in 60..65) for (x in 50..55) bmp.setPixel(x, y, Color.argb(255, 0, 191, 0))
        val zone = Zone(40, 40, 65, 75, 0.1f, floatArrayOf(0f, 1f, 0f))
        val mbp = Zones.maxBucketPresent(bmp, zone)
        assertEquals(0, mbp[0])  // R has no marks → bucket 0
        assertEquals(3, mbp[1])  // G max = sev-3 (wearValue 191 → bucket 3)
        assertEquals(0, mbp[2])  // B has no marks → bucket 0
    }

    @Test
    fun maxBucketPresent_unmarked_zone_returns_zero_per_channel() {
        val bmp = blank(200, 200)
        val zone = Zone(40, 40, 80, 80, 0.1f, floatArrayOf(1f, 0f, 0f))
        val mbp = Zones.maxBucketPresent(bmp, zone)
        assertArrayEquals(intArrayOf(0, 0, 0), mbp)
    }

    @Test
    fun maxBucketPresent_multichannel_zone_reports_per_channel_max() {
        val bmp = blank(200, 200)
        // Mixed: R sev-2 (127), G sev-4 (255), B nothing.
        for (y in 50..60) for (x in 50..60) bmp.setPixel(x, y, Color.argb(255, 127, 255, 0))
        // maxBucketPresent reads pixel data within the zone's bbox and ignores zone.channels;
        // with single-channel zones the per-channel scan still reports per-channel max correctly.
        val zone = Zone(40, 40, 70, 70, 0.1f, floatArrayOf(1f, 0f, 0f))
        val mbp = Zones.maxBucketPresent(bmp, zone)
        assertEquals(2, mbp[0])  // R sev-2
        assertEquals(4, mbp[1])  // G sev-4
        assertEquals(0, mbp[2])  // B unmarked
    }
}
