package app.burnout.calibrate

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class Zone(
    val x0: Int, val y0: Int,
    val x1: Int, val y1: Int,
    initialDim: Float,
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

    // Spec §5.1 entry defaults: falloff=300, globalDim=0.08.
    @Volatile var falloffRadiusPx: Float = 300f
    @Volatile var globalDim: Float = 0.08f

    fun toJson(): String {
        val zonesArr = JSONArray()
        zones.forEach { z ->
            zonesArr.put(JSONObject()
                .put("x0", z.x0).put("y0", z.y0)
                .put("x1", z.x1).put("y1", z.y1)
                .put("dim", "%.4f".format(Locale.US, z.dim).toDouble()))
        }
        val obj = JSONObject()
            .put("mask_w", maskWidth)
            .put("mask_h", maskHeight)
            .put("falloff_radius_px", falloffRadiusPx.toInt())
            .put("global_dim", "%.4f".format(Locale.US, globalDim).toDouble())
            .put("zones", zonesArr)
            .put("max_wear_per_channel", JSONObject()
                .put("r", "%.4f".format(Locale.US, maxWearR).toDouble())
                .put("g", "%.4f".format(Locale.US, maxWearG).toDouble())
                .put("b", "%.4f".format(Locale.US, maxWearB).toDouble()))
        return obj.toString(2)
    }

    fun fromJson(json: String) {
        val obj = JSONObject(json)
        maskWidth = obj.optInt("mask_w", 0)
        maskHeight = obj.optInt("mask_h", 0)
        falloffRadiusPx = obj.optDouble("falloff_radius_px", 300.0).toFloat()
        globalDim = obj.optDouble("global_dim", 0.08).toFloat()
        val zarr = obj.optJSONArray("zones") ?: JSONArray()
        zones = (0 until zarr.length()).map { i ->
            val z = zarr.getJSONObject(i)
            Zone(
                z.getInt("x0"), z.getInt("y0"),
                z.getInt("x1"), z.getInt("y1"),
                z.getDouble("dim").toFloat(),
            )
        }.take(MAX_ZONES)
        obj.optJSONObject("max_wear_per_channel")?.let { mw ->
            maxWearR = mw.optDouble("r", 0.0).toFloat()
            maxWearG = mw.optDouble("g", 0.0).toFloat()
            maxWearB = mw.optDouble("b", 0.0).toFloat()
        }
    }

    companion object {
        const val MAX_ZONES = 8
    }
}
