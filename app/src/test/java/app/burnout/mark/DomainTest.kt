package app.burnout.mark

import kotlin.test.Test
import kotlin.test.assertEquals

class DomainTest {

    @Test
    fun `Severity stores 0-4 only`() {
        assertEquals(0, Severity(0).level)
        assertEquals(4, Severity(4).level)
        assertEquals(0, Severity(-1).level)
        assertEquals(4, Severity(5).level)
    }

    @Test
    fun `Severity to pixel value uses top-of-bucket`() {
        assertEquals(0,   Severity(0).toPixelValue())
        assertEquals(63,  Severity(1).toPixelValue())
        assertEquals(127, Severity(2).toPixelValue())
        assertEquals(191, Severity(3).toPixelValue())
        assertEquals(255, Severity(4).toPixelValue())
    }

    @Test
    fun `Severity from pixel value bucket boundaries`() {
        // bucket 0: 0
        assertEquals(Severity(0), Severity.fromPixelValue(0))
        // bucket 1: 1-63
        assertEquals(Severity(1), Severity.fromPixelValue(1))
        assertEquals(Severity(1), Severity.fromPixelValue(63))
        // bucket 2: 64-127
        assertEquals(Severity(2), Severity.fromPixelValue(64))
        assertEquals(Severity(2), Severity.fromPixelValue(127))
        // bucket 3: 128-191
        assertEquals(Severity(3), Severity.fromPixelValue(128))
        assertEquals(Severity(3), Severity.fromPixelValue(191))
        // bucket 4: 192-255
        assertEquals(Severity(4), Severity.fromPixelValue(192))
        assertEquals(Severity(4), Severity.fromPixelValue(255))
    }

    @Test
    fun `Severity round-trips through pixel value (top-of-bucket)`() {
        for (sev in 0..4) {
            val s = Severity(sev)
            assertEquals(s, Severity.fromPixelValue(s.toPixelValue()))
        }
    }

    @Test
    fun `Severity cycle goes 1 to 4 to 0 to 1`() {
        assertEquals(Severity(2), Severity(1).cycle())
        assertEquals(Severity(3), Severity(2).cycle())
        assertEquals(Severity(4), Severity(3).cycle())
        assertEquals(Severity(0), Severity(4).cycle())
        assertEquals(Severity(1), Severity(0).cycle())
    }

    @Test
    fun `Channel has R G B W members`() {
        assertEquals(4, Channel.entries.size)
        assertEquals(Channel.R, Channel.entries[0])
        assertEquals(Channel.W, Channel.entries[3])
    }

    @Test
    fun `Channel cycle goes R G B W R`() {
        assertEquals(Channel.G, Channel.R.cycle())
        assertEquals(Channel.B, Channel.G.cycle())
        assertEquals(Channel.W, Channel.B.cycle())
        assertEquals(Channel.R, Channel.W.cycle())
    }

    @Test
    fun `Channel W is survey-only (not a marking channel)`() {
        assertEquals(false, Channel.W.isMarkingChannel)
        assertEquals(true,  Channel.R.isMarkingChannel)
        assertEquals(true,  Channel.G.isMarkingChannel)
        assertEquals(true,  Channel.B.isMarkingChannel)
    }
}
