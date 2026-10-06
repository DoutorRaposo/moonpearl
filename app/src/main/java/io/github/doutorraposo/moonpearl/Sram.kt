package io.github.doutorraposo.moonpearl

/**
 * The cartridge save RAM, laid out exactly like the original game's (and so like any
 * emulator's .srm): three files of 0x500 bytes, each marked valid with 0x55AA at +0x3E5 and
 * checksummed so its 16-bit words add up to 0x5A5A (Intro_CheckCksum in select_file.c).
 */
object Sram {
    const val SIZE = 8192
    private const val FILE_SIZE = 0x500
    private const val OFFS_NAME = 0x3D9 // kSrmOffs_Name: 6 tile numbers
    private const val OFFS_HEALTH = 0x36C // kSrmOffs_Health: max health, 8 per heart
    private const val OFFS_VALID = 0x3E5
    private const val BLANK_TILE = 0xA9

    data class SaveFile(val slot: Int, val name: String?, val hearts: Int)

    /** The three game files; null where the slot is empty or damaged. */
    fun files(data: ByteArray): List<SaveFile?> = (0 until 3).map { slot ->
        if (!isValid(data, slot)) return@map null
        val base = slot * FILE_SIZE
        SaveFile(slot + 1, name(data, base), (data[base + OFFS_HEALTH].toInt() and 0xff) shr 3)
    }

    fun isValid(data: ByteArray, slot: Int): Boolean {
        if (data.size < SIZE) return false
        val base = slot * FILE_SIZE
        if (word(data, base + OFFS_VALID) != 0x55AA) return false
        var sum = 0
        for (i in 0 until FILE_SIZE step 2) sum += word(data, base + i)
        return sum and 0xffff == 0x5A5A
    }

    fun hasAnyFile(data: ByteArray) = (0 until 3).any { isValid(data, it) }

    /**
     * Names are stored as font tile numbers. Letters are 8x16, two tile rows each, so A-P are
     * 0x00-0x0F and Q-Z 0x20-0x29 (verified against names entered in the game). Other
     * characters are not mapped; such names are reported as unknown rather than guessed.
     */
    private fun name(data: ByteArray, base: Int): String? {
        val sb = StringBuilder()
        for (i in 0 until 6) {
            val tile = word(data, base + OFFS_NAME + i * 2)
            if (tile == BLANK_TILE) {
                sb.append(' ')
                continue
            }
            val index = (tile and 0xF) or ((tile shr 1) and 0xF0)
            if (index !in 0..25) return null
            sb.append('A' + index)
        }
        return sb.toString().trimEnd().ifEmpty { null }
    }

    private fun word(data: ByteArray, at: Int) = (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8)
}
