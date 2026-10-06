package io.github.doutorraposo.z3

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.hypot

/**
 * The on-screen "⋯" button that opens the in-game menu. It covers the whole screen but only
 * claims touches inside its circle, so everything else falls through to the pad and the game.
 */
@SuppressLint("ViewConstructor")
class MenuButtonView(context: Context, private val onClick: () -> Unit) : View(context) {
    var opacity = 0.5f
        set(value) { field = value.coerceIn(0.1f, 1f); invalidate() }

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var safeRight = 0
    private var pressed = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        safeRight = insets.displayCutout?.safeInsetRight ?: 0
        relayout(width, height)
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout(w, h)

    private fun relayout(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val unit = minOf(w, h) / 100f
        radius = 4.5f * unit
        // Just left of the R shoulder button, on the same line.
        cx = w - safeRight - (TouchControlsView.MARGIN + TouchControlsView.SHOULDER_WIDTH + 3f) * unit - radius
        cy = (TouchControlsView.MARGIN + TouchControlsView.SHOULDER_HEIGHT / 2) * unit
        stroke.strokeWidth = 0.5f * unit
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inside = hypot(event.x - cx, event.y - cy) <= radius * 1.5f
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!inside) return false
                pressed = true
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (pressed && inside) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                }
                pressed = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = if (pressed) (opacity * 200).toInt() else (opacity * 90).toInt()
        fill.color = Color.argb(alpha.coerceAtMost(255), 255, 255, 255)
        canvas.drawCircle(cx, cy, radius, fill)
        stroke.color = Color.argb((opacity * 200).toInt().coerceAtMost(255), 255, 255, 255)
        canvas.drawCircle(cx, cy, radius, stroke)
        fill.color = Color.argb((opacity * 255).toInt().coerceAtMost(255), 255, 255, 255)
        val dot = radius * 0.13f
        for (i in -1..1) canvas.drawCircle(cx + i * radius * 0.42f, cy, dot, fill)
    }
}
