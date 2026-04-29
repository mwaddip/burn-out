package app.burnout.calibrate

import android.graphics.Bitmap

object Zones {
    const val PAD = 10
    private const val CLUSTER_MERGE_PX = 200
    private const val MIN_CLUSTER_PIXELS = 50
    private const val MAX_ZONES = CorrectionState.MAX_ZONES   // 8

    private data class Component(
        val x0: Int, val y0: Int, val x1: Int, val y1: Int,
        val size: Int,
    )

    fun computeFromMask(
        mask: Bitmap,
        prev: List<Zone> = emptyList(),
        seedDims: FloatArray = floatArrayOf(0.08f, 0.08f, 0.08f),
    ): List<Zone> {
        val w = mask.width; val h = mask.height
        val px = IntArray(w * h); mask.getPixels(px, 0, w, 0, 0, w, h)

        // Run connected-components 3 times — once per channel — so that R-paint and G-paint at the
        // same location yield TWO separate zones (each with a single-channel mask). The dominant-zone
        // logic in the shader handles spatial overlap by picking whichever has higher falloff weight.
        val perChannelClusters = (0..2).flatMap { ch ->
            val comps = connectedComponentsForChannel(px, w, h, ch)
            if (comps.isEmpty()) return@flatMap emptyList<Pair<List<Component>, Int>>()
            clusterComponents(comps, CLUSTER_MERGE_PX)
                .filter { group -> group.sumOf { it.size } >= MIN_CLUSTER_PIXELS }
                .map { group -> group to ch }
        }

        val topClusters = perChannelClusters
            .sortedByDescending { (group, _) -> group.sumOf { c -> c.size } }
            .take(MAX_ZONES)

        return topClusters.map { (group, channelIdx) ->
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE
            var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
            for (c in group) {
                if (c.x0 < x0) x0 = c.x0; if (c.y0 < y0) y0 = c.y0
                if (c.x1 > x1) x1 = c.x1; if (c.y1 > y1) y1 = c.y1
            }
            val bx0 = (x0 - PAD).coerceAtLeast(0)
            val by0 = (y0 - PAD).coerceAtLeast(0)
            val bx1 = (x1 + PAD).coerceAtMost(w - 1)
            val by1 = (y1 + PAD).coerceAtMost(h - 1)
            val cy = (by0 + by1) / 2
            // Carry forward dim from the closest previous zone of the SAME channel, so re-detection
            // doesn't drop the user's per-zone tuning when they re-enter Calibrate.
            val carriedDim = prev
                .filter { it.channels[channelIdx] >= 0.5f }
                .minByOrNull { kotlin.math.abs((it.y0 + it.y1) / 2 - cy) }
                ?.dim ?: seedDims[channelIdx]
            val channels = floatArrayOf(0f, 0f, 0f)
            channels[channelIdx] = 1f
            Zone(bx0, by0, bx1, by1, carriedDim, channels)
        }.sortedBy { (it.y0 + it.y1) / 2 }
    }

    private fun connectedComponentsForChannel(px: IntArray, w: Int, h: Int, channelIdx: Int): List<Component> {
        val shift = when (channelIdx) { 0 -> 16; 1 -> 8; else -> 0 }
        val marked = BooleanArray(px.size)
        for (i in px.indices) {
            marked[i] = ((px[i] shr shift) and 0xFF) != 0
        }
        val visited = BooleanArray(px.size)
        val out = ArrayList<Component>()
        val queue = IntArray(px.size)
        for (seed in px.indices) {
            if (!marked[seed] || visited[seed]) continue
            var qHead = 0; var qTail = 0
            queue[qTail++] = seed; visited[seed] = true
            var x0 = seed % w; var y0 = seed / w; var x1 = x0; var y1 = y0; var count = 0
            while (qHead < qTail) {
                val idx = queue[qHead++]
                count++
                val cx = idx % w; val cy = idx / w
                if (cx < x0) x0 = cx; if (cx > x1) x1 = cx
                if (cy < y0) y0 = cy; if (cy > y1) y1 = cy
                for (dy in -1..1) {
                    val ny = cy + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx
                        if (nx < 0 || nx >= w) continue
                        val nidx = ny * w + nx
                        if (marked[nidx] && !visited[nidx]) {
                            visited[nidx] = true
                            queue[qTail++] = nidx
                        }
                    }
                }
            }
            out.add(Component(x0, y0, x1, y1, count))
        }
        return out
    }

    private fun clusterComponents(comps: List<Component>, threshold: Int): List<List<Component>> {
        val parent = IntArray(comps.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(i: Int, j: Int) { val ri = find(i); val rj = find(j); if (ri != rj) parent[ri] = rj }
        fun near(a: Component, b: Component): Boolean {
            val ax0 = a.x0 - threshold; val ay0 = a.y0 - threshold
            val ax1 = a.x1 + threshold; val ay1 = a.y1 + threshold
            return !(b.x1 < ax0 || b.x0 > ax1 || b.y1 < ay0 || b.y0 > ay1)
        }
        for (i in comps.indices) for (j in i + 1 until comps.size) {
            if (near(comps[i], comps[j])) union(i, j)
        }
        val groups = HashMap<Int, MutableList<Component>>()
        for (i in comps.indices) groups.getOrPut(find(i)) { ArrayList() }.add(comps[i])
        return groups.values.toList()
    }

    // Inclusive index range; or null if no empty run exists.
    private data class GapRange(val start: Int, val end: Int) { val length: Int get() = end - start + 1 }

    // Longest mark-free run BETWEEN the first and last marked row (so the 10px pad above/below
    // a tight bbox doesn't register as a gap). Returns null if no internal gap exists.
    private fun longestEmptyRowsRange(mask: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): GapRange? {
        var firstMarked = -1; var lastMarked = -1
        val empty = BooleanArray(y1 - y0 + 1)
        for (y in y0..y1) {
            var anyMark = false
            for (x in x0..x1) {
                val p = mask.getPixel(x, y)
                if (((p shr 16) and 0xFF) != 0 || ((p shr 8) and 0xFF) != 0 || (p and 0xFF) != 0) {
                    anyMark = true; break
                }
            }
            if (anyMark) {
                if (firstMarked < 0) firstMarked = y
                lastMarked = y
            } else {
                empty[y - y0] = true
            }
        }
        if (firstMarked < 0 || lastMarked - firstMarked < 2) return null
        var bestStart = -1; var bestEnd = -1; var bestLen = 0
        var curStart = -1
        for (y in (firstMarked + 1) until lastMarked) {
            if (empty[y - y0]) {
                if (curStart < 0) curStart = y
                val len = y - curStart + 1
                if (len > bestLen) { bestLen = len; bestStart = curStart; bestEnd = y }
            } else {
                curStart = -1
            }
        }
        return if (bestLen == 0) null else GapRange(bestStart, bestEnd)
    }

    private fun longestEmptyColsRange(mask: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): GapRange? {
        var firstMarked = -1; var lastMarked = -1
        val empty = BooleanArray(x1 - x0 + 1)
        for (x in x0..x1) {
            var anyMark = false
            for (y in y0..y1) {
                val p = mask.getPixel(x, y)
                if (((p shr 16) and 0xFF) != 0 || ((p shr 8) and 0xFF) != 0 || (p and 0xFF) != 0) {
                    anyMark = true; break
                }
            }
            if (anyMark) {
                if (firstMarked < 0) firstMarked = x
                lastMarked = x
            } else {
                empty[x - x0] = true
            }
        }
        if (firstMarked < 0 || lastMarked - firstMarked < 2) return null
        var bestStart = -1; var bestEnd = -1; var bestLen = 0
        var curStart = -1
        for (x in (firstMarked + 1) until lastMarked) {
            if (empty[x - x0]) {
                if (curStart < 0) curStart = x
                val len = x - curStart + 1
                if (len > bestLen) { bestLen = len; bestStart = curStart; bestEnd = x }
            } else {
                curStart = -1
            }
        }
        return if (bestLen == 0) null else GapRange(bestStart, bestEnd)
    }

    fun canSplitZone(mask: Bitmap, zone: Zone, gapThreshold: Int = 10): Boolean {
        val hLen = longestEmptyRowsRange(mask, zone.x0, zone.y0, zone.x1, zone.y1)?.length ?: 0
        val vLen = longestEmptyColsRange(mask, zone.x0, zone.y0, zone.x1, zone.y1)?.length ?: 0
        return maxOf(hLen, vLen) >= gapThreshold
    }

    fun splitZone(mask: Bitmap, zone: Zone, gapThreshold: Int = 10): Pair<Zone, Zone>? {
        val w = mask.width; val h = mask.height
        val hGap = longestEmptyRowsRange(mask, zone.x0, zone.y0, zone.x1, zone.y1)
        val vGap = longestEmptyColsRange(mask, zone.x0, zone.y0, zone.x1, zone.y1)
        val hLen = hGap?.length ?: 0
        val vLen = vGap?.length ?: 0
        if (maxOf(hLen, vLen) < gapThreshold) return null

        val splitH = hLen >= vLen   // tie → horizontal
        val pad = PAD
        val (a, b) = if (splitH) {
            val mid = (hGap!!.start + hGap.end) / 2
            // Top half: zone.x0..zone.x1, zone.y0..mid; refit to marks within.
            val topRefit = refitToMarks(mask, zone.x0, zone.y0, zone.x1, mid, pad, w, h)
            val botRefit = refitToMarks(mask, zone.x0, mid + 1, zone.x1, zone.y1, pad, w, h)
            (topRefit ?: zoneWith(zone, zone.x0, zone.y0, zone.x1, mid)) to
                (botRefit ?: zoneWith(zone, zone.x0, mid + 1, zone.x1, zone.y1))
        } else {
            val mid = (vGap!!.start + vGap.end) / 2
            val leftRefit = refitToMarks(mask, zone.x0, zone.y0, mid, zone.y1, pad, w, h)
            val rightRefit = refitToMarks(mask, mid + 1, zone.y0, zone.x1, zone.y1, pad, w, h)
            (leftRefit ?: zoneWith(zone, zone.x0, zone.y0, mid, zone.y1)) to
                (rightRefit ?: zoneWith(zone, mid + 1, zone.y0, zone.x1, zone.y1))
        }
        return zoneInheriting(zone, a) to zoneInheriting(zone, b)
    }

    // Refit a sub-rectangle of `mask` to its marks + `pad` (clamped to mask). Returns null if no marks.
    private fun refitToMarks(
        mask: Bitmap,
        x0: Int, y0: Int, x1: Int, y1: Int,
        pad: Int, w: Int, h: Int,
    ): Zone? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (y in y0..y1) {
            for (x in x0..x1) {
                val p = mask.getPixel(x, y)
                if (((p shr 16) and 0xFF) != 0 || ((p shr 8) and 0xFF) != 0 || (p and 0xFF) != 0) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }
        if (minX == Int.MAX_VALUE) return null
        val bx0 = (minX - pad).coerceAtLeast(0)
        val by0 = (minY - pad).coerceAtLeast(0)
        val bx1 = (maxX + pad).coerceAtMost(w - 1)
        val by1 = (maxY + pad).coerceAtMost(h - 1)
        // Caller will replace dim/channels via zoneInheriting; placeholder values are fine here.
        return Zone(bx0, by0, bx1, by1, 0f, floatArrayOf(0f, 0f, 0f))
    }

    private fun zoneWith(parent: Zone, x0: Int, y0: Int, x1: Int, y1: Int): Zone =
        Zone(x0, y0, x1, y1, 0f, floatArrayOf(0f, 0f, 0f))

    private fun zoneInheriting(parent: Zone, src: Zone): Zone =
        Zone(src.x0, src.y0, src.x1, src.y1, parent.dim,
            floatArrayOf(parent.channels[0], parent.channels[1], parent.channels[2]))

    fun rectBboxFromMarks(
        mask: Bitmap,
        rx0: Int, ry0: Int, rx1: Int, ry1: Int,
        globalDim: FloatArray,
    ): Zone? {
        val w = mask.width; val h = mask.height
        val sx0 = minOf(rx0, rx1).coerceIn(0, w - 1)
        val sx1 = maxOf(rx0, rx1).coerceIn(0, w - 1)
        val sy0 = minOf(ry0, ry1).coerceIn(0, h - 1)
        val sy1 = maxOf(ry0, ry1).coerceIn(0, h - 1)
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        var hasR = false; var hasG = false; var hasB = false
        for (y in sy0..sy1) {
            for (x in sx0..sx1) {
                val px = mask.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr  8) and 0xFF
                val b =  px         and 0xFF
                if ((r or g or b) == 0) continue
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (r != 0) hasR = true
                if (g != 0) hasG = true
                if (b != 0) hasB = true
            }
        }
        if (minX == Int.MAX_VALUE) return null
        val bx0 = (minX - PAD).coerceAtLeast(0)
        val by0 = (minY - PAD).coerceAtLeast(0)
        val bx1 = (maxX + PAD).coerceAtMost(w - 1)
        val by1 = (maxY + PAD).coerceAtMost(h - 1)
        val channels = floatArrayOf(
            if (hasR) 1f else 0f,
            if (hasG) 1f else 0f,
            if (hasB) 1f else 0f,
        )
        // Initial dim: average of globalDim entries for the present channels.
        // The user always tunes dim afterwards; this just gives a sensible non-zero starting point.
        var sum = 0f; var count = 0
        if (hasR) { sum += globalDim[0]; count++ }
        if (hasG) { sum += globalDim[1]; count++ }
        if (hasB) { sum += globalDim[2]; count++ }
        val initialDim = if (count > 0) sum / count else globalDim[0]
        return Zone(bx0, by0, bx1, by1, initialDim, channels)
    }
}
