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
}
