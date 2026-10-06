package io.github.doutorraposo.z3

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

    var touchOpacity: Float
        get() = prefs.getFloat("touch_opacity", 0.5f)
        set(value) { prefs.edit().putFloat("touch_opacity", value).commit() }
}
