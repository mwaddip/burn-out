package app.burnout.mark

import kotlin.test.Test
import kotlin.test.assertEquals

class DraggableOverlayStateTest {

    @Test
    fun `serialize and deserialize position pair`() {
        val s = DraggableOverlay.encodePosition(123, 456)
        assertEquals(123 to 456, DraggableOverlay.decodePosition(s))
    }

    @Test
    fun `decodePosition returns null on garbage`() {
        assertEquals(null, DraggableOverlay.decodePosition("garbage"))
        assertEquals(null, DraggableOverlay.decodePosition(""))
        assertEquals(null, DraggableOverlay.decodePosition("12,abc"))
    }

    @Test
    fun `decodePosition handles negative coords`() {
        assertEquals(-5 to 10, DraggableOverlay.decodePosition("-5,10"))
    }
}
