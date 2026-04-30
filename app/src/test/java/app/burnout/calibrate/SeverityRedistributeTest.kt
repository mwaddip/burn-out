package app.burnout.calibrate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SeverityRedistributeTest {

    @Test
    fun all_unlocked_proportional_shrink() {
        // From [25,25,25,25] increase index 0 by 12 → others shrink by 4 each.
        val gaps = intArrayOf(25, 25, 25, 25)
        CorrectionState.redistributeGaps(
            gaps, locks = booleanArrayOf(false, false, false, false),
            changedIdx = 0, delta = 12)
        assertEquals(100, gaps.sum())
        assertEquals(37, gaps[0])
        assertEquals(21, gaps[1])
        assertEquals(21, gaps[2])
        assertEquals(21, gaps[3])
    }

    @Test
    fun one_locked_three_redistribute() {
        // Lock index 1; from [25,25,25,25] increase index 0 by 9 → indices 2 and 3 absorb -9 split.
        val gaps = intArrayOf(25, 25, 25, 25)
        CorrectionState.redistributeGaps(
            gaps, locks = booleanArrayOf(false, true, false, false),
            changedIdx = 0, delta = 9)
        assertEquals(100, gaps.sum())
        assertEquals(34, gaps[0])
        assertEquals(25, gaps[1])  // locked, unchanged
        // Indices 2 and 3 share the -9 absorption.
        assertEquals(41, gaps[2] + gaps[3])
    }

    @Test
    fun two_locked_two_redistribute() {
        val gaps = intArrayOf(25, 25, 25, 25)
        CorrectionState.redistributeGaps(
            gaps, locks = booleanArrayOf(false, true, true, false),
            changedIdx = 0, delta = 10)
        assertEquals(100, gaps.sum())
        assertEquals(35, gaps[0])
        assertEquals(25, gaps[1])
        assertEquals(25, gaps[2])
        assertEquals(15, gaps[3])
    }

    @Test
    fun clamp_at_zero_floor() {
        // Heavy positive delta on index 0 with others at 5/5/5 → others go to 0, K capped.
        val gaps = intArrayOf(85, 5, 5, 5)
        CorrectionState.redistributeGaps(
            gaps, locks = booleanArrayOf(false, false, false, false),
            changedIdx = 0, delta = 50)
        assertEquals(100, gaps.sum())
        assertEquals(100, gaps[0])  // capped at 100; user wanted +50 but only +15 was free
        assertEquals(0, gaps[1])
        assertEquals(0, gaps[2])
        assertEquals(0, gaps[3])
    }

    @Test
    fun clamp_at_hundred_ceiling() {
        // Negative delta on index 0 with others at 30/30/30 → ok.
        val gaps = intArrayOf(10, 30, 30, 30)
        CorrectionState.redistributeGaps(
            gaps, locks = booleanArrayOf(false, false, false, false),
            changedIdx = 0, delta = -3)
        assertEquals(100, gaps.sum())
        assertEquals(7, gaps[0])
        // Others grow proportionally — each gains 1 (3/3 = 1).
        assertEquals(31, gaps[1])
        assertEquals(31, gaps[2])
        assertEquals(31, gaps[3])
    }

    @Test
    fun zero_delta_is_noop() {
        val gaps = intArrayOf(40, 30, 20, 10)
        CorrectionState.redistributeGaps(
            gaps, locks = booleanArrayOf(false, false, false, false),
            changedIdx = 2, delta = 0)
        assertArrayEquals(intArrayOf(40, 30, 20, 10), gaps)
    }

    @Test
    fun sum_invariant_holds_after_random_drags() {
        // Sanity: any sequence of valid drags preserves sum=100.
        val gaps = intArrayOf(25, 25, 25, 25)
        val rng = java.util.Random(42)
        for (i in 0 until 100) {
            val k = rng.nextInt(4)
            val delta = rng.nextInt(11) - 5
            CorrectionState.redistributeGaps(
                gaps, locks = booleanArrayOf(false, false, false, false),
                changedIdx = k, delta = delta)
            assertEquals("after step $i sum was ${gaps.sum()}", 100, gaps.sum())
            for ((idx, v) in gaps.withIndex()) {
                assert(v in 0..100) { "gap[$idx]=$v out of range at step $i" }
            }
        }
    }
}
