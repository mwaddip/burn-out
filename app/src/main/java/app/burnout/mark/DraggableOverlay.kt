package app.burnout.mark

import android.content.Context
import android.content.SharedPreferences
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

class DraggableOverlay(
    private val dragTarget: View,
    private val touchTarget: View,
    private val id: String,
    private val prefs: SharedPreferences,
) {
    constructor(view: View, id: String, prefs: SharedPreferences) : this(view, view, id, prefs)

    private var dragging = false
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f

    private val detector = GestureDetector(touchTarget.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            dragging = true
            grabOffsetX = e.rawX - dragTarget.x
            grabOffsetY = e.rawY - dragTarget.y
            dragTarget.translationZ = 8f
            // Visual + haptic confirmation that drag mode is engaged.
            dragTarget.animate()
                .scaleX(1.04f).scaleY(1.04f)
                .setDuration(80L).start()
            touchTarget.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    })

    init {
        prefs.getString(prefKey(id), null)?.let { decodePosition(it) }?.let { (savedX, savedY) ->
            dragTarget.post {
                val (cx, cy) = clampToScreen(savedX.toFloat(), savedY.toFloat())
                dragTarget.x = cx
                dragTarget.y = cy
                if (cx.toInt() != savedX || cy.toInt() != savedY) {
                    prefs.edit()
                        .putString(prefKey(id), encodePosition(cx.toInt(), cy.toInt()))
                        .apply()
                }
            }
        }

        // Required so the touch target claims ACTION_DOWN and continues to receive the
        // full gesture sequence — without this, GestureDetector never fires onLongPress.
        touchTarget.isClickable = true
        touchTarget.isLongClickable = true

        touchTarget.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val (cx, cy) = clampToScreen(event.rawX - grabOffsetX, event.rawY - grabOffsetY)
                    dragTarget.x = cx
                    dragTarget.y = cy
                    true
                } else false

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        dragTarget.translationZ = 0f
                        dragTarget.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(80L).start()
                        prefs.edit()
                            .putString(prefKey(id), encodePosition(dragTarget.x.toInt(), dragTarget.y.toInt()))
                            .apply()
                        true
                    } else false
                }

                else -> false
            }
        }
    }

    private fun clampToScreen(x: Float, y: Float): Pair<Float, Float> {
        val parent = dragTarget.parent as? android.view.View ?: return x to y
        val maxX = (parent.width  - dragTarget.width ).coerceAtLeast(0).toFloat()
        val maxY = (parent.height - dragTarget.height).coerceAtLeast(0).toFloat()
        return x.coerceIn(0f, maxX) to y.coerceIn(0f, maxY)
    }

    companion object {
        fun prefsFor(context: Context): SharedPreferences =
            context.getSharedPreferences("draggable_overlay_positions", Context.MODE_PRIVATE)

        private fun prefKey(id: String) = "pos_$id"

        fun encodePosition(x: Int, y: Int): String = "$x,$y"

        fun decodePosition(s: String): Pair<Int, Int>? {
            val parts = s.split(",")
            if (parts.size != 2) return null
            val x = parts[0].toIntOrNull() ?: return null
            val y = parts[1].toIntOrNull() ?: return null
            return x to y
        }
    }
}
