package io.github.doutorraposo.moonpearl

import android.content.Context

/** App-side options that zelda3.ini knows nothing about. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var touchControls: Boolean
        get() = prefs.getBoolean("touch_controls", true)
        // commit(): the game runs in its own process and reads these right after launch.
        set(value) { prefs.edit().putBoolean("touch_controls", value).commit() }

    /** Scale the picture until it covers the whole screen, cropping what does not fit. */
    var fillScreen: Boolean
        get() = prefs.getBoolean("fill_screen", true)
        set(value) { prefs.edit().putBoolean("fill_screen", value).commit() }

    var menuButton: Boolean
        get() = prefs.getBoolean("menu_button", true)
        set(value) { prefs.edit().putBoolean("menu_button", value).commit() }

    var doubleTapMenu: Boolean
        get() = prefs.getBoolean("double_tap_menu", false)
        set(value) { prefs.edit().putBoolean("double_tap_menu", value).commit() }

    /** Cheats.BuiltIn bits that are switched on. */
    var cheatFlags: Int
        get() = prefs.getInt("cheat_flags", 0)
        set(value) { prefs.edit().putInt("cheat_flags", value).commit() }

    var cheatCodes: List<Cheats.Code>
        get() = Cheats.decode(prefs.getString("cheat_codes", "").orEmpty())
        set(value) { prefs.edit().putString("cheat_codes", Cheats.encode(value)).commit() }

    /** MSU-1 pack: on/off, the folder (a persisted document tree URI), Deluxe, volume in %. */
    var msuEnabled: Boolean
        get() = prefs.getBoolean("msu_enabled", false)
        set(value) { prefs.edit().putBoolean("msu_enabled", value).commit() }

    var msuFolder: String?
        get() = prefs.getString("msu_folder", null)
        set(value) { prefs.edit().putString("msu_folder", value).commit() }

    var msuDeluxe: Boolean
        get() = prefs.getBoolean("msu_deluxe", true)
        set(value) { prefs.edit().putBoolean("msu_deluxe", value).commit() }

    var msuVolume: Int
        get() = prefs.getInt("msu_volume", 100)
        set(value) { prefs.edit().putInt("msu_volume", value).commit() }

    /** Hold-to-fast-forward button on the touch pad. */
    var turboButton: Boolean
        get() = prefs.getBoolean("turbo_button", false)
        set(value) { prefs.edit().putBoolean("turbo_button", value).commit() }

    /** Keep the last minute of play in memory to rewind through (rewind.c). */
    var rewind: Boolean
        get() = prefs.getBoolean("rewind", true)
        set(value) { prefs.edit().putBoolean("rewind", value).commit() }

    /** Rate for holding fast-forward (touch button, or L3 in hold mode): 2, 3 or GameKeys.SPEED_MAX. */
    var holdSpeed: Int
        get() = prefs.getInt("hold_speed", GameKeys.SPEED_MAX).takeIf { it in GameKeys.speeds && it != 1 } ?: GameKeys.SPEED_MAX
        set(value) { prefs.edit().putInt("hold_speed", value).commit() }

    /** L3 fast-forwards while held instead of cycling the speed. */
    var l3Hold: Boolean
        get() = prefs.getBoolean("l3_hold", false)
        set(value) { prefs.edit().putBoolean("l3_hold", value).commit() }

    /** Hold-to-rewind button on the touch pad. */
    var rewindButton: Boolean
        get() = prefs.getBoolean("rewind_button", false)
        set(value) { prefs.edit().putBoolean("rewind_button", value).commit() }

    /** LT and RT step the speed; otherwise L3 cycles it. LT+RT rewinds either way. */
    var triggerSpeed: Boolean
        get() = prefs.getBoolean("trigger_speed", false)
        set(value) { prefs.edit().putBoolean("trigger_speed", value).commit() }

    /** Move the HUD's side blocks to the edges of a widescreen picture (patch 0010). */
    var widescreenHud: Boolean
        get() = prefs.getBoolean("widescreen_hud", false)
        set(value) { prefs.edit().putBoolean("widescreen_hud", value).commit() }

    var touchLayout: TouchLayout
        get() = TouchLayout.decode(prefs.getString("touch_layout", null))
        set(value) { prefs.edit().putString("touch_layout", value.encode()).commit() }

    /**
     * Video output: upstream's OpenGL output (needed for shaders) or its SDL renderer. Switched
     * off automatically if OpenGL keeps the game from starting (Shaders.kt).
     */
    var useOpenGl: Boolean
        get() = prefs.getBoolean("use_opengl", true)
        set(value) { prefs.edit().putBoolean("use_opengl", value).commit() }

    var touchOpacity: Float
        get() = prefs.getFloat("touch_opacity", 0.5f)
        set(value) { prefs.edit().putFloat("touch_opacity", value).commit() }
}
