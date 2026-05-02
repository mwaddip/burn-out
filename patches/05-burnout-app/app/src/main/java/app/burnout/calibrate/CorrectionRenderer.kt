package app.burnout.calibrate

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CorrectionRenderer(
    private val context: Context,
    private val state: CorrectionState,
) : GLSurfaceView.Renderer {

    private var program: Int = 0
    private var aPos: Int = 0
    private val uniforms = HashMap<String, Int>()
    private var quadBuf: FloatBuffer? = null
    private var maskTex: Int = 0

    private val zonesUv = FloatArray(CorrectionState.MAX_ZONES * 4)
    private val zonesDim = FloatArray(CorrectionState.MAX_ZONES)
    private val zonesChannels = FloatArray(CorrectionState.MAX_ZONES * 3)
    private val sevWearFraction = FloatArray(5)
    private val zoneMaxEffWear = FloatArray(CorrectionState.MAX_ZONES * 3)
    // Cached per-zone-per-channel max bucket; refreshed when zones change.
    // Indexed [zone * 3 + channel]. Default zeros mean "no wear in this zone in this channel" →
    // shader's step(1e-6, zoneMaxEff) fallback gives factor=1 (legacy behavior) until populated.
    private val maxBucketPresent = IntArray(CorrectionState.MAX_ZONES * 3)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = link(
            compile(GLES30.GL_VERTEX_SHADER,   readAsset("burnout_vertex.glsl")),
            compile(GLES30.GL_FRAGMENT_SHADER, readAsset("burnout_shader.glsl")),
        )
        aPos = GLES30.glGetAttribLocation(program, "a_pos")
        for (n in listOf("u_mask", "u_bgColor", "u_correctionOn",
                         "u_maxWearR", "u_maxWearG", "u_maxWearB",
                         "u_simulatePanel", "u_wearStrength",
                         "u_zoneCount", "u_zones", "u_zoneDims", "u_zoneChannels",
                         "u_selectedZone", "u_pxToUV",
                         "u_maskSizePx", "u_falloffRadiusPx", "u_globalDim",
                         "u_sevWearFraction", "u_zoneMaxEffWear")) {
            uniforms[n] = GLES30.glGetUniformLocation(program, n)
        }
        quadBuf = ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f,  1f, -1f, -1f,  1f,  1f,  1f))
            position(0)
        }

        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        maskTex = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val zero = ByteBuffer.allocateDirect(4).put(byteArrayOf(0, 0, 0, 0)).rewind()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 1, 1, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, zero)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        state.pendingMask?.let { bmp ->
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            state.pendingMask = null
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glUniform1i(uniforms["u_mask"]!!, 0)

        val r = ((state.bgColor shr 16) and 0xFF) / 255f
        val g = ((state.bgColor shr 8)  and 0xFF) / 255f
        val b = ( state.bgColor         and 0xFF) / 255f
        GLES30.glUniform3f(uniforms["u_bgColor"]!!, r, g, b)
        GLES30.glUniform1i(uniforms["u_correctionOn"]!!, if (state.correctionOn) 1 else 0)
        GLES30.glUniform1f(uniforms["u_maxWearR"]!!, state.maxWearR)
        GLES30.glUniform1f(uniforms["u_maxWearG"]!!, state.maxWearG)
        GLES30.glUniform1f(uniforms["u_maxWearB"]!!, state.maxWearB)
        GLES30.glUniform1i(uniforms["u_simulatePanel"]!!, if (state.simulatePanel) 1 else 0)
        GLES30.glUniform1f(uniforms["u_wearStrength"]!!, state.wearStrength)

        val allZones = state.zones
        val w = state.maskWidth.coerceAtLeast(1)
        val h = state.maskHeight.coerceAtLeast(1)

        // Severity sevWearFraction is mode-independent — refresh first so it's available below.
        val frac = state.sevWearFraction()
        for (i in 0..4) sevWearFraction[i] = frac[i]

        // Pre-zero slot arrays — entries beyond filteredCount are dead but the shader's looped
        // uniform fetches still touch them; zero is safe (zoneWeight on an empty bbox returns 0).
        for (i in 0 until CorrectionState.MAX_ZONES) {
            zonesUv[i * 4 + 0] = 0f; zonesUv[i * 4 + 1] = 0f
            zonesUv[i * 4 + 2] = 0f; zonesUv[i * 4 + 3] = 0f
            zonesDim[i] = 0f
            zonesChannels[i * 3 + 0] = 0f
            zonesChannels[i * 3 + 1] = 0f
            zonesChannels[i * 3 + 2] = 0f
            for (c in 0..2) zoneMaxEffWear[i * 3 + c] = 0f
        }

        // Filter by active channel; build (origIdx, zone) pairs so maxBucketPresent — indexed by
        // original zone position — can still be looked up after the index shift.
        val filteredWithIdx = allZones.withIndex().filter {
            state.activeChannel !in 0..2 || it.value.channels[state.activeChannel] >= 0.5f
        }
        val filteredCount = filteredWithIdx.size.coerceAtMost(CorrectionState.MAX_ZONES)

        for (newIdx in 0 until filteredCount) {
            val origIdx = filteredWithIdx[newIdx].index
            val z = filteredWithIdx[newIdx].value
            zonesUv[newIdx * 4 + 0] = z.x0.toFloat() / w
            zonesUv[newIdx * 4 + 1] = z.y0.toFloat() / h
            zonesUv[newIdx * 4 + 2] = z.x1.toFloat() / w
            zonesUv[newIdx * 4 + 3] = z.y1.toFloat() / h
            zonesDim[newIdx] = z.dim
            zonesChannels[newIdx * 3 + 0] = z.channels[0]
            zonesChannels[newIdx * 3 + 1] = z.channels[1]
            zonesChannels[newIdx * 3 + 2] = z.channels[2]
            for (c in 0..2) {
                val bucket = maxBucketPresent[origIdx * 3 + c].coerceIn(0, 4)
                zoneMaxEffWear[newIdx * 3 + c] = sevWearFraction[bucket]
            }
        }

        GLES30.glUniform1i(uniforms["u_zoneCount"]!!, filteredCount)
        GLES30.glUniform4fv(uniforms["u_zones"]!!, CorrectionState.MAX_ZONES, zonesUv, 0)
        GLES30.glUniform1fv(uniforms["u_zoneDims"]!!, CorrectionState.MAX_ZONES, zonesDim, 0)
        GLES30.glUniform3fv(uniforms["u_zoneChannels"]!!, CorrectionState.MAX_ZONES, zonesChannels, 0)

        // selectedZone in state is an index into allZones; remap to the filtered position so the
        // shader's outline highlight tracks the right zone. Out-of-range value suppresses the
        // outline (used in W mode + when selection isn't in the active-channel filter).
        val newSelected = filteredWithIdx.indexOfFirst { it.index == state.selectedZone }
        GLES30.glUniform1i(uniforms["u_selectedZone"]!!,
            if (newSelected < 0) CorrectionState.MAX_ZONES else newSelected)

        GLES30.glUniform2f(uniforms["u_pxToUV"]!!, 1f / w, 1f / h)
        GLES30.glUniform2f(uniforms["u_maskSizePx"]!!, w.toFloat(), h.toFloat())
        GLES30.glUniform1f(uniforms["u_falloffRadiusPx"]!!, state.falloffRadiusPx)
        GLES30.glUniform3f(uniforms["u_globalDim"]!!, state.globalDim[0], state.globalDim[1], state.globalDim[2])
        GLES30.glUniform1fv(uniforms["u_sevWearFraction"]!!, 5, sevWearFraction, 0)
        GLES30.glUniform3fv(uniforms["u_zoneMaxEffWear"]!!, CorrectionState.MAX_ZONES, zoneMaxEffWear, 0)

        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quadBuf)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(aPos)
    }

    /**
     * Called from CalibrateActivity when the mask is (re)loaded or zones are added / deleted / split.
     * Stores per-zone-per-channel max bucket so onDrawFrame can re-derive zoneMaxEffWear cheaply
     * when severity-fraction sliders move (no per-zone mask scan required).
     */
    fun setMaxBucketPresent(perZonePerChannel: IntArray) {
        require(perZonePerChannel.size == CorrectionState.MAX_ZONES * 3) {
            "expected ${CorrectionState.MAX_ZONES * 3} ints, got ${perZonePerChannel.size}"
        }
        System.arraycopy(perZonePerChannel, 0, maxBucketPresent, 0, perZonePerChannel.size)
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            error("shader compile failed: $log\n--- src ---\n$src")
        }
        return s
    }

    private fun link(vs: Int, fs: Int): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs); GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            error("program link failed: $log")
        }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        return p
    }

    companion object {
        /**
         * Filter zones to those owning the given channel. Used to scope the Calibrate preview
         * shader to the active mode's zones. Channel 3 (W mode) returns the input list unchanged.
         * Out-of-range channels also return the input unchanged (defensive).
         */
        fun filterByChannel(zones: List<Zone>, channel: Int): List<Zone> =
            if (channel !in 0..2) zones
            else zones.filter { it.channels[channel] >= 0.5f }
    }
}
