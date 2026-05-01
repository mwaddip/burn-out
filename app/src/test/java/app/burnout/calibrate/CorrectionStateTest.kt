package app.burnout.calibrate

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CorrectionStateTest {

    private fun rZone(x0: Int = 0, y0: Int = 0, x1: Int = 10, y1: Int = 10, dim: Float = 0.1f) =
        Zone(x0, y0, x1, y1, dim, floatArrayOf(1f, 0f, 0f))

    @Test
    fun defaults_match_spec_section_5_1() {
        val s = CorrectionState()
        assertEquals(300f, s.falloffRadiusPx, 0.001f)
        assertEquals("R baseline", 0.08f, s.globalDim[0], 0.001f)
        assertEquals("G baseline", 0.08f, s.globalDim[1], 0.001f)
        assertEquals("B baseline", 0.08f, s.globalDim[2], 0.001f)
        assertTrue("zones list defaults empty", s.zones.isEmpty())
        assertEquals(0, s.selectedZone)
    }

    @Test
    fun zone_cap_is_twelve() {
        assertEquals(12, CorrectionState.MAX_ZONES)
    }

    @Test
    fun activeChannel_defaults_to_w() {
        val s = CorrectionState()
        assertEquals(3, s.activeChannel)  // 0=R, 1=G, 2=B, 3=W
    }

    @Test
    fun activeChannel_does_not_persist_to_json() {
        val s = CorrectionState()
        s.maskWidth = 100; s.maskHeight = 100
        s.activeChannel = 1  // G
        val parsed = JSONObject(s.toJson())
        assertFalse("activeChannel must not be in JSON", parsed.has("active_channel"))
    }

    @Test
    fun toJson_roundtrip_preserves_zones_and_globals() {
        val s = CorrectionState()
        s.maskWidth = 1440; s.maskHeight = 3168
        s.zones = listOf(
            Zone(100, 200, 300, 400, 0.195f, floatArrayOf(1f, 0f, 0f)),
            Zone(150, 1000, 350, 1300, 0.085f, floatArrayOf(0f, 1f, 0f)),
        )
        s.falloffRadiusPx = 250f
        s.globalDim = floatArrayOf(0.07f, 0.05f, 0f)
        s.maxWearR = 0.0f; s.maxWearG = 0.5f; s.maxWearB = 0.0f

        val json = s.toJson()
        val parsed = JSONObject(json)
        assertEquals(1440, parsed.getInt("mask_w"))
        assertEquals(3168, parsed.getInt("mask_h"))
        assertEquals(250, parsed.getInt("falloff_radius_px"))
        val gd = parsed.getJSONArray("global_dim")
        assertEquals(0.07, gd.getDouble(0), 0.001)
        assertEquals(0.05, gd.getDouble(1), 0.001)
        assertEquals(0.0,  gd.getDouble(2), 0.001)
        assertEquals(2, parsed.getJSONArray("zones").length())
        assertTrue("must be locale-US formatted", json.contains("0.07"))
        assertTrue("must not contain comma decimal", !json.contains("0,0700"))
    }

    @Test
    fun toJson_includes_per_zone_channels() {
        val s = CorrectionState()
        s.maskWidth = 100; s.maskHeight = 100
        s.zones = listOf(Zone(0, 0, 10, 10, 0.1f, floatArrayOf(0f, 1f, 0f)))
        val parsed = JSONObject(s.toJson())
        val z0 = parsed.getJSONArray("zones").getJSONObject(0)
        val ch = z0.getJSONArray("channels")
        assertEquals(0, ch.getInt(0))
        assertEquals(1, ch.getInt(1))
        assertEquals(0, ch.getInt(2))
    }

    @Test
    fun fromJson_with_channels_roundtrips() {
        val orig = CorrectionState()
        orig.maskWidth = 100; orig.maskHeight = 100
        orig.zones = listOf(Zone(0, 0, 10, 10, 0.1f, floatArrayOf(1f, 0f, 0f)))
        val restored = CorrectionState()
        restored.fromJson(orig.toJson())
        assertEquals(1f, restored.zones[0].channels[0], 0.001f)
        assertEquals(0f, restored.zones[0].channels[1], 0.001f)
        assertEquals(0f, restored.zones[0].channels[2], 0.001f)
    }

    @Test
    fun fromJson_roundtrip_restores_state() {
        val original = CorrectionState()
        original.maskWidth = 1440; original.maskHeight = 3168
        original.zones = listOf(rZone(100, 200, 300, 400, 0.195f))
        original.falloffRadiusPx = 250f
        original.globalDim = floatArrayOf(0.07f, 0.05f, 0.02f)

        val restored = CorrectionState()
        restored.fromJson(original.toJson())

        assertEquals(1440, restored.maskWidth)
        assertEquals(3168, restored.maskHeight)
        assertEquals(1, restored.zones.size)
        assertEquals(100, restored.zones[0].x0)
        assertEquals(0.195f, restored.zones[0].dim, 0.001f)
        assertEquals(250f, restored.falloffRadiusPx, 0.001f)
        assertEquals(0.07f, restored.globalDim[0], 0.001f)
        assertEquals(0.05f, restored.globalDim[1], 0.001f)
        assertEquals(0.02f, restored.globalDim[2], 0.001f)
    }

    // ---- Strict-parse failures ----

    @Test(expected = JSONException::class)
    fun fromJson_throws_on_missing_global_dim() {
        val bad = """{"mask_w":100,"mask_h":100,"falloff_radius_px":300,"zones":[],
            "max_wear_per_channel":{"r":0,"g":0,"b":0},
            "severity_gaps":[25,25,25,25],"severity_locks":[false,false,false,false]}""".trimIndent()
        CorrectionState().fromJson(bad)
    }

    @Test(expected = JSONException::class)
    fun fromJson_throws_on_zone_missing_channels() {
        val bad = """{"mask_w":100,"mask_h":100,"falloff_radius_px":300,
            "global_dim":[0.08,0.08,0.08],
            "zones":[{"x0":0,"y0":0,"x1":10,"y1":10,"dim":0.1}],
            "max_wear_per_channel":{"r":0,"g":0,"b":0},
            "severity_gaps":[25,25,25,25],"severity_locks":[false,false,false,false]}""".trimIndent()
        CorrectionState().fromJson(bad)
    }

    @Test(expected = IllegalStateException::class)
    fun fromJson_throws_on_multichannel_zone() {
        val bad = """{"mask_w":100,"mask_h":100,"falloff_radius_px":300,
            "global_dim":[0.08,0.08,0.08],
            "zones":[{"x0":0,"y0":0,"x1":10,"y1":10,"dim":0.1,"channels":[1,1,0]}],
            "max_wear_per_channel":{"r":0,"g":0,"b":0},
            "severity_gaps":[25,25,25,25],"severity_locks":[false,false,false,false]}""".trimIndent()
        CorrectionState().fromJson(bad)
    }

    @Test(expected = IllegalStateException::class)
    fun fromJson_throws_on_invalid_severity_sum() {
        val bad = """{"mask_w":100,"mask_h":100,"falloff_radius_px":300,
            "global_dim":[0.08,0.08,0.08],"zones":[],
            "max_wear_per_channel":{"r":0,"g":0,"b":0},
            "severity_gaps":[40,40,40,40],"severity_locks":[false,false,false,false]}""".trimIndent()
        CorrectionState().fromJson(bad)
    }

    // ---- Severity-aware tuning ----

    @Test
    fun severity_defaults_match_spec() {
        val s = CorrectionState()
        assertArrayEquals(intArrayOf(25, 25, 25, 25), s.severityGaps)
        assertArrayEquals(booleanArrayOf(false, false, false, false), s.severityLocks)
    }

    @Test
    fun sevWearFraction_default_is_linear() {
        val s = CorrectionState()
        val f = s.sevWearFraction()
        assertEquals(5, f.size)
        assertEquals(0.0f,  f[0], 0.0001f)
        assertEquals(0.25f, f[1], 0.0001f)
        assertEquals(0.50f, f[2], 0.0001f)
        assertEquals(0.75f, f[3], 0.0001f)
        assertEquals(1.0f,  f[4], 0.0001f)
    }

    @Test
    fun sevWearFraction_skewed_distribution() {
        val s = CorrectionState()
        s.severityGaps = intArrayOf(10, 10, 20, 60)
        val f = s.sevWearFraction()
        assertEquals(0.00f, f[0], 0.0001f)
        assertEquals(0.10f, f[1], 0.0001f)
        assertEquals(0.20f, f[2], 0.0001f)
        assertEquals(0.40f, f[3], 0.0001f)
        assertEquals(1.00f, f[4], 0.0001f)
    }

    @Test
    fun severity_fields_roundtrip_through_json() {
        val s = CorrectionState()
        s.maskWidth = 100; s.maskHeight = 100
        s.severityGaps = intArrayOf(10, 20, 30, 40)
        s.severityLocks = booleanArrayOf(false, true, false, true)
        val restored = CorrectionState()
        restored.fromJson(s.toJson())
        assertArrayEquals(intArrayOf(10, 20, 30, 40), restored.severityGaps)
        assertArrayEquals(booleanArrayOf(false, true, false, true), restored.severityLocks)
    }
}
