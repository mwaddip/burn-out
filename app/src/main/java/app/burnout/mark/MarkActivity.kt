package app.burnout.mark

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.burnout.R

class MarkActivity : AppCompatActivity() {

    private lateinit var markView: MarkView
    private lateinit var statusPill: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    private var fineTune: Boolean = false

    private val pickImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) loadImageFromUri(uri)
    }

    private val autoSaveTicker = object : Runnable {
        override fun run() {
            try {
                MaskCodec.saveAutoSave(this@MarkActivity, markView.mask)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "auto-save failed", e)
            }
            mainHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mark)
        enterImmersiveMode()

        markView = findViewById(R.id.mark_view)
        statusPill = findViewById(R.id.status_pill)

        MaskCodec.loadAutoSave(this)?.let { markView.mask = it }

        markView.onCursorChanged = { _, _ -> updateStatusPill() }

        wireOverlayButtons()
        wireDraggables()
        updateStatusPill()
    }

    override fun onResume() {
        super.onResume()
        mainHandler.postDelayed(autoSaveTicker, 30_000L)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(autoSaveTicker)
        try { MaskCodec.saveAutoSave(this, markView.mask) }
        catch (e: Exception) { android.util.Log.w(TAG, "pause-save failed", e) }
        super.onPause()
    }

    private fun enterImmersiveMode() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let { c ->
            c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            c.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun wireOverlayButtons() {
        fun step(): Int = if (fineTune) 1 else markView.brushSize
        fun nudge(dx: Int, dy: Int) {
            // Per spec §4.1, MOVE TARGET drives routing, not implicit "has image".
            when (markView.moveTarget) {
                MoveTarget.IMAGE -> markView.nudgeImage(dx, dy)
                MoveTarget.CURSOR -> {
                    markView.nudgeCursor(dx, dy)
                    updateStatusPill()
                }
            }
        }
        findViewById<Button>(R.id.btn_up).setOnClickListener    { nudge(0, -step()) }
        findViewById<Button>(R.id.btn_down).setOnClickListener  { nudge(0,  step()) }
        findViewById<Button>(R.id.btn_left).setOnClickListener  { nudge(-step(), 0) }
        findViewById<Button>(R.id.btn_right).setOnClickListener { nudge( step(), 0) }
        findViewById<Button>(R.id.btn_confirm).setOnClickListener {
            markView.confirmAtCursor()
            updateStatusPill()
        }

        findViewById<Button>(R.id.btn_channel).setOnClickListener {
            markView.activeChannel = markView.activeChannel.cycle()
            updateStatusPill()
            updateStampChannelModeButton()  // W disables/forces the toggle's effective mode
        }
        findViewById<Button>(R.id.btn_severity).setOnClickListener {
            val next = if (markView.brushSeverity.level >= 4) Severity(1)
                       else Severity(markView.brushSeverity.level + 1)
            markView.brushSeverity = next
            updateStatusPill()
        }

        val btnBrush = findViewById<Button>(R.id.btn_brush)
        val btnBrushPlus = findViewById<Button>(R.id.btn_brush_plus)
        val btnBrushMinus = findViewById<Button>(R.id.btn_brush_minus)
        fun applyBrush(next: Int) {
            markView.brushSize = next
            btnBrush.text = "B:${markView.brushSize}"
            updateStatusPill()
        }
        btnBrush.setOnClickListener {
            applyBrush(if (markView.brushSize >= 10) 1 else markView.brushSize + 1)
        }
        btnBrushPlus.setOnClickListener  { applyBrush((markView.brushSize + 1).coerceAtMost(10)) }
        btnBrushMinus.setOnClickListener { applyBrush((markView.brushSize - 1).coerceAtLeast(1)) }

        val btnFine = findViewById<Button>(R.id.btn_fine_tune)
        btnFine.setOnClickListener {
            fineTune = !fineTune
            btnFine.text = if (fineTune) getString(R.string.fine_on) else getString(R.string.fine_off)
        }

        findViewById<Button>(R.id.btn_clear_channel).setOnClickListener {
            if (markView.activeChannel.isMarkingChannel) {
                markView.mask.clearChannel(markView.activeChannel)
                markView.invalidate()
                Toast.makeText(this, "Cleared ${markView.activeChannel}", Toast.LENGTH_SHORT).show()
            }
        }

        // A/B hold-to-toggle (spec §4.1): hold to hide painted overlay, release to reveal.
        val btnHide = findViewById<Button>(R.id.btn_hide_overlay)
        btnHide.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    markView.hidePaintedOverlay = true
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    markView.hidePaintedOverlay = false
                    v.isPressed = false
                    v.performClick()  // accessibility
                    true
                }
                else -> false
            }
        }

        // MOVE TARGET toggle (spec §4.1).
        val btnMoveTarget = findViewById<Button>(R.id.btn_move_target)
        fun refreshMoveTargetButton() {
            btnMoveTarget.text = when (markView.moveTarget) {
                MoveTarget.CURSOR -> getString(R.string.move_target_cursor)
                MoveTarget.IMAGE  -> getString(R.string.move_target_image)
            }
            // Disabled when no image loaded (spec §4.1). MarkView.hasImage drives state.
            btnMoveTarget.isEnabled = markView.hasImage()
        }
        btnMoveTarget.setOnClickListener {
            markView.moveTarget = if (markView.moveTarget == MoveTarget.CURSOR) MoveTarget.IMAGE else MoveTarget.CURSOR
            refreshMoveTargetButton()
        }
        // Initial state: no image yet, so disabled.
        refreshMoveTargetButton()

        // Image-stamp panel.
        findViewById<Button>(R.id.btn_load_image).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        findViewById<Button>(R.id.btn_stamp_image).setOnClickListener {
            val n = markView.stampImage()
            Toast.makeText(this, if (n > 0) "Stamped $n channel-pixels" else "Nothing to stamp", Toast.LENGTH_SHORT).show()
            // After STAMP, mode stays in IMAGE for re-positioning + re-stamping (spec §4.1).
        }
        findViewById<Button>(R.id.btn_clear_image).setOnClickListener {
            markView.clearImage()
            // Falling back to CURSOR target since no image.
            markView.moveTarget = MoveTarget.CURSOR
            refreshMoveTargetButton()
        }
        findViewById<Button>(R.id.btn_image_minus).setOnClickListener {
            markView.scaleImage(1f / 1.05f)
        }
        findViewById<Button>(R.id.btn_image_plus).setOnClickListener {
            markView.scaleImage(1.05f)
        }

        // Stamp channel-mode toggle (spec §4.2). W test pattern is enforced ALL_CHANNELS in MarkView.stampImage.
        val btnStampMode = findViewById<Button>(R.id.btn_stamp_channel_mode)
        btnStampMode.setOnClickListener {
            markView.stampChannelMode = if (markView.stampChannelMode == StampChannelMode.SELECTED_CHANNEL)
                StampChannelMode.ALL_CHANNELS else StampChannelMode.SELECTED_CHANNEL
            updateStampChannelModeButton()
        }

        // Initial state.
        updateStampChannelModeButton()
    }

    private fun updateStampChannelModeButton() {
        val btnStampMode = findViewById<Button>(R.id.btn_stamp_channel_mode)
        btnStampMode.text = when (markView.stampChannelMode) {
            StampChannelMode.SELECTED_CHANNEL -> getString(R.string.stamp_mode_selected)
            StampChannelMode.ALL_CHANNELS -> getString(R.string.stamp_mode_all)
        }
        btnStampMode.isEnabled = markView.activeChannel != Channel.W
    }

    private fun loadImageFromUri(uri: Uri) {
        try {
            val bmp = contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it)
            } ?: error("decode returned null")
            markView.loadImage(bmp)
            // Loading does NOT auto-switch MOVE TARGET (spec §4.1) — user toggles explicitly.
            // But the MOVE TARGET button now becomes enabled.
            findViewById<Button>(R.id.btn_move_target).isEnabled = true
            Toast.makeText(this, "Loaded image ${bmp.width}x${bmp.height}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "image load failed", e)
            Toast.makeText(this, "Image load failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun wireDraggables() {
        val prefs = DraggableOverlay.prefsFor(this)
        DraggableOverlay(findViewById(R.id.status_pill), "status_pill", prefs)
        DraggableOverlay(
            dragTarget = findViewById(R.id.dpad_cluster),
            touchTarget = findViewById(R.id.dpad_drag_handle),
            id = "dpad",
            prefs = prefs,
        )
        DraggableOverlay(
            dragTarget = findViewById(R.id.mode_buttons),
            touchTarget = findViewById(R.id.mode_drag_handle),
            id = "mode_buttons",
            prefs = prefs,
        )
    }

    private fun updateStatusPill() {
        val ch = markView.activeChannel
        val sev = markView.brushSeverity
        val brush = markView.brushSize
        val cx = markView.cursorX
        val cy = markView.cursorY
        val text = if (ch.isMarkingChannel) {
            val existing = markView.severityAtCursor()
            if (existing.level > 0) "$ch s:${sev.level} b:$brush ($cx,$cy) marked:${existing.level}->${sev.level}"
            else                    "$ch s:${sev.level} b:$brush ($cx,$cy)"
        } else {
            "W (survey) ($cx,$cy)"
        }
        statusPill.text = text
    }

    companion object {
        private const val TAG = "MarkActivity"
    }
}
