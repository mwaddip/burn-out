package app.burnout.mark

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock

/**
 * Cursor + highlight, in alternating phases so something is always visible:
 *
 *  - "On" phase  (first half of period): the brush extent is filled in inverse-pattern
 *    color — the actual pixels that would be marked on confirm.
 *  - "Off" phase (second half of period): the brush extent shows through, but a
 *    3-px-thick square ring is drawn around it, with its inner edge 25 px away
 *    from the brush's edges.
 *
 * Period is 2000 ms (1000 ms each phase).
 */
class CursorRenderer(private val color: () -> Int) {

    private val paint = Paint().apply {
        isAntiAlias = false
        style = Paint.Style.FILL
    }

    private val periodMs = 2000L
    private val highlightOffset = 25
    private val ringThickness = 3

    fun isCursorPhase(): Boolean {
        val phase = SystemClock.uptimeMillis() % periodMs
        return phase < periodMs / 2
    }

    fun draw(canvas: Canvas, x: Int, y: Int, brushSize: Int) {
        paint.color = color()

        val half = (brushSize - 1) / 2
        val rest = brushSize / 2
        val left   = x - half
        val top    = y - half
        val rightX = x + rest + 1     // exclusive
        val bottomY = y + rest + 1    // exclusive

        if (isCursorPhase()) {
            canvas.drawRect(left.toFloat(), top.toFloat(), rightX.toFloat(), bottomY.toFloat(), paint)
        } else {
            val innerLeft   = left   - highlightOffset
            val innerTop    = top    - highlightOffset
            val innerRight  = rightX + highlightOffset
            val innerBottom = bottomY + highlightOffset
            val outerLeft   = innerLeft   - ringThickness
            val outerTop    = innerTop    - ringThickness
            val outerRight  = innerRight  + ringThickness
            val outerBottom = innerBottom + ringThickness

            canvas.drawRect(outerLeft.toFloat(), outerTop.toFloat(), outerRight.toFloat(), innerTop.toFloat(), paint)
            canvas.drawRect(outerLeft.toFloat(), innerBottom.toFloat(), outerRight.toFloat(), outerBottom.toFloat(), paint)
            canvas.drawRect(outerLeft.toFloat(), innerTop.toFloat(), innerLeft.toFloat(), innerBottom.toFloat(), paint)
            canvas.drawRect(innerRight.toFloat(), innerTop.toFloat(), outerRight.toFloat(), innerBottom.toFloat(), paint)
        }
    }
}
