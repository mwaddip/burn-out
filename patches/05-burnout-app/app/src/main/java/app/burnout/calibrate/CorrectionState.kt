package app.burnout.calibrate

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class Zone(
    val x0: Int, val y0: Int,
    val x1: Int, val y1: Int,
    initialDim: Float,
    // Per-zone channel-has-wear mask (RGB). Invariant: exactly one entry is 1f, the others 0f
    // (single-channel ownership). Enforced at write paths (rect-add, fromJson). The Zone class
    // itself doesn't validate — callers do — to keep the constructor cheap and side-effect-free.
    val channels: FloatArray,
) {
    @Volatile var dim: Float = initialDim
    fun containsPx(px: Int, py: Int) = px in x0..x1 && py in y0..y1
    fun containsUV(u: Float, v: Float, w: Int, h: Int): Boolean {
        val px = (u * w).toInt(); val py = (v * h).toInt()
        return containsPx(px, py)
    }
}

class CorrectionState {
    @Volatile var correctionOn: Boolean = true
    @Volatile var bgColor: Int = 0xFFFFFFFF.toInt()

    @Volatile var simulatePanel: Boolean = false
    @Volatile var wearStrength: Float = 0.0f

    @Volatile var maxWearR: Float = 0f
    @Volatile var maxWearG: Float = 0f
    @Volatile var maxWearB: Float = 0f

    @Volatile var maskWidth: Int = 0
    @Volatile var maskHeight: Int = 0

    @Volatile var pendingMask: Bitmap? = null
    @Volatile var maskBitmap: Bitmap? = null
    @Volatile var zones: List<Zone> = emptyList()
    @Volatile var selectedZone: Int = 0

    // 0=R, 1=G, 2=B, 3=W. UI-only filter for Calibrate mode (which channel's zones the user is editing).
    // Not persisted in JSON — defaults to 3 (W = full-preview view) at every launch / state reload.
    @Volatile var activeChannel: Int = 3

    // Spec §5.1 entry defaults: falloff=300, globalDim=[0.08, 0.08, 0.08] per RGB channel.
    @Volatile var falloffRadiusPx: Float = 300f
    @Volatile var globalDim: FloatArray = floatArrayOf(0.08f, 0.08f, 0.08f)

    // Severity-aware shading tuning (spec §5). 4 inter-bucket gaps in [0, 100], sum = 100.
    @Volatile var severityGaps: IntArray = intArrayOf(25, 25, 25, 25)
    @Volatile var severityLocks: BooleanArray = booleanArrayOf(false, false, false, false)

    // Cumulative wear-fraction lookup [0, c1, c1+c2, c1+c2+c3, 1.0] derived from severityGaps.
    fun sevWearFraction(): FloatArray {
        val out = FloatArray(5)
        out[0] = 0f
        for (i in 0..3) out[i + 1] = out[i] + severityGaps[i] / 100f
        out[4] = 1f  // pin against accumulated rounding error
        return out
    }

    fun toJson(): String {
        val zonesArr = JSONArray()
        zones.forEach { z ->
            zonesArr.put(JSONObject()
                .put("x0", z.x0).put("y0", z.y0)
                .put("x1", z.x1).put("y1", z.y1)
                .put("dim", "%.4f".format(Locale.US, z.dim).toDouble())
                .put("channels", JSONArray()
                    .put(z.channels[0].toInt()).put(z.channels[1].toInt()).put(z.channels[2].toInt())))
        }
        val obj = JSONObject()
            .put("mask_w", maskWidth)
            .put("mask_h", maskHeight)
            .put("falloff_radius_px", falloffRadiusPx.toInt())
            .put("global_dim", JSONArray()
                .put("%.4f".format(Locale.US, globalDim[0]).toDouble())
                .put("%.4f".format(Locale.US, globalDim[1]).toDouble())
                .put("%.4f".format(Locale.US, globalDim[2]).toDouble()))
            .put("zones", zonesArr)
            .put("max_wear_per_channel", JSONObject()
                .put("r", "%.4f".format(Locale.US, maxWearR).toDouble())
                .put("g", "%.4f".format(Locale.US, maxWearG).toDouble())
                .put("b", "%.4f".format(Locale.US, maxWearB).toDouble()))
            .put("severity_gaps", JSONArray()
                .put(severityGaps[0]).put(severityGaps[1])
                .put(severityGaps[2]).put(severityGaps[3]))
            .put("severity_locks", JSONArray()
                .put(severityLocks[0]).put(severityLocks[1])
                .put(severityLocks[2]).put(severityLocks[3]))
        return obj.toString(2)
    }

    fun fromJson(json: String) {
        val obj = JSONObject(json)
        maskWidth = obj.getInt("mask_w")
        maskHeight = obj.getInt("mask_h")
        falloffRadiusPx = obj.getDouble("falloff_radius_px").toFloat()

        val gdArr = obj.getJSONArray("global_dim")
        check(gdArr.length() == 3) { "global_dim must be a 3-element array" }
        globalDim = floatArrayOf(
            gdArr.getDouble(0).toFloat(),
            gdArr.getDouble(1).toFloat(),
            gdArr.getDouble(2).toFloat(),
        )

        val zarr = obj.getJSONArray("zones")
        zones = (0 until zarr.length()).map { i ->
            val z = zarr.getJSONObject(i)
            val ch = z.getJSONArray("channels")
            check(ch.length() == 3) { "zone[$i].channels must be a 3-element array" }
            val channels = floatArrayOf(
                ch.getInt(0).toFloat(),
                ch.getInt(1).toFloat(),
                ch.getInt(2).toFloat(),
            )
            check(channels.sum() == 1f) {
                "zone[$i].channels must be single-channel (one 1, two 0s); got ${channels.joinToString()}"
            }
            Zone(
                z.getInt("x0"), z.getInt("y0"),
                z.getInt("x1"), z.getInt("y1"),
                z.getDouble("dim").toFloat(),
                channels,
            )
        }.take(MAX_ZONES)

        val mw = obj.getJSONObject("max_wear_per_channel")
        maxWearR = mw.getDouble("r").toFloat()
        maxWearG = mw.getDouble("g").toFloat()
        maxWearB = mw.getDouble("b").toFloat()

        val sgArr = obj.getJSONArray("severity_gaps")
        check(sgArr.length() == 4) { "severity_gaps must be a 4-element array" }
        val gaps = IntArray(4) { sgArr.getInt(it) }
        check(gaps.all { it in 0..100 } && gaps.sum() == 100) {
            "severity_gaps each in 0..100 with sum=100; got ${gaps.joinToString()}"
        }
        severityGaps = gaps

        val slArr = obj.getJSONArray("severity_locks")
        check(slArr.length() == 4) { "severity_locks must be a 4-element array" }
        val locks = BooleanArray(4) { slArr.getBoolean(it) }
        check(locks.count { !it } >= 2) { "severity_locks: at least 2 unlocked required" }
        severityLocks = locks
    }

    companion object {
        const val MAX_ZONES = 12

        /**
         * Redistribute a gap change across unlocked siblings to maintain sum=100.
         *
         * @param gaps mutable IntArray of size 4; updated in place.
         * @param locks BooleanArray of size 4; true = slider may not move.
         * @param changedIdx slider the user dragged (must be unlocked).
         * @param delta intended change to gaps[changedIdx]; positive = grow, negative = shrink.
         *
         * Behavior: distributes -delta proportionally among unlocked others, clamped to [0,100],
         * with a leftover-redistribution loop for what couldn't be applied. After all phases,
         * gaps[changedIdx] is pinned to (100 - sum-of-others) so the invariant holds even if
         * the user's full delta couldn't be absorbed.
         */
        fun redistributeGaps(
            gaps: IntArray, locks: BooleanArray,
            changedIdx: Int, delta: Int,
        ) {
            require(gaps.size == 4 && locks.size == 4)
            if (delta == 0) return
            if (locks[changedIdx]) return
            val others = (0..3).filter { it != changedIdx && !locks[it] }
            if (others.isEmpty()) return  // ≥2 unlocked invariant prevents this; defensive

            var remaining = -delta
            val initialRemaining = remaining

            // Phase 1: proportional distribution against the *initial* remaining, so each sibling's
            // share is computed independently (fairer than letting the running total decay).
            val totalOthers = others.sumOf { gaps[it] }
            for (i in others) {
                val share = if (totalOthers > 0)
                    (initialRemaining.toFloat() * gaps[i] / totalOthers).toInt()
                else
                    initialRemaining / others.size  // all-zero others: equal split
                val newVal = (gaps[i] + share).coerceIn(0, 100)
                remaining -= (newVal - gaps[i])
                gaps[i] = newVal
            }

            // Phase 2: leftover from clamping redistributes proportionally among still-movable.
            // remaining > 0 → we still need siblings to GROW, so we want gaps < 100.
            // remaining < 0 → we still need siblings to SHRINK, so we want gaps > 0.
            while (remaining != 0) {
                val avail = others.filter {
                    (remaining > 0 && gaps[it] < 100) || (remaining < 0 && gaps[it] > 0)
                }
                if (avail.isEmpty()) break
                val share = remaining / avail.size
                if (share == 0) break  // proportional phase done; unit-level pass below
                for (i in avail) {
                    val newVal = (gaps[i] + share).coerceIn(0, 100)
                    remaining -= (newVal - gaps[i])
                    gaps[i] = newVal
                }
            }

            // Phase 3: distribute the final 0..N units one at a time so integer-division floor
            // doesn't silently swallow them. Walk `others` in order for deterministic behavior.
            // step direction matches the sign of `remaining`: positive remaining → grow siblings.
            while (remaining != 0) {
                val step = if (remaining > 0) 1 else -1
                var applied = false
                for (i in others) {
                    if (remaining == 0) break
                    val newVal = gaps[i] + step
                    if (newVal < 0 || newVal > 100) continue
                    gaps[i] = newVal
                    remaining -= step  // step=+1: remaining decreases toward 0; step=-1: increases toward 0
                    applied = true
                }
                if (!applied) break  // all unlocked others are at floor/ceiling
            }

            gaps[changedIdx] = 100 - gaps.indices.filter { it != changedIdx }.sumOf { gaps[it] }
        }
    }
}
