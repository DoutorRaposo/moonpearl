package io.github.doutorraposo.z3

import androidx.annotation.StringRes

/** Cheats applied by cheats.c before every game frame. */
object Cheats {
    /** Bits must match the kCheat_ flags in cheats.c. */
    enum class BuiltIn(val bit: Int, @StringRes val label: Int, @StringRes val description: Int) {
        HEALTH(1 shl 0, R.string.cheat_health, R.string.cheat_health_desc),
        MAGIC(1 shl 1, R.string.cheat_magic, R.string.cheat_magic_desc),
        BOMBS(1 shl 2, R.string.cheat_bombs, R.string.cheat_bombs_desc),
        ARROWS(1 shl 3, R.string.cheat_arrows, R.string.cheat_arrows_desc),
        RUPEES(1 shl 4, R.string.cheat_rupees, R.string.cheat_rupees_desc),
        KEYS(1 shl 5, R.string.cheat_keys, R.string.cheat_keys_desc),
        WALK_THROUGH_WALLS(1 shl 6, R.string.cheat_walls, R.string.cheat_walls_desc),
    }

    /** A Pro Action Replay RAM code as the player typed it, normalized to "7EF36D:A0". */
    data class Code(val code: String, val name: String, val enabled: Boolean) {
        /** (offset from $7E0000) shl 8 or value, the form cheats.c takes. */
        val packed: Int get() = parse(code) ?: error("invalid code $code")
    }

    const val MAX_CODES = 64

    /**
     * Accepts "7EF36D:A0", "7EF36DA0", "7E F36D A0" and the like: 6 hex digits of address in
     * banks $7E/$7F (work RAM), then 2 of value. Returns the packed form, or null.
     */
    fun parse(text: String): Int? {
        val hex = text.filterNot { it == ':' || it == '-' || it.isWhitespace() }.uppercase()
        if (hex.length != 8 || hex.any { it !in "0123456789ABCDEF" }) return null
        val address = hex.substring(0, 6).toInt(16)
        val value = hex.substring(6).toInt(16)
        val offset = address - 0x7E0000
        if (offset !in 0 until 0x20000) return null
        return (offset shl 8) or value
    }

    fun normalize(text: String): String? {
        val packed = parse(text) ?: return null
        return "%06X:%02X".format(0x7E0000 + (packed ushr 8), packed and 0xFF)
    }

    /** One code per line: "7EF36D:A0|1|name". Names may contain anything but newlines. */
    fun encode(codes: List<Code>) = codes.joinToString("\n") { "${it.code}|${if (it.enabled) 1 else 0}|${it.name.replace('\n', ' ')}" }

    fun decode(text: String): List<Code> = text.lines().mapNotNull { line ->
        val parts = line.split('|', limit = 3)
        val code = parts.getOrNull(0)?.let(::normalize) ?: return@mapNotNull null
        Code(code, parts.getOrNull(2).orEmpty(), parts.getOrNull(1) == "1")
    }
}
