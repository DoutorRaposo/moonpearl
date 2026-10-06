package io.github.doutorraposo.moonpearl

import android.view.KeyEvent

/**
 * The upstream keyboard shortcuts the app presses on the player's behalf (save states,
 * fast-forward, reset, chapter saves). [bindings] is written into zelda3.ini on every start
 * so these key codes always mean the same command.
 */
object GameKeys {
    /** Upstream save state slots driven by the app; slot 0 is the autosave / resume point. */
    const val STATE_SLOTS = 10

    val bindings = mapOf(
        "Load" to (1..STATE_SLOTS).joinToString(", ") { "F$it" },
        "Save" to (1..STATE_SLOTS).joinToString(", ") { "Shift+F$it" },
        "Turbo" to "Tab",
        "Reset" to "Ctrl+r",
        "Pause" to "Shift+p",
        "LoadRef" to "1, 2, 3, 4, 5, 6, 7, 8, 9, 0, -, =, Backspace",
    )

    /** Key codes for LoadRef, in the order of upstream's kReferenceSaves (chapter 1..13). */
    val chapterKeys = intArrayOf(
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4,
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS,
        KeyEvent.KEYCODE_DEL,
    )

    fun slotKey(slot: Int): Int {
        require(slot in 0 until STATE_SLOTS)
        return KeyEvent.KEYCODE_F1 + slot
    }

    const val TURBO = KeyEvent.KEYCODE_TAB

    /** Fast-forward choices: game frames per displayed frame, or [SPEED_MAX] for upstream turbo. */
    val speeds = intArrayOf(1, 2, 3, SPEED_MAX)
    const val SPEED_MAX = 0

    const val SHIFT = KeyEvent.KEYCODE_SHIFT_LEFT
    const val CTRL = KeyEvent.KEYCODE_CTRL_LEFT
    const val RESET = KeyEvent.KEYCODE_R
    const val PAUSE = KeyEvent.KEYCODE_P
}
