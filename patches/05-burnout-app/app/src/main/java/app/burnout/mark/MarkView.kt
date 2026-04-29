package app.burnout.mark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Per spec §4.1: arrow buttons step the painting cursor (CURSOR) or nudge the loaded image overlay (IMAGE). */
enum class MoveTarget { CURSOR, IMAGE }

/** Per spec §4.2: stamp writes only the channel matching the active test pattern (R/G/B) or all three. W test pattern forces ALL_CHANNELS. */
enum class StampChannelMode { SELECTED_CHANNEL, ALL_CHANNELS }

class MarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var mask: WearMask = WearMask(1, 1)
        set(value) {
            field = value
            invalidate()
        }

    var activeChannel: Channel = Channel.R
        set(value) { field = value; invalidate() }

    var brushSeverity: Severity = Severity(1)
        set(value) { field = value; invalidate() }

    var brushSize: Int = 1
        set(value) {
            field = value.coerceIn(1, 10)
            invalidate()
        }

    var cursorX: Int = 0
        private set
    var cursorY: Int = 0
        private set

    var onCursorChanged: ((Int, Int) -> Unit)? = null

    /** Explicit MOVE TARGET state (spec §4.1). MarkActivity drives this via setter. */
    var moveTarget: MoveTarget = MoveTarget.CURSOR
        set(value) { field = value; invalidate() }

    /** A/B hold-to-toggle (spec §4.1): when true, paint overlay is hidden, raw test pattern shown. */
    var hidePaintedOverlay: Boolean = false
        set(value) { field = value; invalidate() }

    /** Image-stamp channel mode (spec §4.2). MarkActivity drives this; W pattern forces ALL_CHANNELS. */
    var stampChannelMode: StampChannelMode = StampChannelMode.SELECTED_CHANNEL
        set(value) { field = value; invalidate() }

    // --- Image overlay state ---
    private var imageBitmap: Bitmap? = null
    private var imageX: Float = 0f
    private var imageY: Float = 0f
    private var imageScale: Float = 1f
    private var imageGrabOffsetX: Float = 0f
    private var imageGrabOffsetY: Float = 0f
    private val imageMatrix = Matrix()
    private val imagePaint = Paint().apply { isAntiAlias = false; alpha = 128 }
    // ---

    private val bgPaint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
    private val markPaint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL; color = android.graphics.Color.BLACK }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            imageBitmap ?: return false
            val newScale = (imageScale * detector.scaleFactor).coerceIn(0.1f, 20f)
            val actualFactor = newScale / imageScale
            val fx = detector.focusX
            val fy = detector.focusY
            imageX = fx - (fx - imageX) * actualFactor
            imageY = fy - (fy - imageY) * actualFactor
            imageScale = newScale
            invalidate()
            return true
        }
    })

    private val cursor = CursorRenderer { TestPattern.forChannel(activeChannel).cursorColor }

    private var lastBlinkPhase: Boolean? = null

    private val blinkTicker = object : Runnable {
        override fun run() {
            val phase = cursor.isCursorPhase()
            if (phase != lastBlinkPhase) {
                lastBlinkPhase = phase
                invalidate()
            }
            postDelayed(this, 50)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(blinkTicker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkTicker)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (mask.width != w || mask.height != h) {
            mask = WearMask(w, h)
        }
        if (oldw == 0 && oldh == 0) {
            cursorX = w / 2
            cursorY = h / 2
            onCursorChanged?.invoke(cursorX, cursorY)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val pattern = TestPattern.forChannel(activeChannel)
        bgPaint.color = pattern.backgroundColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Image overlay (semi-transparent) — drawn before marks so STAMPed marks
        // remain clearly visible as black on top of the still-shown image.
        imageBitmap?.let { img ->
            imageMatrix.reset()
            imageMatrix.postScale(imageScale, imageScale)
            imageMatrix.postTranslate(imageX, imageY)
            canvas.drawBitmap(img, imageMatrix, imagePaint)
        }

        // Marked pixels in the active channel rendered as black (simulating burn-in look).
        // Hidden when A/B hold-to-toggle is active (spec §4.1).
        if (!hidePaintedOverlay && activeChannel.isMarkingChannel) {
            mask.forEachMarked(activeChannel) { x, y ->
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + 1).toFloat(), (y + 1).toFloat(), markPaint)
            }
        }

        cursor.draw(canvas, cursorX, cursorY, brushSize)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true

        // Touch routing follows MOVE TARGET (spec §4.1):
        //   IMAGE  -> drag the loaded overlay
        //   CURSOR -> teleport the cursor on tap
        if (moveTarget == MoveTarget.IMAGE && imageBitmap != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    imageGrabOffsetX = event.x - imageX
                    imageGrabOffsetY = event.y - imageY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    imageX = event.x - imageGrabOffsetX
                    imageY = event.y - imageGrabOffsetY
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> return true
            }
        } else {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                cursorX = event.x.toInt().coerceIn(0, width - 1)
                cursorY = event.y.toInt().coerceIn(0, height - 1)
                onCursorChanged?.invoke(cursorX, cursorY)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun scaleImage(factor: Float) {
        val img = imageBitmap ?: return
        val newScale = (imageScale * factor).coerceIn(0.05f, 20f)
        val actualFactor = newScale / imageScale
        val cx = imageX + img.width  * imageScale / 2f
        val cy = imageY + img.height * imageScale / 2f
        imageX = cx - (cx - imageX) * actualFactor
        imageY = cy - (cy - imageY) * actualFactor
        imageScale = newScale
        invalidate()
    }

    fun nudgeImage(dx: Int, dy: Int) {
        if (imageBitmap == null) return
        imageX += dx
        imageY += dy
        invalidate()
    }

    fun loadImage(bitmap: Bitmap) {
        imageBitmap?.recycle()
        imageBitmap = bitmap
        imageScale = 1f
        imageX = ((width  - bitmap.width ).toFloat() / 2f).coerceAtLeast(0f)
        imageY = ((height - bitmap.height).toFloat() / 2f).coerceAtLeast(0f)
        invalidate()
    }

    fun hasImage(): Boolean = imageBitmap != null

    fun clearImage() {
        imageBitmap?.recycle()
        imageBitmap = null
        invalidate()
    }

    /**
     * Channel-bucketed image stamp per spec §4.2.
     * For each source pixel:
     *   - if alpha < 32 (mostly transparent), skip
     *   - if stampChannelMode == SELECTED_CHANNEL and activeChannel is R/G/B,
     *     write only that channel's value to the mask
     *   - else (ALL_CHANNELS or W active pattern), write all three RGB values
     * Each written value is the source byte (0..255) directly — WearMask.markRaw preserves
     * intensity within the format. The shader currently treats marked as binary
     * (max(r,g,b) > 0.001), but the encoded severity is available for future severity-aware
     * shader updates.
     *
     * Returns count of mask cells written (one per (pixel,channel) write).
     */
    fun stampImage(): Int {
        val img = imageBitmap ?: return 0
        val s = imageScale
        val rx = imageX
        val ry = imageY

        // W test pattern forces ALL_CHANNELS (spec §4.2 — ambiguous which channel to pick).
        val effectiveMode = if (activeChannel == Channel.W) StampChannelMode.ALL_CHANNELS else stampChannelMode

        var count = 0
        for (by in 0 until img.height) {
            for (bx in 0 until img.width) {
                val argb = img.getPixel(bx, by)
                if ((argb ushr 24) and 0xFF < 32) continue  // transparency cutoff
                val srcR = (argb shr 16) and 0xFF
                val srcG = (argb shr 8) and 0xFF
                val srcB = argb and 0xFF

                val sx0 = (rx + bx * s).toInt()
                val sy0 = (ry + by * s).toInt()
                val sx1 = (rx + (bx + 1) * s).toInt().coerceAtLeast(sx0 + 1)
                val sy1 = (ry + (by + 1) * s).toInt().coerceAtLeast(sy0 + 1)

                for (y in sy0 until sy1) {
                    for (x in sx0 until sx1) {
                        if (x !in 0 until width || y !in 0 until height) continue
                        when (effectiveMode) {
                            StampChannelMode.ALL_CHANNELS -> {
                                if (srcR > 0) { mask.markRaw(x, y, Channel.R, srcR); count++ }
                                if (srcG > 0) { mask.markRaw(x, y, Channel.G, srcG); count++ }
                                if (srcB > 0) { mask.markRaw(x, y, Channel.B, srcB); count++ }
                            }
                            StampChannelMode.SELECTED_CHANNEL -> {
                                val (ch, v) = when (activeChannel) {
                                    Channel.R -> Channel.R to srcR
                                    Channel.G -> Channel.G to srcG
                                    Channel.B -> Channel.B to srcB
                                    Channel.W -> error("unreachable — handled above")
                                }
                                if (v > 0) { mask.markRaw(x, y, ch, v); count++ }
                            }
                        }
                    }
                }
            }
        }
        invalidate()
        return count
    }

    fun nudgeCursor(dx: Int, dy: Int) {
        cursorX = (cursorX + dx).coerceIn(0, width - 1)
        cursorY = (cursorY + dy).coerceIn(0, height - 1)
        onCursorChanged?.invoke(cursorX, cursorY)
        invalidate()
    }

    /**
     * Confirm at cursor.
     *  - Brush 1: cycle behavior (preserves fine-adjust + erase via 1->2->3->4->0->1).
     *  - Brush > 1: stamp brushSeverity across the NxN brush area. Out-of-bounds pixels
     *    are silently ignored by WearMask.mark().
     */
    fun confirmAtCursor() {
        if (!activeChannel.isMarkingChannel) return
        if (brushSize == 1) {
            val current = mask.getSeverity(cursorX, cursorY, activeChannel)
            if (current.level == 0) {
                mask.mark(cursorX, cursorY, activeChannel, brushSeverity)
            } else {
                mask.cycleAtPixel(cursorX, cursorY, activeChannel)
            }
        } else {
            val half = (brushSize - 1) / 2
            val rest = brushSize / 2
            for (dy in -half..rest) {
                for (dx in -half..rest) {
                    mask.mark(cursorX + dx, cursorY + dy, activeChannel, brushSeverity)
                }
            }
        }
        invalidate()
    }

    fun severityAtCursor(): Severity =
        if (activeChannel.isMarkingChannel) mask.getSeverity(cursorX, cursorY, activeChannel)
        else Severity(0)
}
