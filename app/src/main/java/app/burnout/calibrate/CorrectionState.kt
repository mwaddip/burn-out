package app.burnout.calibrate

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class Zone(
    val x0: Int, val y0: Int,
    val x1: Int, val y1: Int,
    initialDim: Float,
    // Per-zone channel-has-wear mask (RGB), 0f or 1f per channel.
    // Default all-channels-wearing for back-compat with legacy presets that lack the field.
    val channels: FloatArray = floatArrayOf(1f, 1f, 1f),
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

    // Spec §5.1 entry defaults: falloff=300, globalDim=[0.08, 0.08, 0.08] per RGB channel.
    @Volatile var falloffRadiusPx: Float = 300f
    @Volatile var globalDim: FloatArray = floatArrayOf(0.08f, 0.08f, 0.08f)

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
        return obj.toString(2)
    }

    fun fromJson(json: String) {
        val obj = JSONObject(json)
        maskWidth = obj.optInt("mask_w", 0)
        maskHeight = obj.optInt("mask_h", 0)
        falloffRadiusPx = obj.optDouble("falloff_radius_px", 300.0).toFloat()
        // Back-compat: legacy presets stored global_dim as a scalar; new ones store an RGB array.
        val gdArr = obj.optJSONArray("global_dim")
        if (gdArr != null && gdArr.length() == 3) {
            globalDim = floatArrayOf(
                gdArr.getDouble(0).toFloat(),
                gdArr.getDouble(1).toFloat(),
                gdArr.getDouble(2).toFloat(),
            )
        } else {
            val scalar = obj.optDouble("global_dim", 0.08).toFloat()
            globalDim = floatArrayOf(scalar, scalar, scalar)
        }
        val zarr = obj.optJSONArray("zones") ?: JSONArray()
        zones = (0 until zarr.length()).map { i ->
            val z = zarr.getJSONObject(i)
            val ch = z.optJSONArray("channels")
            val channels = if (ch != null && ch.length() == 3) {
                floatArrayOf(ch.getInt(0).toFloat(), ch.getInt(1).toFloat(), ch.getInt(2).toFloat())
            } else {
                // Legacy preset without channels — assume all channels worn (matches old global gate).
                floatArrayOf(1f, 1f, 1f)
            }
            Zone(
                z.getInt("x0"), z.getInt("y0"),
                z.getInt("x1"), z.getInt("y1"),
                z.getDouble("dim").toFloat(),
                channels,
            )
        }.take(MAX_ZONES)
        obj.optJSONObject("max_wear_per_channel")?.let { mw ->
            maxWearR = mw.optDouble("r", 0.0).toFloat()
            maxWearG = mw.optDouble("g", 0.0).toFloat()
            maxWearB = mw.optDouble("b", 0.0).toFloat()
        }
    }

    companion object {
        const val MAX_ZONES = 12
    }
}
