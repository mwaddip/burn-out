package app.burnout.calibrate

import android.graphics.Bitmap
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import kotlin.concurrent.thread

class MaskServer(
    val socketName: String = "burnout-mask",
    private val handler: Handler,
) {
    interface Handler {
        fun snapshotMaskBitmap(): Bitmap?
        fun snapshotMeta(): MetaSnapshot
        fun applyPixelEdits(edits: List<PixelEdit>)
        fun setZoneDim(index: Int, value: Float)
        fun selectZone(index: Int)
        fun setFalloffRadiusPx(value: Float)
        fun setGlobalDim(value: Float)
        fun saveAll(): String?
    }

    data class PixelEdit(val x: Int, val y: Int, val channel: Int, val severity: Int)
    data class MetaSnapshot(
        val maskW: Int, val maskH: Int,
        val zones: List<Zone>,
        val selectedZone: Int,
        val maxWearR: Float, val maxWearG: Float, val maxWearB: Float,
        val falloffRadiusPx: Float,
        val globalDim: Float,
    )

    private var serverSocket: LocalServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(start = true, name = "mask-server-accept") {
            try {
                val ss = LocalServerSocket(socketName)
                serverSocket = ss
                Log.i(TAG, "MaskServer listening on localabstract:$socketName")
                while (running) {
                    val sock = try { ss.accept() } catch (_: IOException) { break }
                    thread(start = true, name = "mask-server-conn") { handleConn(sock) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "accept loop crashed", e)
            } finally {
                Log.i(TAG, "MaskServer stopped")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleConn(sock: LocalSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            val out = sock.outputStream
            while (true) {
                val line = try { reader.readLine() } catch (_: IOException) { null } ?: break
                if (line.isBlank()) continue
                try {
                    handleLine(line, out)
                } catch (e: Exception) {
                    Log.w(TAG, "request error: $line", e)
                    try { writeJson(out, JSONObject().put("ok", false).put("error", e.message ?: "?")) } catch (_: Exception) {}
                }
            }
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun handleLine(line: String, out: OutputStream) {
        val req = JSONObject(line)
        when (req.getString("op")) {
            "ping" -> writeJson(out, JSONObject().put("ok", true))

            "get_meta" -> {
                val s = handler.snapshotMeta()
                val zarr = JSONArray()
                s.zones.forEach { z ->
                    zarr.put(JSONObject()
                        .put("x0", z.x0).put("y0", z.y0)
                        .put("x1", z.x1).put("y1", z.y1)
                        .put("dim", z.dim.toDouble()))
                }
                writeJson(out, JSONObject()
                    .put("ok", true)
                    .put("mask_w", s.maskW).put("mask_h", s.maskH)
                    .put("selected_zone", s.selectedZone)
                    .put("falloff_radius_px", s.falloffRadiusPx.toDouble())
                    .put("global_dim", s.globalDim.toDouble())
                    .put("max_wear", JSONArray()
                        .put(s.maxWearR.toDouble()).put(s.maxWearG.toDouble()).put(s.maxWearB.toDouble()))
                    .put("zones", zarr))
            }

            "get_mask" -> {
                val bmp = handler.snapshotMaskBitmap()
                if (bmp == null) {
                    writeJson(out, JSONObject().put("ok", false).put("error", "no mask loaded"))
                } else {
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    val bytes = baos.toByteArray()
                    writeJson(out, JSONObject().put("ok", true).put("png_len", bytes.size))
                    out.write(bytes)
                    out.flush()
                }
            }

            "toggle_pixels", "set_pixels" -> {
                val arr = req.getJSONArray("edits")
                val edits = ArrayList<PixelEdit>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    edits.add(PixelEdit(
                        o.getInt("x"), o.getInt("y"),
                        o.getInt("channel"), o.getInt("severity")))
                }
                handler.applyPixelEdits(edits)
                writeJson(out, JSONObject().put("ok", true).put("count", edits.size))
            }

            "set_zone_dim" -> {
                handler.setZoneDim(req.getInt("index"), req.getDouble("value").toFloat())
                writeJson(out, JSONObject().put("ok", true))
            }

            "select_zone" -> {
                handler.selectZone(req.getInt("index"))
                writeJson(out, JSONObject().put("ok", true))
            }

            "set_falloff_radius" -> {
                handler.setFalloffRadiusPx(req.getDouble("value").toFloat())
                writeJson(out, JSONObject().put("ok", true))
            }

            "set_global_dim" -> {
                handler.setGlobalDim(req.getDouble("value").toFloat())
                writeJson(out, JSONObject().put("ok", true))
            }

            "save" -> {
                val err = handler.saveAll()
                val obj = JSONObject().put("ok", err == null)
                if (err != null) obj.put("error", err)
                writeJson(out, obj)
            }

            else -> writeJson(out, JSONObject().put("ok", false).put("error", "unknown op"))
        }
    }

    private fun writeJson(out: OutputStream, obj: JSONObject) {
        out.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    companion object { const val TAG = "MaskServer" }
}
