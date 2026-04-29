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
        s.zones = listOf(Zone(100, 200, 300, 400, 0.195f))
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
            Zone(i * 100, i * 200, i * 100 + 50, i * 200 + 50, 0.05f + i * 0.01f)
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
    fun thirteenth_zone_in_json_is_dropped() {
        val obj = JSONObject()
        obj.put("mask_w", 100).put("mask_h", 100)
            .put("falloff_radius_px", 0).put("global_dim", 0.0)
        val zarr = org.json.JSONArray()
        for (i in 0 until 13) {
            zarr.put(JSONObject()
                .put("x0", i).put("y0", i).put("x1", i + 1).put("y1", i + 1).put("dim", 0.1))
        }
        obj.put("zones", zarr)
        val state = CorrectionState()
        state.fromJson(obj.toString())
        assertEquals(12, state.zones.size)
    }
}
