package app.burnout.calibrate

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MaskServerTest {

    private val noopHandler = object : MaskServer.Handler {
        override fun snapshotMaskBitmap(): Bitmap? = null
        override fun snapshotMeta() = MaskServer.MetaSnapshot(0, 0, emptyList(), 0, 0f, 0f, 0f, 0f, 0f)
        override fun applyPixelEdits(edits: List<MaskServer.PixelEdit>) {}
        override fun setZoneDim(index: Int, value: Float) {}
        override fun selectZone(index: Int) {}
        override fun setFalloffRadiusPx(value: Float) {}
        override fun setGlobalDim(value: Float) {}
        override fun saveAll(): String? = null
    }

    @Test
    fun default_socket_name_is_burnout_mask() {
        val server = MaskServer(handler = noopHandler)
        assertEquals("burnout-mask", server.socketName)
    }

    @Test
    fun custom_socket_name_is_honored() {
        val server = MaskServer(socketName = "test-socket", handler = noopHandler)
        assertEquals("test-socket", server.socketName)
    }

    @Test
    fun buildMetaJson_emits_per_zone_channels_single_channel() {
        val gOnly = Zone(10, 20, 100, 200, 0.15f, floatArrayOf(0f, 1f, 0f))
        val snapshot = MaskServer.MetaSnapshot(
            maskW = 1440, maskH = 3216,
            zones = listOf(gOnly), selectedZone = 0,
            maxWearR = 0f, maxWearG = 1f, maxWearB = 0f,
            falloffRadiusPx = 300f, globalDim = 0.08f,
        )
        val zone0 = MaskServer.buildMetaJson(snapshot).getJSONArray("zones").getJSONObject(0)
        val ch = zone0.getJSONArray("channels")
        assertEquals(0, ch.getInt(0))
        assertEquals(1, ch.getInt(1))
        assertEquals(0, ch.getInt(2))
    }

    @Test
    fun buildMetaJson_emits_top_level_meta_fields() {
        val snapshot = MaskServer.MetaSnapshot(
            maskW = 1440, maskH = 3216,
            zones = emptyList(), selectedZone = 2,
            maxWearR = 0.5f, maxWearG = 0.7f, maxWearB = 0.2f,
            falloffRadiusPx = 250f, globalDim = 0.12f,
        )
        val obj = MaskServer.buildMetaJson(snapshot)
        assertEquals(true, obj.getBoolean("ok"))
        assertEquals(1440, obj.getInt("mask_w"))
        assertEquals(3216, obj.getInt("mask_h"))
        assertEquals(2, obj.getInt("selected_zone"))
        assertEquals(250.0, obj.getDouble("falloff_radius_px"), 0.001)
        assertEquals(0.12, obj.getDouble("global_dim"), 0.001)
        assertEquals(0, obj.getJSONArray("zones").length())
    }
}
