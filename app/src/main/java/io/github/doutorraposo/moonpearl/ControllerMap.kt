package io.github.doutorraposo.moonpearl

import android.view.KeyEvent
import androidx.annotation.StringRes

/**
 * Which controller button presses each SNES button. Stored in upstream's [GamepadMap] Controls
 * (order: Up, Down, Left, Right, Select, Start, A, B, X, Y, L, R; names of SDL's game controller
 * buttons). The d-pad stays on the d-pad, and the buttons the app itself uses (guide and right
 * stick for the menu, the triggers for the speed) are left out.
 */
data class ControllerMap(val buttons: Map<Snes, Pad>) {
    /** The SNES buttons in the order of upstream's Controls list after the four directions. */
    enum class Snes(val label: String) { SELECT("SELECT"), START("START"), A("A"), B("B"), X("X"), Y("Y"), L("L"), R("R") }

    /**
     * Controller buttons, named as upstream does (SDL's layout, Xbox letters by position) and
     * matched to the Android key codes SDL turns into them.
     */
    enum class Pad(val iniName: String, val keyCode: Int, @StringRes val label: Int) {
        A("A", KeyEvent.KEYCODE_BUTTON_A, R.string.pad_a),
        B("B", KeyEvent.KEYCODE_BUTTON_B, R.string.pad_b),
        X("X", KeyEvent.KEYCODE_BUTTON_X, R.string.pad_x),
        Y("Y", KeyEvent.KEYCODE_BUTTON_Y, R.string.pad_y),
        LB("Lb", KeyEvent.KEYCODE_BUTTON_L1, R.string.pad_lb),
        RB("Rb", KeyEvent.KEYCODE_BUTTON_R1, R.string.pad_rb),
        BACK("Back", KeyEvent.KEYCODE_BUTTON_SELECT, R.string.pad_back),
        START("Start", KeyEvent.KEYCODE_BUTTON_START, R.string.pad_start),
        L3("L3", KeyEvent.KEYCODE_BUTTON_THUMBL, R.string.pad_l3);

        companion object {
            fun fromKeyCode(keyCode: Int) = entries.firstOrNull { it.keyCode == keyCode }

            fun fromIni(name: String) = when (name.trim().lowercase()) {
                "l1" -> LB
                "r1" -> RB
                else -> entries.firstOrNull { it.iniName.equals(name.trim(), ignoreCase = true) }
            }
        }
    }

    operator fun get(snes: Snes): Pad = buttons.getValue(snes)

    /** Puts [snes] on [pad]; the SNES button that had [pad] takes [snes]'s old one, so none is lost. */
    fun assigned(snes: Snes, pad: Pad): ControllerMap {
        val other = buttons.entries.firstOrNull { it.value == pad }?.key
        val updated = buttons.toMutableMap()
        if (other != null && other != snes) updated[other] = buttons.getValue(snes)
        updated[snes] = pad
        return ControllerMap(updated)
    }

    fun iniValue() = (listOf("DpadUp", "DpadDown", "DpadLeft", "DpadRight") + Snes.entries.map { this[it].iniName })
        .joinToString(", ")

    companion object {
        /** Upstream's default: by position, as on a SNES pad (SNES A is the right face button). */
        val BY_POSITION = ControllerMap(
            mapOf(
                Snes.SELECT to Pad.BACK, Snes.START to Pad.START,
                Snes.A to Pad.B, Snes.B to Pad.A, Snes.X to Pad.Y, Snes.Y to Pad.X,
                Snes.L to Pad.LB, Snes.R to Pad.RB,
            ),
        )

        /** By the letters printed on Xbox-style controllers. */
        val BY_LETTER = BY_POSITION.copy(
            buttons = BY_POSITION.buttons + mapOf(Snes.A to Pad.A, Snes.B to Pad.B, Snes.X to Pad.X, Snes.Y to Pad.Y),
        )

        /**
         * Reads [GamepadMap] Controls. Anything this screen cannot show (other directions,
         * modifiers, buttons outside [Pad]) falls back to the default for that SNES button.
         */
        fun fromIni(value: String?): ControllerMap {
            val names = value?.split(',')?.map { it.trim() }.orEmpty()
            if (names.size != 12) return BY_POSITION
            var map = BY_POSITION
            for ((i, snes) in Snes.entries.withIndex()) {
                val pad = Pad.fromIni(names[4 + i]) ?: continue
                map = map.assigned(snes, pad)
            }
            return map
        }
    }
}
