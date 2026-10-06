package io.github.doutorraposo.moonpearl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import io.github.doutorraposo.moonpearl.TouchLayout.Element
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * On-screen SNES pad drawn over the SDL surface, plus the "⋯" menu button and the optional
 * fast-forward and rewind buttons, which act while held. Buttons inject keyboard events, which upstream maps to the joypad
 * through [KeyMap] Controls (see [KEYMAP_CONTROLS]). Where each control sits comes from a
 * [TouchLayout]; in [editMode] touches move controls around instead of pressing them.
 */
@SuppressLint("ViewConstructor")
class TouchControlsView(
    context: Context,
    private val sendKey: (keyCode: Int, down: Boolean) -> Unit = { _, _ -> },
    private val onMenu: () -> Unit = {},
    /** The fast-forward or rewind button was pressed or released. */
    private val onHold: (element: Element, down: Boolean) -> Unit = { _, _ -> },
) : View(context) {

    var opacity = 0.5f
        set(value) { field = value.coerceIn(0.1f, 1f); invalidate() }

    var layout = TouchLayout.DEFAULT
        set(value) { field = value; relayout(width, height) }

    /** Controls in use; the others are neither drawn nor touchable. */
    var elements: Set<Element> = Element.entries.toSet()
        set(value) { field = value; releaseAll(); invalidate() }

    /** False hides the pad (e.g. while a controller is in use) but keeps the menu button. */
    var padVisible = true
        set(value) {
            if (field == value) return
            field = value
            releaseHidden()
            invalidate()
        }

    /** While the rewind panel is up, the rewind button stays even with the pad hidden. */
    var rewinding = false
        set(value) {
            field = value
            releaseHidden()
            invalidate()
        }

    /** Short status shown on the menu button instead of the dots, e.g. the fast-forward rate. */
    var menuBadge: String? = null
        set(value) { field = value; invalidate() }

    var editMode = false
    var selected: Element? = null
        set(value) { field = value; invalidate() }
    /** Edit mode: a control was picked up, or null for a tap on an empty area. */
    var onSelect: (Element?) -> Unit = {}
    var onLayoutChange: (TouchLayout) -> Unit = {}

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
    private data object Menu : Control
    private data class Hold(val element: Element) : Control
    private data class Press(val button: Button) : Control

    // Layout, computed in relayout.
    private var screen = TouchLayout.Screen(0, 0)
    private var dpadX = 0f
    private var dpadY = 0f
    private var dpadRadius = 0f
    private var buttonRadius = 0f
    private var menuRadius = 0f
    private val buttonCenters = HashMap<Button, TouchLayout.Point>()
    private val buttonRects = HashMap<Button, RectF>()
    private var menuCenter = TouchLayout.Point(0f, 0f)
    private val holdCenters = HashMap<Element, TouchLayout.Point>()
    private val holdRadii = HashMap<Element, Float>()
    private val bounds = HashMap<Element, RectF>()
    private var safeLeft = 0
    private var safeRight = 0

    // Input state.
    private val pointerControl = HashMap<Int, Control>()
    private val pointerDpadKeys = HashMap<Int, Set<Int>>()
    private val held = HashSet<Int>()
    private val heldButtons = HashSet<Button>()
    private var dpadKeys: Set<Int> = emptySet()
    private var menuHeld = false
    private val heldHolds = HashSet<Element>()

    // Edit state: the dragged control and where it was grabbed, relative to its center.
    private var dragging: Element? = null
    private var dragDx = 0f
    private var dragDy = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val selection = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
        screen = TouchLayout.Screen(w, h, safeLeft, safeRight)
        val unit = screen.unit
        fun center(e: Element) = layout.center(e, screen)
        fun size(e: Element) = unit * layout[e].scale
        fun box(c: TouchLayout.Point, halfW: Float, halfH: Float) = RectF(c.x - halfW, c.y - halfH, c.x + halfW, c.y + halfH)

        center(Element.DPAD).let {
            dpadX = it.x
            dpadY = it.y
            dpadRadius = 17 * size(Element.DPAD)
            bounds[Element.DPAD] = box(it, dpadRadius, dpadRadius)
        }

        center(Element.FACE).let { c ->
            val s = size(Element.FACE)
            buttonRadius = 7 * s
            val d = 10.5f * s
            buttonCenters[Button.X] = TouchLayout.Point(c.x, c.y - d)
            buttonCenters[Button.B] = TouchLayout.Point(c.x, c.y + d)
            buttonCenters[Button.Y] = TouchLayout.Point(c.x - d, c.y)
            buttonCenters[Button.A] = TouchLayout.Point(c.x + d, c.y)
            bounds[Element.FACE] = box(c, d + buttonRadius, d + buttonRadius)
        }

        for ((e, b) in listOf(Element.L to Button.L, Element.R to Button.R)) {
            val s = size(e)
            buttonRects[b] = box(center(e), 11 * s, 5 * s).also { bounds[e] = it }
        }
        for ((e, b) in listOf(Element.SELECT to Button.SELECT, Element.START to Button.START)) {
            val s = size(e)
            buttonRects[b] = box(center(e), 7.5f * s, 3 * s).also { bounds[e] = it }
        }

        menuCenter = center(Element.MENU)
        menuRadius = 4.5f * size(Element.MENU)
        bounds[Element.MENU] = box(menuCenter, menuRadius, menuRadius)
        for (e in HOLD_ELEMENTS) {
            val c = center(e)
            val r = 4.5f * size(e)
            holdCenters[e] = c
            holdRadii[e] = r
            bounds[e] = box(c, r, r)
        }

        text.textSize = 5 * unit
        stroke.strokeWidth = 0.5f * unit
        selection.strokeWidth = 0.6f * unit
        selection.pathEffect = DashPathEffect(floatArrayOf(2 * unit, 1.5f * unit), 0f)
        invalidate()
    }

    private fun shown(e: Element) =
        e in elements && (padVisible || e == Element.MENU || (rewinding && e == Element.REWIND))

    private fun elementOf(control: Control) = when (control) {
        Dpad -> Element.DPAD
        Menu -> Element.MENU
        is Hold -> control.element
        is Press -> when (control.button) {
            Button.A, Button.B, Button.X, Button.Y -> Element.FACE
            Button.L -> Element.L
            Button.R -> Element.R
            Button.SELECT -> Element.SELECT
            Button.START -> Element.START
        }
    }

    /** Lets go of the controls that are no longer shown; a finger on one still shown keeps it. */
    private fun releaseHidden() {
        val gone = pointerControl.filterValues { !shown(elementOf(it)) }.keys
        pointerControl.keys.removeAll(gone)
        pointerDpadKeys.keys.removeAll(gone)
        sync()
    }

    private fun buttonShown(b: Button) = when (b) {
        Button.A, Button.B, Button.X, Button.Y -> shown(Element.FACE)
        Button.L -> shown(Element.L)
        Button.R -> shown(Element.R)
        Button.SELECT -> shown(Element.SELECT)
        Button.START -> shown(Element.START)
    }

    // --- Input ---------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return onEditTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                controlAt(x, y)?.let { control ->
                    pointerControl[id] = control
                    if (control == Dpad) pointerDpadKeys[id] = dpadDirection(x, y)
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
                        Menu, is Hold -> Unit
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val control = pointerControl.remove(id)
                pointerDpadKeys.remove(id)
                if (control == Menu && hitMenu(event.getX(i), event.getY(i))) {
                    sync()
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onMenu()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerControl.clear()
                pointerDpadKeys.clear()
            }
        }
        sync()
        return true
    }

    private fun controlAt(x: Float, y: Float): Control? {
        if (hitMenu(x, y)) return Menu
        hitHold(x, y)?.let { return Hold(it) }
        if (shown(Element.DPAD) && hypot(x - dpadX, y - dpadY) <= dpadRadius * 1.4f) return Dpad
        return hitButton(x, y)?.let(::Press)
    }

    private fun hitHold(x: Float, y: Float): Element? = HOLD_ELEMENTS.firstOrNull { e ->
        shown(e) && hypot(x - holdCenters.getValue(e).x, y - holdCenters.getValue(e).y) <= holdRadii.getValue(e) * 1.5f
    }

    private fun hitMenu(x: Float, y: Float) =
        shown(Element.MENU) && hypot(x - menuCenter.x, y - menuCenter.y) <= menuRadius * 1.5f

    /** Whether a touch at (x, y) would land on one of the controls. */
    fun isOverControl(x: Float, y: Float) = controlAt(x, y) != null

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
        val menu = Menu in pointerControl.values
        val holds = pointerControl.values.filterIsInstance<Hold>().map { it.element }.toSet()
        if (held == wanted && heldButtons == buttons && menu == menuHeld && holds == heldHolds) return

        // Record the new state before calling out: a callback may change the pad (the rewind
        // button hides it) and sync again, which must not see the same presses as new.
        val released = held - wanted
        val pressed = wanted - held
        val holdsReleased = heldHolds - holds
        val holdsPressed = holds - heldHolds
        held.clear(); held += wanted
        heldButtons.clear(); heldButtons += buttons
        menuHeld = menu
        heldHolds.clear(); heldHolds += holds
        invalidate()

        for (code in released) sendKey(code, false)
        for (code in pressed) sendKey(code, true)
        for (e in holdsReleased) onHold(e, false)
        for (e in holdsPressed) onHold(e, true)
        if (pressed.isNotEmpty() || holdsPressed.isNotEmpty()) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun hitButton(x: Float, y: Float): Button? {
        buttonRects.entries.firstOrNull { (b, r) -> buttonShown(b) && r.contains(x, y) }?.let { return it.key }
        return buttonCenters.entries
            .filter { buttonShown(it.key) }
            .map { (b, c) -> b to hypot(x - c.x, y - c.y) }
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

    // --- Editing -------------------------------------------------------------

    private fun onEditTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val slop = 3 * screen.unit
                // The smallest control under the finger wins, so a pill inside the d-pad's box stays reachable.
                val hit = elements
                    .filter { bounds[it]?.let { r -> RectF(r).apply { inset(-slop, -slop) }.contains(event.x, event.y) } == true }
                    .minByOrNull { bounds.getValue(it).width() * bounds.getValue(it).height() }
                dragging = hit
                if (hit != null) {
                    val c = layout.center(hit, screen)
                    dragDx = event.x - c.x
                    dragDy = event.y - c.y
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                selected = hit ?: selected
                onSelect(hit)
            }
            MotionEvent.ACTION_MOVE -> dragging?.let { e ->
                val x = (event.x - dragDx).coerceIn(0f, screen.width.toFloat())
                val y = (event.y - dragDy).coerceIn(0f, screen.height.toFloat())
                layout = layout.moved(e, x, y, screen)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != null) onLayoutChange(layout)
                dragging = null
            }
        }
        return true
    }

    // --- Drawing -------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val idle = (opacity * 90).toInt()
        val active = (opacity * 200).toInt().coerceAtMost(255)
        val outline = (opacity * 200).toInt().coerceAtMost(255)
        stroke.color = Color.argb(outline, 255, 255, 255)

        if (shown(Element.DPAD)) {
            // Base disc plus four arrows; the held direction(s) light up.
            fill.color = Color.argb(idle / 2, 0, 0, 0)
            canvas.drawCircle(dpadX, dpadY, dpadRadius, fill)
            canvas.drawCircle(dpadX, dpadY, dpadRadius, stroke)
            drawArrow(canvas, KeyEvent.KEYCODE_DPAD_UP, 0f, -1f, idle, active)
            drawArrow(canvas, KeyEvent.KEYCODE_DPAD_DOWN, 0f, 1f, idle, active)
            drawArrow(canvas, KeyEvent.KEYCODE_DPAD_LEFT, -1f, 0f, idle, active)
            drawArrow(canvas, KeyEvent.KEYCODE_DPAD_RIGHT, 1f, 0f, idle, active)
        }

        val baseText = text.textSize
        for ((button, c) in buttonCenters) {
            if (!buttonShown(button)) continue
            val pressed = button in heldButtons
            fill.color = Color.argb(if (pressed) active else idle, 255, 255, 255)
            canvas.drawCircle(c.x, c.y, buttonRadius, fill)
            canvas.drawCircle(c.x, c.y, buttonRadius, stroke)
            text.textSize = baseText * layout[Element.FACE].scale
            drawLabel(canvas, button.label, c.x, c.y, pressed)
        }
        for ((button, r) in buttonRects) {
            if (!buttonShown(button)) continue
            val pressed = button in heldButtons
            val radius = r.height() / 2
            fill.color = Color.argb(if (pressed) active else idle, 255, 255, 255)
            canvas.drawRoundRect(r, radius, radius, fill)
            canvas.drawRoundRect(r, radius, radius, stroke)
            text.textSize = r.height() / 2
            drawLabel(canvas, button.label, r.centerX(), r.centerY(), pressed)
        }
        text.textSize = baseText

        if (shown(Element.MENU)) drawMenu(canvas, idle, active)
        for (e in HOLD_ELEMENTS) if (shown(e)) drawHold(canvas, e, idle, active)

        if (editMode) {
            selected?.takeIf { it in elements }?.let { e ->
                val r = RectF(bounds.getValue(e)).apply { inset(-1.5f * screen.unit, -1.5f * screen.unit) }
                selection.color = Color.argb(255, 0xE2, 0xC1, 0x5A)
                canvas.drawRoundRect(r, 2 * screen.unit, 2 * screen.unit, selection)
            }
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

    private fun drawMenu(canvas: Canvas, idle: Int, active: Int) {
        val (cx, cy) = menuCenter
        fill.color = Color.argb(if (menuHeld) active else idle, 255, 255, 255)
        canvas.drawCircle(cx, cy, menuRadius, fill)
        canvas.drawCircle(cx, cy, menuRadius, stroke)
        fill.color = Color.argb((opacity * 255).toInt().coerceAtMost(255), 255, 255, 255)
        val badge = menuBadge
        if (badge != null) {
            val size = text.textSize
            text.textSize = menuRadius * 0.8f
            text.color = fill.color
            canvas.drawText(badge, cx, cy - (text.descent() + text.ascent()) / 2, text)
            text.textSize = size
            return
        }
        val dot = menuRadius * 0.13f
        for (i in -1..1) canvas.drawCircle(cx + i * menuRadius * 0.42f, cy, dot, fill)
    }

    /** Two triangles: the usual fast-forward sign, or pointing left for rewind. */
    private fun drawHold(canvas: Canvas, e: Element, idle: Int, active: Int) {
        val (cx, cy) = holdCenters.getValue(e)
        val r = holdRadii.getValue(e)
        val pressed = e in heldHolds
        fill.color = Color.argb(if (pressed) active else idle, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, stroke)
        val alpha = (opacity * 255).toInt().coerceAtMost(255)
        fill.color = if (pressed) Color.argb(alpha, 0, 0, 0) else Color.argb(alpha, 255, 255, 255)
        val h = r * 0.38f
        val w = if (e == Element.REWIND) -r * 0.38f else r * 0.38f
        for (base in listOf(cx - w, cx)) {
            arrow.reset()
            arrow.moveTo(base, cy - h)
            arrow.lineTo(base + w, cy)
            arrow.lineTo(base, cy + h)
            arrow.close()
            canvas.drawPath(arrow, fill)
        }
    }

    private fun drawLabel(canvas: Canvas, label: String, x: Float, y: Float, pressed: Boolean) {
        val alpha = (opacity * 255).toInt().coerceAtMost(255)
        text.color = if (pressed) Color.argb(alpha, 0, 0, 0) else Color.argb(alpha, 255, 255, 255)
        canvas.drawText(label, x, y - (text.descent() + text.ascent()) / 2, text)
    }

    companion object {
        /**
         * Value written to [KeyMap] Controls (order: Up, Down, Left, Right, Select, Start, A, B, X, Y, L, R).
         * Must match the key codes in [Button] and [dpadDirection]. Upstream binds Select to Right Shift,
         * but a held modifier turns other keys into Shift+key, so releasing them while Select is down
         * would leave them stuck. Every key here is therefore a plain, unmodified key.
         */
        const val KEYMAP_CONTROLS = "Up, Down, Left, Right, q, Return, x, z, s, a, c, v"

        private val HOLD_ELEMENTS = listOf(Element.TURBO, Element.REWIND)

        /** Controls to show for the current settings. */
        fun elementsFor(prefs: AppPrefs): Set<Element> = buildSet {
            if (prefs.touchControls) {
                addAll(listOf(Element.DPAD, Element.FACE, Element.L, Element.R, Element.SELECT, Element.START))
                if (prefs.turboButton) add(Element.TURBO)
                if (prefs.rewindButton && prefs.rewind) add(Element.REWIND)
            }
            if (prefs.menuButton) add(Element.MENU)
        }
    }
}
