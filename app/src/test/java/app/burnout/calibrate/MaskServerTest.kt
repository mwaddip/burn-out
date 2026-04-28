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
}
