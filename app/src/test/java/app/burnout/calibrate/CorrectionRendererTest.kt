package app.burnout.calibrate

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CorrectionRendererTest {

    private fun zone(channel: Int, x0: Int = 0): Zone {
        val ch = floatArrayOf(0f, 0f, 0f); ch[channel] = 1f
        return Zone(x0, 0, x0 + 10, 10, 0.1f, ch)
    }

    @Test
    fun filterByChannel_w_returns_all_zones_unchanged() {
        val zones = listOf(zone(0, 0), zone(1, 20), zone(2, 40))
        val filtered = CorrectionRenderer.filterByChannel(zones, 3)
        assertEquals(zones, filtered)
    }

    @Test
    fun filterByChannel_r_returns_only_r_zones() {
        val r = zone(0, 0); val g = zone(1, 20); val b = zone(2, 40); val r2 = zone(0, 60)
        val filtered = CorrectionRenderer.filterByChannel(listOf(r, g, b, r2), 0)
        assertEquals(listOf(r, r2), filtered)
    }

    @Test
    fun filterByChannel_invalid_channel_returns_all_unchanged() {
        // Defensive: out-of-range index treated like W (no filter).
        val zones = listOf(zone(0, 0), zone(1, 20))
        assertEquals(zones, CorrectionRenderer.filterByChannel(zones, -1))
        assertEquals(zones, CorrectionRenderer.filterByChannel(zones, 4))
    }

    @Test
    fun filterByChannel_empty_list_returns_empty() {
        assertEquals(emptyList<Zone>(), CorrectionRenderer.filterByChannel(emptyList(), 0))
        assertEquals(emptyList<Zone>(), CorrectionRenderer.filterByChannel(emptyList(), 3))
    }
}
