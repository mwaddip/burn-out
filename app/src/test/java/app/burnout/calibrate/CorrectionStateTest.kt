package app.burnout.calibrate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CorrectionStateTest {

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
    fun toJson_roundtrip_preserves_zones_and_globals() {
        val s = CorrectionState()
        s.maskWidth = 1440; s.maskHeight = 3168
        s.zones = listOf(
            Zone(100, 200, 300, 400, 0.195f),
            Zone(150, 1000, 350, 1300, 0.085f),
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
        orig.zones = listOf(Zone(0, 0, 10, 10, 0.1f, floatArrayOf(1f, 0f, 1f)))
        val restored = CorrectionState()
        restored.fromJson(orig.toJson())
        assertEquals(1f, restored.zones[0].channels[0], 0.001f)
        assertEquals(0f, restored.zones[0].channels[1], 0.001f)
        assertEquals(1f, restored.zones[0].channels[2], 0.001f)
    }

    @Test
    fun fromJson_legacy_preset_without_channels_defaults_all_ones() {
        val legacy = """{"mask_w":100,"mask_h":100,"falloff_radius_px":300,"global_dim":0.08,
            "zones":[{"x0":0,"y0":0,"x1":10,"y1":10,"dim":0.1}]}""".trimIndent()
        val s = CorrectionState()
        s.fromJson(legacy)
        assertEquals(1, s.zones.size)
        assertEquals(1f, s.zones[0].channels[0], 0.001f)
        assertEquals(1f, s.zones[0].channels[1], 0.001f)
        assertEquals(1f, s.zones[0].channels[2], 0.001f)
    }

    @Test
    fun fromJson_roundtrip_restores_state() {
        val original = CorrectionState()
        original.maskWidth = 1440; original.maskHeight = 3168
        original.zones = listOf(Zone(100, 200, 300, 400, 0.195f))
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

    @Test
    fun fromJson_legacy_scalar_global_dim_broadcasts_to_all_channels() {
        val legacy = """{"mask_w":100,"mask_h":100,"falloff_radius_px":300,"global_dim":0.05,
            "zones":[]}""".trimIndent()
        val s = CorrectionState()
        s.fromJson(legacy)
        assertEquals(0.05f, s.globalDim[0], 0.001f)
        assertEquals(0.05f, s.globalDim[1], 0.001f)
        assertEquals(0.05f, s.globalDim[2], 0.001f)
    }
}
