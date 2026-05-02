package app.burnout.calibrate

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import app.burnout.R
import app.burnout.mark.DraggableOverlay
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class CalibrateActivity : AppCompatActivity() {

    private val state = CorrectionState()
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: CorrectionRenderer
    private lateinit var statusLine: TextView
    private lateinit var seekDim: SeekBar
    private lateinit var btnZone: Button
    private lateinit var lblRadius: TextView
    private lateinit var lblGlobalDim: TextView

    private val maskLock = Object()
    private var server: MaskServer? = null

    private enum class AddState { IDLE, AWAITING_CORNER_1, AWAITING_CORNER_2 }
    private var addState: AddState = AddState.IDLE
    private var corner1Mask: Pair<Int, Int>? = null
    private lateinit var cornerOverlay: CornerOverlayView
    private lateinit var addBanner: TextView

    private lateinit var severityPanel: View
    private lateinit var severitySeeks: List<SeekBar>
    private lateinit var severityVals: List<TextView>
    private lateinit var severityLockBtns: List<ImageButton>
    private lateinit var severitySumReadout: TextView
    private var severityPanelSnapGaps: IntArray? = null
    private var severityPanelSnapLocks: BooleanArray? = null
    private var severityPanelOverlay: DraggableOverlay? = null

    private val severityPanelBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { hideSeverityPanel(commit = false) }
    }

    // Index = activeChannel for indices 0..2 (R/G/B); index 3 = W (white BG, full-preview view).
    // bgIndex and state.activeChannel stay in lockstep — see setActiveChannel().
    private val bgPalette = intArrayOf(
        0xFFFF0000.toInt(),  // 0 — R
        0xFF00FF00.toInt(),  // 1 — G
        0xFF0000FF.toInt(),  // 2 — B
        0xFFFFFFFF.toInt(),  // 3 — W
    )
    private val bgLabels = arrayOf("red", "green", "blue", "white")
    private var bgIndex = 3  // start in W

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibrate)
        enterImmersiveMode()

        glView = findViewById(R.id.gl_view)
        glView.setEGLContextClientVersion(3)
        renderer = CorrectionRenderer(this, state)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glView.setOnTouchListener { _, ev ->
            if (addState == AddState.IDLE) return@setOnTouchListener false
            if (ev.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            handleAddTap(ev.x, ev.y)
            true
        }

        cornerOverlay = findViewById(R.id.corner_overlay)
        addBanner = findViewById(R.id.add_banner)

        statusLine = findViewById(R.id.status_line)
        wireControls()
        wireDraggables()
        setupSeverityPanel()
        onBackPressedDispatcher.addCallback(this, severityPanelBackCallback)
        loadMaskAndState()
        startServer()
        updateStatus()
    }

    private fun wireDraggables() {
        val prefs = DraggableOverlay.prefsFor(this)
        DraggableOverlay(
            dragTarget = findViewById(R.id.controls_panel),
            touchTarget = findViewById(R.id.controls_drag_handle),
            id = "calibrate_controls",
            prefs = prefs,
        )
    }

    override fun onResume() { super.onResume(); glView.onResume() }
    override fun onPause()  { glView.onPause();  super.onPause()  }
    override fun onDestroy() {
        server?.stop(); server = null
        super.onDestroy()
    }

    private fun loadMaskAndState() {
        val maskFile = File(filesDir, "mask_autosave.png")
        if (!maskFile.exists()) {
            Toast.makeText(this, R.string.calibrate_no_mask, Toast.LENGTH_LONG).show()
            finish(); return
        }
        thread(start = true, name = "calibrate-load") {
            // inMutable=true so MaskServer.applyPixelEdits can call bmp.setPixel for
            // desktop-editor TCP edits. Default decode produces an immutable bitmap
            // and any setPixel against it throws IllegalStateException.
            val opts = BitmapFactory.Options().apply { inMutable = true }
            val bmp = BitmapFactory.decodeFile(maskFile.absolutePath, opts) ?: return@thread
            val perChan = MaskBlur.globalMaxWearPerChannel(bmp)
            state.maxWearR = perChan[0]; state.maxWearG = perChan[1]; state.maxWearB = perChan[2]
            state.maskWidth = bmp.width; state.maskHeight = bmp.height

            val presetFile = File(filesDir, "preset_autosave.json")
            if (presetFile.exists()) {
                try {
                    state.fromJson(presetFile.readText(Charsets.UTF_8))
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "preset autosave parse failed; running auto-detect", e)
                    autoDetectAndPersist(bmp)
                }
            } else {
                autoDetectAndPersist(bmp)
            }
            state.maskBitmap = bmp
            runOnUiThread {
                state.pendingMask = bmp
                // setActiveChannel handles bg/activeChannel sync, slider/label refresh, mode-UI
                // enable states, status line, zone buttons, and a render request — wiring everything
                // to the current bgIndex (= W on first launch).
                setActiveChannel(bgIndex)
                refreshMaxBucketPresent()
            }
        }
    }

    private fun autoDetectAndPersist(bmp: Bitmap) {
        val zones = Zones.computeFromMask(bmp, prev = emptyList(), seedDims = state.globalDim)
        state.zones = zones
        state.selectedZone = 0
        try {
            File(filesDir, "preset_autosave.json").writeText(state.toJson(), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "preset autosave write failed", e)
        }
    }

    /**
     * Wire the (always-present, initially gone) severity panel: bind view fields, attach
     * lock-toggle and slider listeners, attach Done/Cancel handlers. Called once in onCreate.
     */
    private fun setupSeverityPanel() {
        severityPanel = findViewById(R.id.severity_panel)
        severitySeeks = listOf(
            findViewById(R.id.seek_01), findViewById(R.id.seek_12),
            findViewById(R.id.seek_23), findViewById(R.id.seek_34),
        )
        severityVals = listOf(
            findViewById(R.id.val_01), findViewById(R.id.val_12),
            findViewById(R.id.val_23), findViewById(R.id.val_34),
        )
        severityLockBtns = listOf(
            findViewById(R.id.lock_01), findViewById(R.id.lock_12),
            findViewById(R.id.lock_23), findViewById(R.id.lock_34),
        )
        severitySumReadout = findViewById(R.id.sum_readout)

        // Lock toggle handlers — enforce ≥2 unlocked.
        for (i in 0..3) {
            severityLockBtns[i].setOnClickListener {
                val wantLock = !state.severityLocks[i]
                if (wantLock && state.severityLocks.count { !it } <= 2) {
                    Toast.makeText(this, R.string.severity_min_unlocked, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                state.severityLocks[i] = wantLock
                refreshSeverityPanelUi()
            }
        }

        // Slider drag handlers — redistribute on every progress change (live preview).
        for (i in 0..3) {
            severitySeeks[i].setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val delta = progress - state.severityGaps[i]
                    if (delta == 0) return
                    CorrectionState.redistributeGaps(
                        state.severityGaps, state.severityLocks,
                        changedIdx = i, delta = delta)
                    refreshSeverityPanelUi()
                    glView.requestRender()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        findViewById<Button>(R.id.btn_severity_done).setOnClickListener {
            hideSeverityPanel(commit = true)
        }
        findViewById<Button>(R.id.btn_severity_cancel).setOnClickListener {
            hideSeverityPanel(commit = false)
        }
    }

    private fun refreshSeverityPanelUi() {
        for (j in 0..3) {
            if (severitySeeks[j].progress != state.severityGaps[j]) {
                severitySeeks[j].progress = state.severityGaps[j]
            }
            severityVals[j].text = state.severityGaps[j].toString()
            severityLockBtns[j].setImageResource(
                if (state.severityLocks[j]) R.drawable.ic_lock_closed
                else R.drawable.ic_lock_open
            )
            severitySeeks[j].isEnabled = !state.severityLocks[j]
        }
        severitySumReadout.text =
            getString(R.string.severity_sum_format, state.severityGaps.sum())
    }

    /**
     * Reveal the severity panel as a non-modal draggable overlay. Snapshot current
     * gaps/locks so Cancel can revert. Lazy-init the DraggableOverlay on first show
     * (the panel must be laid out for clampToScreen to use real width/height).
     */
    private fun showSeverityPanel() {
        severityPanelSnapGaps = state.severityGaps.copyOf()
        severityPanelSnapLocks = state.severityLocks.copyOf()
        refreshSeverityPanelUi()
        severityPanel.visibility = View.VISIBLE
        if (severityPanelOverlay == null) {
            severityPanelOverlay = DraggableOverlay(
                dragTarget = severityPanel,
                touchTarget = findViewById(R.id.severity_drag_handle),
                id = "calibrate_severity",
                prefs = DraggableOverlay.prefsFor(this),
            )
        }
        severityPanelBackCallback.isEnabled = true
    }

    private fun hideSeverityPanel(commit: Boolean) {
        if (!commit) {
            severityPanelSnapGaps?.let { state.severityGaps = it }
            severityPanelSnapLocks?.let { state.severityLocks = it }
            refreshSeverityPanelUi()
            glView.requestRender()
        }
        severityPanelSnapGaps = null
        severityPanelSnapLocks = null
        severityPanel.visibility = View.GONE
        severityPanelBackCallback.isEnabled = false
    }

    /**
     * Recompute per-zone-per-channel max severity bucket and push to the renderer.
     * Called after any zone-list mutation (mask reload, Add, Delete, Split) so the
     * shader's zoneMaxEffWear uniforms stay in sync. Cheap: 12 zones × bbox scan.
     */
    private fun refreshMaxBucketPresent() {
        val bmp = state.maskBitmap ?: return
        val out = IntArray(CorrectionState.MAX_ZONES * 3)
        state.zones.forEachIndexed { idx, zone ->
            if (idx >= CorrectionState.MAX_ZONES) return@forEachIndexed
            val mbp = Zones.maxBucketPresent(bmp, zone)
            out[idx * 3 + 0] = mbp[0]
            out[idx * 3 + 1] = mbp[1]
            out[idx * 3 + 2] = mbp[2]
        }
        renderer.setMaxBucketPresent(out)
        glView.requestRender()
    }

    // ---------- Save (live commit to /data/burnout/) ----------

    private fun saveLive() {
        val bmp = state.maskBitmap ?: run {
            Toast.makeText(this, "No mask loaded", Toast.LENGTH_SHORT).show(); return
        }
        thread(start = true, name = "calibrate-save") {
            val maskBytes: ByteArray
            synchronized(maskLock) {
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                maskBytes = baos.toByteArray()
            }
            val json = state.toJson()
            try {
                File(filesDir, "mask_autosave.png").writeBytes(maskBytes)
                File(filesDir, "preset_autosave.json").writeText(json, Charsets.UTF_8)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "autosave write failed", e)
            }
            val staging = File(filesDir, "staging")
            val result = RootIO.commitToDataBurnout(staging, maskBytes, json)
            runOnUiThread {
                when (result.outcome) {
                    RootIO.Outcome.OK ->
                        Toast.makeText(this, getString(R.string.calibrate_save_ok, state.zones.size), Toast.LENGTH_LONG).show()
                    RootIO.Outcome.DENIED ->
                        Toast.makeText(this, R.string.calibrate_save_denied, Toast.LENGTH_LONG).show()
                    RootIO.Outcome.FAILED ->
                        Toast.makeText(this, getString(R.string.calibrate_save_failed, result.exitCode), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- Export (timestamped MediaStore.Downloads) ----------

    private fun exportToDownloads() {
        val bmp = state.maskBitmap ?: run {
            Toast.makeText(this, "No mask loaded", Toast.LENGTH_SHORT).show(); return
        }
        thread(start = true, name = "calibrate-export") {
            val ts = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            try {
                val maskBytes = ByteArrayOutputStream().also {
                    synchronized(maskLock) { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }.toByteArray()
                writeMediaStoreEntry("mask.png", "image/png", "burn-out/$ts", maskBytes)
                writeMediaStoreEntry("zones.json", "application/json", "burn-out/$ts", state.toJson().toByteArray(Charsets.UTF_8))
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.calibrate_export_ok, ts), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "export failed", e)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.calibrate_export_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun writeMediaStoreEntry(displayName: String, mime: String, subPath: String, bytes: ByteArray) {
        val resolver = contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subPath")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed for $displayName")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    // ---------- TCP server ----------

    private fun startServer() {
        server = MaskServer(handler = object : MaskServer.Handler {
            override fun snapshotMaskBitmap(): Bitmap? = synchronized(maskLock) {
                state.maskBitmap?.let { it.copy(it.config ?: Bitmap.Config.ARGB_8888, false) }
            }
            override fun snapshotMeta() = synchronized(maskLock) {
                // TCP protocol exposes a single global_dim scalar — project the active-channel
                // value (or the max in W) so the desktop editor sees a stable number.
                val gd = if (state.activeChannel in 0..2) state.globalDim[state.activeChannel]
                         else maxOf(state.globalDim[0], state.globalDim[1], state.globalDim[2])
                MaskServer.MetaSnapshot(
                    state.maskWidth, state.maskHeight,
                    state.zones.toList(),
                    state.selectedZone,
                    state.maxWearR, state.maxWearG, state.maxWearB,
                    state.falloffRadiusPx,
                    gd,
                )
            }
            override fun applyPixelEdits(edits: List<MaskServer.PixelEdit>) {
                val bmp = state.maskBitmap ?: return
                synchronized(maskLock) {
                    val w = bmp.width; val h = bmp.height
                    for (e in edits) {
                        if (e.x !in 0 until w || e.y !in 0 until h) continue
                        val orig = bmp.getPixel(e.x, e.y)
                        val r = (orig shr 16) and 0xFF
                        val g = (orig shr  8) and 0xFF
                        val b =  orig         and 0xFF
                        // Top-of-bucket mapping (matches Mark's encoding): 0=clear, 1=63, 2=127, 3=191, 4=255.
                        val sevByte = when (e.severity.coerceIn(0, 4)) {
                            0 -> 0; 1 -> 63; 2 -> 127; 3 -> 191; else -> 255
                        }
                        val newR = if (e.channel == 0) sevByte else r
                        val newG = if (e.channel == 1) sevByte else g
                        val newB = if (e.channel == 2) sevByte else b
                        bmp.setPixel(e.x, e.y, (0xFF shl 24) or (newR shl 16) or (newG shl 8) or newB)
                    }
                }
                state.pendingMask = bmp
            }
            override fun setZoneDim(index: Int, value: Float) {
                state.zones.getOrNull(index)?.dim = value.coerceIn(0f, 1f)
                runOnUiThread { if (index == state.selectedZone) syncSliderToSelectedZone(); updateStatus() }
            }
            override fun selectZone(index: Int) {
                if (index in state.zones.indices) {
                    state.selectedZone = index
                    runOnUiThread {
                        syncSliderToSelectedZone()
                        updateStatus()
                        updateZoneButtonStates()
                    }
                }
            }
            override fun setFalloffRadiusPx(value: Float) {
                state.falloffRadiusPx = value.coerceIn(0f, 2000f)
                runOnUiThread { updateStatus() }
            }
            override fun setGlobalDim(value: Float) {
                val v = value.coerceIn(0f, 1f)
                if (state.activeChannel in 0..2) {
                    state.globalDim[state.activeChannel] = v
                } else {
                    // W mode — broadcast to keep the desktop-editor scalar protocol meaningful.
                    state.globalDim = floatArrayOf(v, v, v)
                }
                runOnUiThread {
                    syncGlobalDimSlider()
                    updateStatus()
                }
            }
            override fun saveAll(): String? {
                var err: String? = null
                val done = java.util.concurrent.CountDownLatch(1)
                runOnUiThread {
                    try { saveLive() } catch (e: Exception) { err = e.message ?: "?" }
                    finally { done.countDown() }
                }
                done.await()
                return err
            }
        }).also { it.start() }
    }

    // ---------- UI plumbing ----------

    private fun selectedZoneDim(): Float = state.zones.getOrNull(state.selectedZone)?.dim ?: 0f

    private fun setSelectedZoneDim(v: Float) {
        state.zones.getOrNull(state.selectedZone)?.dim = v.coerceIn(0f, 1f)
    }

    private fun syncSliderToSelectedZone() {
        if (::seekDim.isInitialized) seekDim.progress = (selectedZoneDim() * 200f).toInt().coerceIn(0, 200)
    }

    private fun wireControls() {
        seekDim = findViewById<SeekBar>(R.id.seek_dim).apply {
            this.max = 200
            progress = (selectedZoneDim() * 200f).toInt().coerceIn(0, 200)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) { setSelectedZoneDim(v * 0.005f); updateStatus() }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        lblRadius = findViewById(R.id.lbl_radius)
        findViewById<SeekBar>(R.id.seek_radius).apply {
            this.max = 1000
            progress = state.falloffRadiusPx.toInt().coerceIn(0, 1000)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    state.falloffRadiusPx = v.toFloat(); updateStatus()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        // Single global-dim slider — bound dynamically to globalDim[activeChannel] in non-W modes.
        // In W mode the slider is disabled (updateModeUiEnabledStates).
        findViewById<SeekBar>(R.id.seek_global_dim).apply {
            this.max = 200
            progress = (state.globalDim[bgIndex.coerceIn(0, 2)] * 200f).toInt().coerceIn(0, 200)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    if (isWMode()) return
                    state.globalDim[state.activeChannel] = v * 0.005f
                    syncGlobalDimLabel()
                    updateStatus()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        lblGlobalDim = findViewById(R.id.lbl_global_dim)

        val btnBg = findViewById<Button>(R.id.btn_bg)
        btnBg.text = "BG: ${bgLabels[bgIndex]}"
        btnBg.setOnClickListener {
            setActiveChannel((bgIndex + 1) % bgPalette.size)
        }

        btnZone = findViewById(R.id.btn_zone)
        btnZone.setOnClickListener {
            if (isWMode()) return@setOnClickListener
            val filtered = CorrectionRenderer.filterByChannel(state.zones, state.activeChannel)
            if (filtered.isEmpty()) return@setOnClickListener
            val curIdxInFiltered = filtered.indexOfFirst { state.zones.indexOf(it) == state.selectedZone }
            val nextIdxInFiltered = if (curIdxInFiltered < 0) 0 else (curIdxInFiltered + 1) % filtered.size
            state.selectedZone = state.zones.indexOf(filtered[nextIdxInFiltered])
            syncSliderToSelectedZone()
            updateStatus()
            updateZoneButtonStates()
        }

        findViewById<Button>(R.id.btn_ab).apply {
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { state.correctionOn = false; updateStatus(); true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        state.correctionOn = true; updateStatus(); true
                    }
                    else -> false
                }
            }
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener { saveLive() }
        findViewById<Button>(R.id.btn_export).setOnClickListener { exportToDownloads() }
        findViewById<Button>(R.id.btn_tune_severity).setOnClickListener { showSeverityPanel() }
        findViewById<Button>(R.id.btn_zone_del).setOnClickListener { deleteSelectedZone() }
        findViewById<Button>(R.id.btn_zone_split).setOnClickListener { splitSelectedZone() }
        findViewById<Button>(R.id.btn_zone_add).setOnClickListener {
            if (addState == AddState.IDLE) startAddMode() else cancelAddMode()
        }
    }

    /**
     * Cycle to the next mode (R → G → B → W → R). Updates state.activeChannel + state.bgColor,
     * snaps state.selectedZone to the first same-channel zone (or 0 if none), and refreshes UI.
     */
    private fun setActiveChannel(channelIdx: Int) {
        bgIndex = channelIdx.coerceIn(0, 3)
        state.activeChannel = bgIndex
        state.bgColor = bgPalette[bgIndex]

        val filtered = CorrectionRenderer.filterByChannel(state.zones, state.activeChannel)
        state.selectedZone = if (filtered.isEmpty()) 0
            else state.zones.indexOf(filtered[0]).coerceAtLeast(0)

        findViewById<Button>(R.id.btn_bg)?.text = "BG: ${bgLabels[bgIndex]}"
        syncSliderToSelectedZone()
        syncGlobalDimSlider()
        updateModeUiEnabledStates()
        updateStatus()
        updateZoneButtonStates()
        glView.requestRender()
    }

    private fun isWMode(): Boolean = state.activeChannel == 3

    private fun updateModeUiEnabledStates() {
        val w = isWMode()
        findViewById<Button>(R.id.btn_zone)?.isEnabled = !w
        findViewById<Button>(R.id.btn_zone_add)?.isEnabled = !w
        findViewById<Button>(R.id.btn_zone_del)?.isEnabled = !w
        findViewById<Button>(R.id.btn_zone_split)?.isEnabled = !w
        findViewById<Button>(R.id.btn_ab)?.isEnabled = !w
        findViewById<Button>(R.id.btn_tune_severity)?.isEnabled = !w
        findViewById<SeekBar>(R.id.seek_dim)?.isEnabled = !w
        findViewById<SeekBar>(R.id.seek_global_dim)?.isEnabled = !w
        findViewById<SeekBar>(R.id.seek_radius)?.isEnabled = !w
        // Always enabled: btn_bg, btn_save, btn_export.
        if (!w) updateZoneButtonStates()  // restores nuanced enabled states for zone buttons in non-W
    }

    private fun syncGlobalDimSlider() {
        if (isWMode()) return
        val ch = state.activeChannel.coerceIn(0, 2)
        findViewById<SeekBar>(R.id.seek_global_dim)?.progress =
            (state.globalDim[ch] * 200f).toInt().coerceIn(0, 200)
        syncGlobalDimLabel()
    }

    private fun syncGlobalDimLabel() {
        if (!::lblGlobalDim.isInitialized) return
        val ch = state.activeChannel
        lblGlobalDim.text = if (ch in 0..2) {
            val name = bgLabels[ch].uppercase().take(1)
            "global dim %s: %.3f".format(Locale.US, name, state.globalDim[ch])
        } else {
            "global dim R/G/B: %.3f / %.3f / %.3f".format(Locale.US,
                state.globalDim[0], state.globalDim[1], state.globalDim[2])
        }
    }

    private fun updateStatus() {
        val on = if (state.correctionOn) "ON" else "OFF"
        val mode = bgLabels[bgIndex].uppercase().take(1)  // R / G / B / W
        val filtered = CorrectionRenderer.filterByChannel(state.zones, state.activeChannel)
        val zoneInfo = when {
            isWMode() -> "preview (${state.zones.size} zones total)"
            filtered.isEmpty() -> "no $mode zones"
            else -> {
                val pos = filtered.indexOfFirst { state.zones.indexOf(it) == state.selectedZone }
                    .coerceAtLeast(0)
                val sel = filtered[pos]
                "$mode: zone ${pos + 1}/${filtered.size} dim=${"%.3f".format(Locale.US, sel.dim)}"
            }
        }
        val gdLine = if (isWMode())
            "g=%.3f/%.3f/%.3f".format(Locale.US, state.globalDim[0], state.globalDim[1], state.globalDim[2])
        else
            "g[$mode]=%.3f".format(Locale.US, state.globalDim[state.activeChannel])

        statusLine.text = "$on • bg=${bgLabels[bgIndex]} • $zoneInfo • $gdLine"

        if (::btnZone.isInitialized) {
            btnZone.text = when {
                isWMode() -> "ZONE"
                filtered.isEmpty() -> "ZONE: —"
                else -> {
                    val pos = filtered.indexOfFirst { state.zones.indexOf(it) == state.selectedZone }
                        .coerceAtLeast(0)
                    "ZONE: ${pos + 1}/${filtered.size}"
                }
            }
        }
        if (::lblRadius.isInitialized) {
            lblRadius.text = "falloff radius: ${state.falloffRadiusPx.toInt()}px"
        }
        syncGlobalDimLabel()
    }

    private fun enterImmersiveMode() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let { c ->
            c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            c.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun deleteSelectedZone() {
        val zones = state.zones.toMutableList()
        if (zones.isEmpty()) return
        val idx = state.selectedZone.coerceIn(0, zones.size - 1)
        zones.removeAt(idx)
        state.zones = zones
        state.selectedZone = if (zones.isEmpty()) 0 else (idx).coerceAtMost(zones.size - 1)
        updateZoneButtonStates()
        syncSliderToSelectedZone()
        updateStatus()
        refreshMaxBucketPresent()
    }

    private fun splitSelectedZone() {
        val bmp = state.maskBitmap ?: return
        val zones = state.zones.toMutableList()
        if (zones.isEmpty()) return
        if (zones.size >= CorrectionState.MAX_ZONES) {
            Toast.makeText(this, R.string.calibrate_at_cap, Toast.LENGTH_SHORT).show()
            return
        }
        val idx = state.selectedZone.coerceIn(0, zones.size - 1)
        val parent = zones[idx]
        val pair = Zones.splitZone(bmp, parent) ?: return
        zones.removeAt(idx)
        // Insert both halves at the same position; sort by Y center to keep top-to-bottom convention.
        zones.add(idx, pair.first)
        zones.add(idx + 1, pair.second)
        val sorted = zones.sortedBy { (it.y0 + it.y1) / 2 }
        state.zones = sorted
        // Keep selection on the first half.
        state.selectedZone = sorted.indexOf(pair.first).coerceAtLeast(0)
        updateZoneButtonStates()
        syncSliderToSelectedZone()
        updateStatus()
        refreshMaxBucketPresent()
    }

    private fun updateZoneButtonStates() {
        if (isWMode()) {
            // W mode: updateModeUiEnabledStates already disabled all zone buttons.
            return
        }
        val filtered = CorrectionRenderer.filterByChannel(state.zones, state.activeChannel)
        val totalAtCap = state.zones.size >= CorrectionState.MAX_ZONES
        val haveSameChannelSelected = filtered.isNotEmpty() &&
            state.zones.getOrNull(state.selectedZone)?.let {
                it.channels[state.activeChannel] >= 0.5f
            } == true
        val bmp = state.maskBitmap
        val canSplit = haveSameChannelSelected && bmp != null && !totalAtCap &&
            Zones.canSplitZone(bmp, state.zones[state.selectedZone])

        findViewById<Button>(R.id.btn_zone_add)?.isEnabled = !totalAtCap || addState != AddState.IDLE
        findViewById<Button>(R.id.btn_zone_del)?.isEnabled = haveSameChannelSelected
        findViewById<Button>(R.id.btn_zone_split)?.isEnabled = canSplit
        findViewById<Button>(R.id.btn_zone)?.isEnabled = filtered.isNotEmpty()
    }

    private fun handleAddTap(viewX: Float, viewY: Float) {
        val w = state.maskWidth
        val h = state.maskHeight
        if (w <= 0 || h <= 0) return
        val mx = (viewX / glView.width * w).toInt().coerceIn(0, w - 1)
        val my = (viewY / glView.height * h).toInt().coerceIn(0, h - 1)
        when (addState) {
            AddState.AWAITING_CORNER_1 -> {
                corner1Mask = mx to my
                addState = AddState.AWAITING_CORNER_2
                cornerOverlay.update(corner1Mask, null, w, h)
            }
            AddState.AWAITING_CORNER_2 -> {
                val c1 = corner1Mask
                if (c1 == null) {
                    cancelAddMode()
                    return
                }
                cornerOverlay.update(c1, mx to my, w, h)
                applyAddZone(c1.first, c1.second, mx, my)
            }
            AddState.IDLE -> { /* unreachable: setOnTouchListener short-circuits */ }
        }
    }

    private fun applyAddZone(rx0: Int, ry0: Int, rx1: Int, ry1: Int) {
        val bmp = state.maskBitmap ?: run { cancelAddMode(); return }
        if (state.zones.size >= CorrectionState.MAX_ZONES) {
            Toast.makeText(this, R.string.calibrate_at_cap, Toast.LENGTH_SHORT).show()
            cancelAddMode()
            return
        }
        if (isWMode()) { cancelAddMode(); return }
        val zone = Zones.rectBboxFromMarks(
            bmp, rx0, ry0, rx1, ry1, state.globalDim,
            activeChannel = state.activeChannel,
            existingZones = state.zones,
        )
        if (zone == null) {
            val chName = bgLabels[state.activeChannel].uppercase().take(1)
            Toast.makeText(this,
                "no $chName marks in rect, or overlaps existing $chName zone",
                Toast.LENGTH_SHORT).show()
            cancelAddMode()
            return
        }
        val zones = state.zones.toMutableList()
        zones.add(zone)
        val sorted = zones.sortedBy { (it.y0 + it.y1) / 2 }
        state.zones = sorted
        state.selectedZone = sorted.indexOf(zone).coerceAtLeast(0)
        cancelAddMode()
        syncSliderToSelectedZone()
        updateStatus()
        updateZoneButtonStates()
        refreshMaxBucketPresent()
    }

    private fun startAddMode() {
        if (state.zones.size >= CorrectionState.MAX_ZONES) {
            Toast.makeText(this, R.string.calibrate_at_cap, Toast.LENGTH_SHORT).show()
            return
        }
        addState = AddState.AWAITING_CORNER_1
        corner1Mask = null
        cornerOverlay.update(null, null, state.maskWidth, state.maskHeight)
        addBanner.visibility = View.VISIBLE
    }

    private fun cancelAddMode() {
        addState = AddState.IDLE
        corner1Mask = null
        cornerOverlay.update(null, null, state.maskWidth, state.maskHeight)
        addBanner.visibility = View.GONE
        updateZoneButtonStates()
    }

    companion object { private const val TAG = "CalibrateActivity" }
}
