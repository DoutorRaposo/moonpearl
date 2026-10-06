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
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The rewind panel at the bottom of the screen, as on Nintendo Switch Online, so the game above
 * shows each moment as it goes back: how far back it is, the buffer as a bar (oldest on the left,
 * the present on the right) that can be touched or dragged to jump to a point, and buttons to hold
 * for going back or forward, continue from here, or cancel back to the present. Controllers use LT, RT, A and B (GameActivity). Touches
 * outside the panel fall through to the pad below.
 */
@SuppressLint("ViewConstructor")
class RewindOverlayView(
    context: Context,
    private val onDirection: (Int) -> Unit,
    private val onSeek: (stepsBack: Int) -> Unit,
    private val onResume: () -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {
    private var stepsBack = 0
    private var steps = 0
    private var maxSteps = 600

    /** Position from rewind.c: snapshots back from the present, kept, and at most. */
    fun update(stepsBack: Int, steps: Int, maxSteps: Int) {
        // While dragging, the finger decides where the marker is; rewind.c catches up.
        val back = if (dragging) this.stepsBack else stepsBack
        if (back == this.stepsBack && steps == this.steps && maxSteps == this.maxSteps) return
        this.stepsBack = back
        this.steps = steps
        this.maxSteps = maxSteps.coerceAtLeast(1)
        invalidate()
    }

    private enum class Btn { BACK, FORWARD, RESUME, CANCEL }

    private val panel = RectF()
    private val bar = RectF()
    private val barTouch = RectF()
    private var dragging = false
    private val buttons = LinkedHashMap<Btn, RectF>()
    private var pressed: Btn? = null
    private var unit = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        unit = minOf(w, h) / 100f
        val width = minOf(w * 0.9f, 170 * unit)
        val height = 35 * unit
        panel.set((w - width) / 2, h - 3 * unit - height, (w + width) / 2, h - 3 * unit)
        val pad = 4 * unit
        bar.set(panel.left + pad, panel.top + 12 * unit, panel.right - pad, panel.top + 14 * unit)
        barTouch.set(bar.left - 2 * unit, panel.top + 9 * unit, bar.right + 2 * unit, panel.top + 16.5f * unit)
        val top = panel.top + 17 * unit
        val bottom = top + 10 * unit
        val gap = 2 * unit
        val each = (panel.width() - 2 * pad - 3 * gap) / 4
        Btn.entries.forEachIndexed { i, b ->
            val left = panel.left + pad + i * (each + gap)
            buttons[b] = RectF(left, top, left + each, bottom)
        }
    }

    /** Snapshots back from the present at a point of the bar, within what is kept. */
    private fun stepsBackAt(x: Float) =
        ((bar.right - x) / bar.width() * maxSteps).roundToInt().coerceIn(0, steps)

    private fun seek(x: Float) {
        val back = stepsBackAt(x)
        if (back == stepsBack) return
        stepsBack = back
        onSeek(back)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && barTouch.contains(event.x, event.y)) {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            seek(event.x)
            return true
        }
        if (dragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> seek(event.x)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    invalidate()
                }
            }
            return true
        }
        val hit = buttons.entries.firstOrNull { it.value.contains(event.x, event.y) }?.key
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!panel.contains(event.x, event.y)) return false
                pressed = hit
                when (hit) {
                    Btn.BACK -> onDirection(-1)
                    Btn.FORWARD -> onDirection(1)
                    else -> Unit
                }
                if (hit != null) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = pressed
                pressed = null
                invalidate()
                if (was == Btn.BACK || was == Btn.FORWARD) onDirection(0)
                if (event.actionMasked == MotionEvent.ACTION_UP && was == hit) {
                    if (hit == Btn.RESUME) onResume() else if (hit == Btn.CANCEL) onCancel()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = Color.argb(225, 0x0B, 0x0F, 0x0C)
        canvas.drawRoundRect(panel, 3 * unit, 3 * unit, fill)

        val seconds = stepsBack / 10f
        text.color = Color.WHITE
        text.textSize = 5 * unit
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(context.getString(R.string.rewind_title), panel.left + 4 * unit, panel.top + 8 * unit, text)
        text.textAlign = Paint.Align.RIGHT
        val time = when {
            steps == 0 -> context.getString(R.string.rewind_empty)
            stepsBack == 0 -> context.getString(R.string.rewind_now)
            else -> String.format(Locale.getDefault(), "−%.1f s", seconds)
        }
        canvas.drawText(time, panel.right - 4 * unit, panel.top + 8 * unit, text)

        // A fixed scale (the longest the buffer keeps), the present at the right: right after a
        // load only a short stretch exists, and the bar shows it. The marker is where the game is.
        val r = bar.height() / 2
        fill.color = Color.argb(40, 255, 255, 255)
        canvas.drawRoundRect(bar, r, r, fill)
        val oldest = bar.right - bar.width() * steps / maxSteps
        val at = bar.right - bar.width() * stepsBack / maxSteps
        fill.color = Color.argb(110, 255, 255, 255)
        canvas.drawRoundRect(RectF(oldest, bar.top, bar.right, bar.bottom), r, r, fill)
        fill.color = Color.rgb(0xE2, 0xC1, 0x5A)
        if (at > oldest) canvas.drawRoundRect(RectF(oldest, bar.top, at, bar.bottom), r, r, fill)
        canvas.drawCircle(at, bar.centerY(), (if (dragging) 3.2f else 2.2f) * unit, fill)

        for ((b, r) in buttons) {
            val down = pressed == b
            fill.color = if (b == Btn.RESUME) Color.rgb(0xE2, 0xC1, 0x5A) else Color.argb(if (down) 140 else 60, 255, 255, 255)
            if (b == Btn.RESUME && down) fill.color = Color.rgb(0xC9, 0xA9, 0x45)
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
            val ink = if (b == Btn.RESUME) Color.BLACK else Color.WHITE
            when (b) {
                Btn.BACK, Btn.FORWARD -> drawArrows(canvas, r, if (b == Btn.BACK) -1 else 1, ink)
                Btn.RESUME, Btn.CANCEL -> {
                    text.color = ink
                    text.textSize = 4 * unit
                    text.textAlign = Paint.Align.CENTER
                    val label = context.getString(if (b == Btn.RESUME) R.string.rewind_resume else R.string.rewind_cancel)
                    canvas.drawText(label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
                }
            }
        }

        text.color = Color.argb(190, 255, 255, 255)
        text.textSize = 3.2f * unit
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(context.getString(R.string.rewind_controller_hint), panel.centerX(), panel.bottom - 2.5f * unit, text)
    }

    private fun drawArrows(canvas: Canvas, r: RectF, dir: Int, color: Int) {
        fill.color = color
        val h = r.height() * 0.22f
        val w = h * dir
        for (base in listOf(r.centerX() - w, r.centerX())) {
            path.reset()
            path.moveTo(base, r.centerY() - h)
            path.lineTo(base + w, r.centerY())
            path.lineTo(base, r.centerY() + h)
            path.close()
            canvas.drawPath(path, fill)
        }
    }
}
