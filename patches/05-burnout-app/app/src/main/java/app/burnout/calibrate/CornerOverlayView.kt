package app.burnout.calibrate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay for Calibrate's Add-zone corner-tap UX. Sits between glView and the
 * controls panel; non-clickable so touches fall through to glView's OnTouchListener. Renders
 * up to two corner markers and the in-progress rectangle using mask-pixel coords scaled into
 * view-pixel space.
 */
class CornerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var corner1: Pair<Int, Int>? = null
    private var corner2: Pair<Int, Int>? = null
    private var maskW: Int = 0
    private var maskH: Int = 0

    private val markerPaint = Paint().apply {
        color = 0xFFFFAA00.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val rectFill = Paint().apply {
        color = 0x40FFAA00.toInt()
        style = Paint.Style.FILL
    }
    private val rectBorder = Paint().apply {
        color = 0xFFFFAA00.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    fun update(c1: Pair<Int, Int>?, c2: Pair<Int, Int>?, mw: Int, mh: Int) {
        corner1 = c1
        corner2 = c2
        maskW = mw
        maskH = mh
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (maskW <= 0 || maskH <= 0) return
        val sx = width.toFloat() / maskW
        val sy = height.toFloat() / maskH
        if (corner1 != null && corner2 != null) {
            val (x1, y1) = corner1!!
            val (x2, y2) = corner2!!
            val left = minOf(x1, x2) * sx
            val top = minOf(y1, y2) * sy
            val right = maxOf(x1, x2) * sx
            val bottom = maxOf(y1, y2) * sy
            canvas.drawRect(left, top, right, bottom, rectFill)
            canvas.drawRect(left, top, right, bottom, rectBorder)
        }
        corner1?.let { (mx, my) -> canvas.drawCircle(mx * sx, my * sy, 18f, markerPaint) }
        corner2?.let { (mx, my) -> canvas.drawCircle(mx * sx, my * sy, 18f, markerPaint) }
    }
}
