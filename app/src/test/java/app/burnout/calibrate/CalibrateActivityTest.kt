package app.burnout.calibrate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalibrateActivityTest {

    @Test
    fun saveLive_payload_uses_locale_us_decimals() {
        val s = CorrectionState()
        s.maskWidth = 1440; s.maskHeight = 3168
        s.zones = listOf(Zone(100, 200, 300, 400, 0.195f, floatArrayOf(1f, 0f, 0f)))
        s.falloffRadiusPx = 250f
        s.globalDim = floatArrayOf(0.07f, 0.07f, 0.07f)
        val json = s.toJson()
        assertTrue("Locale.US decimals expected", json.contains("0.07"))
        assertTrue("no comma-decimal", !json.contains("0,0700"))
    }

    @Test
    fun rootIO_command_uses_app_burnout_filesDir_path() {
        val cmd = RootIO.buildCommitCommand(
            stagingDir = "/data/data/app.burnout/files/staging",
            reloadEpoch = 1234567890L,
        )
        assertTrue(cmd.startsWith("mkdir -p /data/burnout"))
        assertTrue(cmd.endsWith("setprop sys.burnout.reload 1234567890"))
        assertTrue(cmd.contains("chcon u:object_r:burnout_data_file:s0"))
    }

    @Test
    fun preset_autosave_round_trip_preserves_zones() {
        val orig = CorrectionState()
        orig.maskWidth = 1440; orig.maskHeight = 3168
        orig.zones = (0 until 12).map { i ->
            // Cycle channels R/G/B per index so all three appear; bboxes don't overlap (different x).
            val ch = floatArrayOf(0f, 0f, 0f); ch[i % 3] = 1f
            Zone(i * 100, i * 200, i * 100 + 50, i * 200 + 50, 0.05f + i * 0.01f, ch)
        }
        orig.falloffRadiusPx = 275f
        orig.globalDim = floatArrayOf(0.09f, 0.06f, 0.03f)

        val json = orig.toJson()
        val restored = CorrectionState()
        restored.fromJson(json)

        assertEquals(12, restored.zones.size)
        assertEquals(0.05f + 11 * 0.01f, restored.zones[11].dim, 0.001f)
        assertEquals(275f, restored.falloffRadiusPx, 0.01f)
        assertEquals(0.09f, restored.globalDim[0], 0.001f)
        assertEquals(0.06f, restored.globalDim[1], 0.001f)
        assertEquals(0.03f, restored.globalDim[2], 0.001f)
    }

    @Test
    fun activeChannel_filter_in_r_mode_yields_only_r_zones() {
        val state = CorrectionState()
        state.zones = listOf(
            Zone(0, 0, 10, 10, 0.1f, floatArrayOf(1f, 0f, 0f)),    // R
            Zone(20, 0, 30, 10, 0.1f, floatArrayOf(0f, 1f, 0f)),   // G
            Zone(40, 0, 50, 10, 0.1f, floatArrayOf(0f, 0f, 1f)),   // B
            Zone(60, 0, 70, 10, 0.1f, floatArrayOf(1f, 0f, 0f)),   // R
        )
        state.activeChannel = 0  // R
        val r = CorrectionRenderer.filterByChannel(state.zones, state.activeChannel)
        assertEquals(2, r.size)
        assertEquals(0, r[0].x0); assertEquals(60, r[1].x0)
    }

    @Test
    fun activeChannel_filter_in_w_mode_returns_all_zones() {
        val state = CorrectionState()
        state.zones = listOf(
            Zone(0, 0, 10, 10, 0.1f, floatArrayOf(1f, 0f, 0f)),
            Zone(20, 0, 30, 10, 0.1f, floatArrayOf(0f, 1f, 0f)),
        )
        state.activeChannel = 3  // W
        val all = CorrectionRenderer.filterByChannel(state.zones, state.activeChannel)
        assertEquals(2, all.size)
    }

    @Test
    fun thirteenth_zone_in_json_is_dropped() {
        val obj = JSONObject()
        obj.put("mask_w", 100).put("mask_h", 100)
            .put("falloff_radius_px", 0)
            .put("global_dim", org.json.JSONArray().put(0.0).put(0.0).put(0.0))
            .put("max_wear_per_channel", JSONObject().put("r", 0).put("g", 0).put("b", 0))
            .put("severity_gaps", org.json.JSONArray().put(25).put(25).put(25).put(25))
            .put("severity_locks", org.json.JSONArray().put(false).put(false).put(false).put(false))
        val zarr = org.json.JSONArray()
        for (i in 0 until 13) {
            zarr.put(JSONObject()
                .put("x0", i).put("y0", i).put("x1", i + 1).put("y1", i + 1).put("dim", 0.1)
                .put("channels", org.json.JSONArray().put(1).put(0).put(0)))
        }
        obj.put("zones", zarr)
        val state = CorrectionState()
        state.fromJson(obj.toString())
        assertEquals(12, state.zones.size)
    }
}
