package app.burnout.mark

import kotlin.test.Test
import kotlin.test.assertEquals

class TestPatternTest {

    @Test
    fun `pattern colors are pure full-channel ARGB`() {
        assertEquals(0xFFFF0000.toInt(), TestPattern.forChannel(Channel.R).backgroundColor)
        assertEquals(0xFF00FF00.toInt(), TestPattern.forChannel(Channel.G).backgroundColor)
        assertEquals(0xFF0000FF.toInt(), TestPattern.forChannel(Channel.B).backgroundColor)
        assertEquals(0xFFFFFFFF.toInt(), TestPattern.forChannel(Channel.W).backgroundColor)
    }

    @Test
    fun `cursor color is the bitwise-inverse of background, with full alpha`() {
        assertEquals(0xFF00FFFF.toInt(), TestPattern.forChannel(Channel.R).cursorColor)
        assertEquals(0xFFFF00FF.toInt(), TestPattern.forChannel(Channel.G).cursorColor)
        assertEquals(0xFFFFFF00.toInt(), TestPattern.forChannel(Channel.B).cursorColor)
        assertEquals(0xFF000000.toInt(), TestPattern.forChannel(Channel.W).cursorColor)
    }

    @Test
    fun `mark color is white for all marking channels`() {
        assertEquals(0xFFFFFFFF.toInt(), TestPattern.forChannel(Channel.R).markColor)
        assertEquals(0xFFFFFFFF.toInt(), TestPattern.forChannel(Channel.G).markColor)
        assertEquals(0xFFFFFFFF.toInt(), TestPattern.forChannel(Channel.B).markColor)
    }
}
