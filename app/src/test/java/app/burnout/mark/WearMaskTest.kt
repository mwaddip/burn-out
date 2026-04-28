package app.burnout.mark

import kotlin.test.Test
import kotlin.test.assertEquals

class WearMaskTest {

    @Test
    fun `new mask has all unmarked pixels (severity 0 in all channels)`() {
        val mask = WearMask(width = 100, height = 200)
        for (x in 0 until 100) for (y in 0 until 200) {
            for (channel in listOf(Channel.R, Channel.G, Channel.B)) {
                assertEquals(Severity(0), mask.getSeverity(x, y, channel))
            }
        }
    }

    @Test
    fun `mark sets severity in correct channel only`() {
        val mask = WearMask(10, 10)
        mask.mark(5, 5, Channel.R, Severity(2))
        assertEquals(Severity(2), mask.getSeverity(5, 5, Channel.R))
        assertEquals(Severity(0), mask.getSeverity(5, 5, Channel.G))
        assertEquals(Severity(0), mask.getSeverity(5, 5, Channel.B))
    }

    @Test
    fun `mark on different channels at same pixel are independent`() {
        val mask = WearMask(10, 10)
        mask.mark(3, 4, Channel.R, Severity(1))
        mask.mark(3, 4, Channel.G, Severity(3))
        mask.mark(3, 4, Channel.B, Severity(4))
        assertEquals(Severity(1), mask.getSeverity(3, 4, Channel.R))
        assertEquals(Severity(3), mask.getSeverity(3, 4, Channel.G))
        assertEquals(Severity(4), mask.getSeverity(3, 4, Channel.B))
    }

    @Test
    fun `mark with W channel throws because W is not a marking channel`() {
        val mask = WearMask(10, 10)
        try {
            mask.mark(0, 0, Channel.W, Severity(1))
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `mark out of bounds is silently ignored`() {
        val mask = WearMask(10, 10)
        mask.mark(-1, 5, Channel.R, Severity(2))
        mask.mark(5, 100, Channel.G, Severity(2))
    }

    @Test
    fun `clearChannel resets one channel without affecting others`() {
        val mask = WearMask(10, 10)
        mask.mark(2, 2, Channel.R, Severity(2))
        mask.mark(2, 2, Channel.G, Severity(3))
        mask.clearChannel(Channel.R)
        assertEquals(Severity(0), mask.getSeverity(2, 2, Channel.R))
        assertEquals(Severity(3), mask.getSeverity(2, 2, Channel.G))
    }

    @Test
    fun `isMarked returns true if any channel is non-zero`() {
        val mask = WearMask(10, 10)
        assertEquals(false, mask.isMarked(5, 5))
        mask.mark(5, 5, Channel.B, Severity(1))
        assertEquals(true, mask.isMarked(5, 5))
    }

    @Test
    fun `cycleAtPixel cycles severity 0 to 4 to 0 in given channel`() {
        val mask = WearMask(10, 10)
        mask.cycleAtPixel(5, 5, Channel.R)
        assertEquals(Severity(1), mask.getSeverity(5, 5, Channel.R))
        repeat(3) { mask.cycleAtPixel(5, 5, Channel.R) }
        assertEquals(Severity(4), mask.getSeverity(5, 5, Channel.R))
        mask.cycleAtPixel(5, 5, Channel.R)
        assertEquals(Severity(0), mask.getSeverity(5, 5, Channel.R))
    }

    @Test
    fun `toPixelArray produces ARGB ints with top-of-bucket channel values`() {
        val mask = WearMask(2, 1)
        mask.mark(0, 0, Channel.R, Severity(1))   // R=63
        mask.mark(1, 0, Channel.G, Severity(4))   // G=255
        val px = mask.toPixelArray()
        assertEquals(2, px.size)
        // R severity 1 = 63 = 0x3F. Pixel = 0xFF<RR><GG><BB> = 0xFF3F0000.
        assertEquals(0xFF3F0000.toInt(), px[0])
        assertEquals(0xFF00FF00.toInt(), px[1])
    }

    @Test
    fun `forEachMarked iterates only marked pixels in given channel`() {
        val mask = WearMask(3, 2)
        mask.mark(0, 0, Channel.R, Severity(1))
        mask.mark(2, 1, Channel.R, Severity(4))
        mask.mark(1, 0, Channel.G, Severity(2))   // different channel

        val redMarks = mutableListOf<Pair<Int, Int>>()
        mask.forEachMarked(Channel.R) { x, y -> redMarks.add(x to y) }

        assertEquals(setOf(0 to 0, 2 to 1), redMarks.toSet())
    }

    @Test
    fun `forEachMarked is a no-op for W channel`() {
        val mask = WearMask(3, 2)
        mask.mark(0, 0, Channel.R, Severity(1))
        val marks = mutableListOf<Pair<Int, Int>>()
        mask.forEachMarked(Channel.W) { x, y -> marks.add(x to y) }
        assertEquals(emptyList(), marks)
    }

    @Test
    fun `fromPixelArray round-trips through top-of-bucket encoding`() {
        val original = WearMask(3, 2)
        original.mark(0, 0, Channel.R, Severity(2))
        original.mark(1, 1, Channel.G, Severity(3))
        original.mark(2, 0, Channel.B, Severity(4))

        val restored = WearMask.fromPixelArray(original.toPixelArray(), 3, 2)
        for (x in 0 until 3) for (y in 0 until 2) {
            for (channel in listOf(Channel.R, Channel.G, Channel.B)) {
                assertEquals(
                    original.getSeverity(x, y, channel),
                    restored.getSeverity(x, y, channel),
                    "mismatch at ($x,$y) $channel"
                )
            }
        }
    }
}
