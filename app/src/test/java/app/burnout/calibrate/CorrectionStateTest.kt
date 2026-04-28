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
        assertEquals(0.08f, s.globalDim, 0.001f)
        assertTrue("zones list defaults empty", s.zones.isEmpty())
        assertEquals(0, s.selectedZone)
    }

    @Test
    fun zone_cap_is_eight() {
        assertEquals(8, CorrectionState.MAX_ZONES)
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
        s.globalDim = 0.07f
        s.maxWearR = 0.0f; s.maxWearG = 0.5f; s.maxWearB = 0.0f

        val json = s.toJson()
        val parsed = JSONObject(json)
        assertEquals(1440, parsed.getInt("mask_w"))
        assertEquals(3168, parsed.getInt("mask_h"))
        assertEquals(250, parsed.getInt("falloff_radius_px"))
        assertEquals(0.07, parsed.getDouble("global_dim"), 0.001)
        assertEquals(2, parsed.getJSONArray("zones").length())
        // Zones JSON uses Locale.US — no comma decimals.
        assertTrue("must be locale-US formatted", json.contains("0.07"))
        assertTrue("must not contain comma decimal", !json.contains("0,0700"))
    }

    @Test
    fun fromJson_roundtrip_restores_state() {
        val original = CorrectionState()
        original.maskWidth = 1440; original.maskHeight = 3168
        original.zones = listOf(Zone(100, 200, 300, 400, 0.195f))
        original.falloffRadiusPx = 250f
        original.globalDim = 0.07f

        val restored = CorrectionState()
        restored.fromJson(original.toJson())

        assertEquals(1440, restored.maskWidth)
        assertEquals(3168, restored.maskHeight)
        assertEquals(1, restored.zones.size)
        assertEquals(100, restored.zones[0].x0)
        assertEquals(0.195f, restored.zones[0].dim, 0.001f)
        assertEquals(250f, restored.falloffRadiusPx, 0.001f)
        assertEquals(0.07f, restored.globalDim, 0.001f)
    }
}
