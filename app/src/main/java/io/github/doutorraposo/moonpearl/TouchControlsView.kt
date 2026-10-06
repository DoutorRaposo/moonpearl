package io.github.doutorraposo.moonpearl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * On-screen SNES pad drawn over the SDL surface. It injects keyboard events, which
 * upstream maps to the joypad through [KeyMap] Controls (see [KEYMAP_CONTROLS]).
 */
@SuppressLint("ViewConstructor")
class TouchControlsView(
    context: Context,
    private val sendKey: (keyCode: Int, down: Boolean) -> Unit,
) : View(context) {

    var opacity = 0.5f
        set(value) { field = value.coerceIn(0.1f, 1f); invalidate() }

    private enum class Button(val keyCode: Int, val label: String) {
        A(KeyEvent.KEYCODE_X, "A"),
        B(KeyEvent.KEYCODE_Z, "B"),
        X(KeyEvent.KEYCODE_S, "X"),
        Y(KeyEvent.KEYCODE_A, "Y"),
        L(KeyEvent.KEYCODE_C, "L"),
        R(KeyEvent.KEYCODE_V, "R"),
        SELECT(KeyEvent.KEYCODE_Q, "SELECT"),
        START(KeyEvent.KEYCODE_ENTER, "START"),
    }

    private sealed interface Control
    private data object Dpad : Control
    private data class Press(val button: Button) : Control

    // Layout, computed in onSizeChanged.
    private var dpadX = 0f
    private var dpadY = 0f
    private var dpadRadius = 0f
    private var buttonRadius = 0f
    private val buttonCenters = HashMap<Button, Pair<Float, Float>>()
    private val buttonRects = HashMap<Button, RectF>()
    private var safeLeft = 0
    private var safeRight = 0

    // Input state.
    private val pointerControl = HashMap<Int, Control>()
    private val pointerDpadKeys = HashMap<Int, Set<Int>>()
    private val held = HashSet<Int>()
    private val heldButtons = HashSet<Button>()
    private var dpadKeys: Set<Int> = emptySet()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val arrow = Path()

    init {
        isHapticFeedbackEnabled = true
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cutout = insets.displayCutout
        safeLeft = cutout?.safeInsetLeft ?: 0
        safeRight = cutout?.safeInsetRight ?: 0
        relayout(width, height)
        return super.onApplyWindowInsets(insets)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout(w, h)

    private fun relayout(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val unit = minOf(w, h) / 100f
        val margin = MARGIN * unit
        dpadRadius = 17 * unit
        dpadX = safeLeft + margin + dpadRadius
        dpadY = h - margin - dpadRadius

        buttonRadius = 7 * unit
        val faceX = w - safeRight - margin - dpadRadius
        val faceY = h - margin - dpadRadius
        val d = 10.5f * unit
        buttonCenters[Button.X] = faceX to faceY - d
        buttonCenters[Button.B] = faceX to faceY + d
        buttonCenters[Button.Y] = faceX - d to faceY
        buttonCenters[Button.A] = faceX + d to faceY

        val shoulderW = SHOULDER_WIDTH * unit
        val shoulderH = SHOULDER_HEIGHT * unit
        buttonRects[Button.L] = RectF(safeLeft + margin, margin, safeLeft + margin + shoulderW, margin + shoulderH)
        buttonRects[Button.R] = RectF(w - safeRight - margin - shoulderW, margin, w - safeRight - margin, margin + shoulderH)

        val pillW = 15 * unit
        val pillH = 6 * unit
        val pillY = h - margin - pillH
        buttonRects[Button.SELECT] = RectF(w / 2f - 2 * unit - pillW, pillY, w / 2f - 2 * unit, pillY + pillH)
        buttonRects[Button.START] = RectF(w / 2f + 2 * unit, pillY, w / 2f + 2 * unit + pillW, pillY + pillH)

        text.textSize = 5 * unit
        stroke.strokeWidth = 0.5f * unit
        invalidate()
    }

    // --- Input ---------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                if (hypot(x - dpadX, y - dpadY) <= dpadRadius * 1.4f) {
                    pointerControl[id] = Dpad
                    pointerDpadKeys[id] = dpadDirection(x, y)
                } else {
                    hitButton(x, y)?.let { pointerControl[id] = Press(it) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    when (pointerControl[id]) {
                        Dpad -> pointerDpadKeys[id] = dpadDirection(x, y)
                        // Sliding a finger across the face buttons moves the press, like on a real pad.
                        is Press -> pointerControl[id] = hitButton(x, y)?.let(::Press) ?: pointerControl.getValue(id)
                        null -> hitButton(x, y)?.let { pointerControl[id] = Press(it) }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pointerControl.remove(id)
                pointerDpadKeys.remove(id)
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerControl.clear()
                pointerDpadKeys.clear()
            }
        }
        sync()
        return true
    }

    /** Whether a touch at (x, y) would land on one of the pad's controls. */
    fun isOverControl(x: Float, y: Float) =
        visibility == VISIBLE && (hypot(x - dpadX, y - dpadY) <= dpadRadius * 1.4f || hitButton(x, y) != null)

    /** Releases everything, e.g. when the overlay is hidden or the game is paused. */
    fun releaseAll() {
        pointerControl.clear()
        pointerDpadKeys.clear()
        sync()
    }

    private fun sync() {
        dpadKeys = pointerDpadKeys.values.flatten().toSet()
        val buttons = pointerControl.values.filterIsInstance<Press>().map { it.button }.toSet()
        val wanted = dpadKeys + buttons.map { it.keyCode }

        var pressedSomething = false
        for (code in held - wanted) sendKey(code, false)
        for (code in wanted - held) {
            sendKey(code, true)
            pressedSomething = true
        }
        if (pressedSomething) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (held != wanted || heldButtons != buttons) {
            held.clear(); held += wanted
            heldButtons.clear(); heldButtons += buttons
            invalidate()
        }
    }

    private fun hitButton(x: Float, y: Float): Button? {
        buttonRects.entries.firstOrNull { (_, r) -> r.contains(x, y) }?.let { return it.key }
        return buttonCenters.entries
            .map { (b, c) -> b to hypot(x - c.first, y - c.second) }
            .filter { it.second <= buttonRadius * 1.5f }
            .minByOrNull { it.second }?.first
    }

    /** 8-way direction; diagonals hold two keys. */
    private fun dpadDirection(x: Float, y: Float): Set<Int> {
        val dx = x - dpadX
        val dy = y - dpadY
        if (hypot(dx, dy) < dpadRadius * 0.25f) return emptySet()
        val sector = Math.floorMod(Math.round(Math.toDegrees(atan2(-dy, dx).toDouble()) / 45.0).toInt(), 8)
        return when (sector) {
            0 -> setOf(KeyEvent.KEYCODE_DPAD_RIGHT)
            1 -> setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            2 -> setOf(KeyEvent.KEYCODE_DPAD_UP)
            3 -> setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT)
            4 -> setOf(KeyEvent.KEYCODE_DPAD_LEFT)
            5 -> setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            6 -> setOf(KeyEvent.KEYCODE_DPAD_DOWN)
            else -> setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
        }
    }

    // --- Drawing -------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val idle = (opacity * 90).toInt()
        val active = (opacity * 200).toInt().coerceAtMost(255)
        val outline = (opacity * 200).toInt().coerceAtMost(255)

        // D-pad: base disc plus four arrows; the held direction(s) light up.
        fill.color = Color.argb(idle / 2, 0, 0, 0)
        canvas.drawCircle(dpadX, dpadY, dpadRadius, fill)
        stroke.color = Color.argb(outline, 255, 255, 255)
        canvas.drawCircle(dpadX, dpadY, dpadRadius, stroke)
        drawArrow(canvas, KeyEvent.KEYCODE_DPAD_UP, 0f, -1f, idle, active)
        drawArrow(canvas, KeyEvent.KEYCODE_DPAD_DOWN, 0f, 1f, idle, active)
        drawArrow(canvas, KeyEvent.KEYCODE_DPAD_LEFT, -1f, 0f, idle, active)
        drawArrow(canvas, KeyEvent.KEYCODE_DPAD_RIGHT, 1f, 0f, idle, active)

        for ((button, c) in buttonCenters) {
            val pressed = button in heldButtons
            fill.color = Color.argb(if (pressed) active else idle, 255, 255, 255)
            canvas.drawCircle(c.first, c.second, buttonRadius, fill)
            canvas.drawCircle(c.first, c.second, buttonRadius, stroke)
            drawLabel(canvas, button.label, c.first, c.second, pressed)
        }
        for ((button, r) in buttonRects) {
            val pressed = button in heldButtons
            val radius = r.height() / 2
            fill.color = Color.argb(if (pressed) active else idle, 255, 255, 255)
            canvas.drawRoundRect(r, radius, radius, fill)
            canvas.drawRoundRect(r, radius, radius, stroke)
            val size = text.textSize
            if (button == Button.SELECT || button == Button.START) text.textSize = size * 0.6f
            drawLabel(canvas, button.label, r.centerX(), r.centerY(), pressed)
            text.textSize = size
        }
    }

    private fun drawArrow(canvas: Canvas, keyCode: Int, dx: Float, dy: Float, idle: Int, active: Int) {
        val pressed = keyCode in dpadKeys
        val tip = dpadRadius * 0.85f
        val base = dpadRadius * 0.45f
        val half = dpadRadius * 0.28f
        arrow.reset()
        arrow.moveTo(dpadX + dx * tip, dpadY + dy * tip)
        arrow.lineTo(dpadX + dx * base - dy * half, dpadY + dy * base + dx * half)
        arrow.lineTo(dpadX + dx * base + dy * half, dpadY + dy * base - dx * half)
        arrow.close()
        fill.color = Color.argb(if (pressed) active else idle, 255, 255, 255)
        canvas.drawPath(arrow, fill)
    }

    private fun drawLabel(canvas: Canvas, label: String, x: Float, y: Float, pressed: Boolean) {
        val alpha = (opacity * 255).toInt().coerceAtMost(255)
        text.color = if (pressed) Color.argb(alpha, 0, 0, 0) else Color.argb(alpha, 255, 255, 255)
        canvas.drawText(label, x, y - (text.descent() + text.ascent()) / 2, text)
    }

    companion object {
        // Layout in units of 1% of the screen's short side; MenuButtonView sits next to R.
        const val MARGIN = 6f
        const val SHOULDER_WIDTH = 22f
        const val SHOULDER_HEIGHT = 10f

        /**
         * Value written to [KeyMap] Controls (order: Up, Down, Left, Right, Select, Start, A, B, X, Y, L, R).
         * Must match the key codes in [Button] and [dpadDirection]. Upstream binds Select to Right Shift,
         * but a held modifier turns other keys into Shift+key, so releasing them while Select is down
         * would leave them stuck. Every key here is therefore a plain, unmodified key.
         */
        const val KEYMAP_CONTROLS = "Up, Down, Left, Right, q, Return, x, z, s, a, c, v"
    }
}
