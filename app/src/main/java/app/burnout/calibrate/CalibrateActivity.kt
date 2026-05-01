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
import androidx.appcompat.app.AlertDialog
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

    private val bgPalette = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF808080.toInt(),
        0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
        0xFF000000.toInt(),
    )
    private val bgLabels = arrayOf("white", "gray", "red", "green", "blue", "black")
    private var bgIndex = 0

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
                syncSliderToSelectedZone()
                updateStatus()
                updateZoneButtonStates()
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
     * Open the Tune-severity dialog: 4 sliders + per-slider lock toggles.
     * Live preview pushes uniforms via glView.requestRender() while dragging;
     * Cancel/back-press restores the pre-open snapshot, Done persists.
     */
    private fun showSeverityDialog() {
        val snapGaps = state.severityGaps.copyOf()
        val snapLocks = state.severityLocks.copyOf()

        val view = layoutInflater.inflate(R.layout.dialog_severity, null)
        val seeks = listOf(
            view.findViewById<SeekBar>(R.id.seek_01),
            view.findViewById<SeekBar>(R.id.seek_12),
            view.findViewById<SeekBar>(R.id.seek_23),
            view.findViewById<SeekBar>(R.id.seek_34),
        )
        val vals = listOf(
            view.findViewById<TextView>(R.id.val_01),
            view.findViewById<TextView>(R.id.val_12),
            view.findViewById<TextView>(R.id.val_23),
            view.findViewById<TextView>(R.id.val_34),
        )
        val lockBtns = listOf(
            view.findViewById<ImageButton>(R.id.lock_01),
            view.findViewById<ImageButton>(R.id.lock_12),
            view.findViewById<ImageButton>(R.id.lock_23),
            view.findViewById<ImageButton>(R.id.lock_34),
        )
        val sumReadout = view.findViewById<TextView>(R.id.sum_readout)

        fun refreshAll() {
            for (j in 0..3) {
                if (seeks[j].progress != state.severityGaps[j]) {
                    seeks[j].progress = state.severityGaps[j]
                }
                vals[j].text = state.severityGaps[j].toString()
                lockBtns[j].setImageResource(
                    if (state.severityLocks[j]) R.drawable.ic_lock_closed
                    else R.drawable.ic_lock_open
                )
                seeks[j].isEnabled = !state.severityLocks[j]
            }
            sumReadout.text = getString(R.string.severity_sum_format, state.severityGaps.sum())
        }
        refreshAll()

        // Lock toggle handlers — enforce ≥2 unlocked.
        for (i in 0..3) {
            lockBtns[i].setOnClickListener {
                val wantLock = !state.severityLocks[i]
                if (wantLock && state.severityLocks.count { !it } <= 2) {
                    Toast.makeText(this, R.string.severity_min_unlocked, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                state.severityLocks[i] = wantLock
                refreshAll()
            }
        }

        // Slider drag handlers — redistribute on every progress change (live preview).
        for (i in 0..3) {
            seeks[i].setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val delta = progress - state.severityGaps[i]
                    if (delta == 0) return
                    CorrectionState.redistributeGaps(
                        state.severityGaps, state.severityLocks,
                        changedIdx = i, delta = delta)
                    refreshAll()
                    glView.requestRender()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.severity_dialog_title)
            .setView(view)
            .setPositiveButton("Done") { _, _ -> /* state already mutated */ }
            .setNegativeButton("Cancel") { _, _ ->
                state.severityGaps = snapGaps
                state.severityLocks = snapLocks
                glView.requestRender()
            }
            .setOnCancelListener {
                state.severityGaps = snapGaps
                state.severityLocks = snapLocks
                glView.requestRender()
            }
            .show()
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
                // TCP protocol still exposes a single global_dim — broadcast the max of the per-channel
                // values so the desktop editor sees the most informative number until protocol catches up.
                MaskServer.MetaSnapshot(
                    state.maskWidth, state.maskHeight,
                    state.zones.toList(),
                    state.selectedZone,
                    state.maxWearR, state.maxWearG, state.maxWearB,
                    state.falloffRadiusPx,
                    maxOf(state.globalDim[0], state.globalDim[1], state.globalDim[2]),
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
                // Legacy TCP protocol takes a scalar — broadcast to all RGB channels.
                val v = value.coerceIn(0f, 1f)
                state.globalDim = floatArrayOf(v, v, v)
                runOnUiThread {
                    syncGlobalDimSliders()
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

        // Per-channel globalDim: three independent sliders (R / G / B).
        wireGlobalDimSlider(0, R.id.seek_global_dim_r)
        wireGlobalDimSlider(1, R.id.seek_global_dim_g)
        wireGlobalDimSlider(2, R.id.seek_global_dim_b)
        lblGlobalDim = findViewById(R.id.lbl_global_dim)

        val btnBg = findViewById<Button>(R.id.btn_bg)
        btnBg.text = "BG: ${bgLabels[bgIndex]}"
        btnBg.setOnClickListener {
            bgIndex = (bgIndex + 1) % bgPalette.size
            state.bgColor = bgPalette[bgIndex]
            btnBg.text = "BG: ${bgLabels[bgIndex]}"
            updateStatus()
        }

        btnZone = findViewById(R.id.btn_zone)
        btnZone.setOnClickListener {
            val n = state.zones.size
            if (n > 0) {
                state.selectedZone = (state.selectedZone + 1) % n
                syncSliderToSelectedZone()
                updateStatus()
                updateZoneButtonStates()
            }
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
        findViewById<Button>(R.id.btn_tune_severity).setOnClickListener { showSeverityDialog() }
        findViewById<Button>(R.id.btn_zone_del).setOnClickListener { deleteSelectedZone() }
        findViewById<Button>(R.id.btn_zone_split).setOnClickListener { splitSelectedZone() }
        findViewById<Button>(R.id.btn_zone_add).setOnClickListener {
            if (addState == AddState.IDLE) startAddMode() else cancelAddMode()
        }
    }

    private fun wireGlobalDimSlider(channelIdx: Int, seekId: Int) {
        findViewById<SeekBar>(seekId).apply {
            this.max = 200
            progress = (state.globalDim[channelIdx] * 200f).toInt().coerceIn(0, 200)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) {
                        state.globalDim[channelIdx] = v * 0.005f
                        updateStatus()
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
    }

    private fun syncGlobalDimSliders() {
        listOf(0 to R.id.seek_global_dim_r, 1 to R.id.seek_global_dim_g, 2 to R.id.seek_global_dim_b).forEach { (idx, id) ->
            findViewById<SeekBar>(id)?.progress = (state.globalDim[idx] * 200f).toInt().coerceIn(0, 200)
        }
    }

    private fun updateStatus() {
        val on = if (state.correctionOn) "ON" else "OFF"
        val zoneInfo = if (state.zones.isEmpty()) "no zones" else
            "zone ${state.selectedZone + 1}/${state.zones.size} dim=${"%.3f".format(Locale.US, selectedZoneDim())}"
        val gd = "%.3f/%.3f/%.3f".format(Locale.US, state.globalDim[0], state.globalDim[1], state.globalDim[2])
        statusLine.text = "$on • bg=${bgLabels[bgIndex]} • $zoneInfo • g=$gd"
        if (::btnZone.isInitialized) {
            btnZone.text = if (state.zones.isEmpty()) "ZONE: —" else "ZONE: ${state.selectedZone + 1}/${state.zones.size}"
        }
        if (::lblRadius.isInitialized) {
            lblRadius.text = "falloff radius: ${state.falloffRadiusPx.toInt()}px"
        }
        if (::lblGlobalDim.isInitialized) {
            lblGlobalDim.text = "global dim R/G/B: $gd"
        }
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
        val n = state.zones.size
        val atCap = n >= CorrectionState.MAX_ZONES
        val haveSelected = n > 0
        val bmp = state.maskBitmap
        val canSplit = if (haveSelected && bmp != null && !atCap) {
            Zones.canSplitZone(bmp, state.zones[state.selectedZone.coerceIn(0, n - 1)])
        } else false

        findViewById<Button>(R.id.btn_zone_add)?.isEnabled = !atCap || addState != AddState.IDLE
        findViewById<Button>(R.id.btn_zone_del)?.isEnabled = haveSelected
        findViewById<Button>(R.id.btn_zone_split)?.isEnabled = canSplit
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
        // Phase 2 stub: pass current activeChannel + zones; Phase 4 wires the proper W-mode guard
        // so this codepath isn't reached in W (where activeChannel == 3 would throw the require()).
        val zone = Zones.rectBboxFromMarks(
            bmp, rx0, ry0, rx1, ry1, state.globalDim,
            activeChannel = state.activeChannel.coerceIn(0, 2),
            existingZones = state.zones,
        )
        if (zone == null) {
            Toast.makeText(this, R.string.calibrate_add_no_marks, Toast.LENGTH_SHORT).show()
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
