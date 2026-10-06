package io.github.doutorraposo.z3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SramTest {
    private fun putWord(d: ByteArray, at: Int, v: Int) {
        d[at] = v.toByte()
        d[at + 1] = (v shr 8).toByte()
    }

    /** A save file the way the game writes it, with the checksum word at +0x4FE fixed up. */
    private fun sram(slot: Int, nameTiles: List<Int>, maxHealth: Int): ByteArray {
        val d = ByteArray(Sram.SIZE)
        val base = slot * 0x500
        nameTiles.forEachIndexed { i, t -> putWord(d, base + 0x3D9 + i * 2, t) }
        d[base + 0x36C] = maxHealth.toByte()
        putWord(d, base + 0x3E5, 0x55AA)
        var sum = 0
        for (i in 0 until 0x4FE step 2) sum += (d[base + i].toInt() and 0xff) or ((d[base + i + 1].toInt() and 0xff) shl 8)
        putWord(d, base + 0x4FE, (0x5A5A - sum) and 0xffff)
        return d
    }

    @Test
    fun readsNameAndHearts() {
        // "GHR" as entered on the name screen; R sits on the second tile row (0x21).
        val files = Sram.files(sram(1, listOf(0x06, 0x07, 0x21, 0xA9, 0xA9, 0xA9), 3 * 8))
        assertNull(files[0])
        assertEquals(Sram.SaveFile(2, "GHR", 3), files[1])
        assertNull(files[2])
    }

    @Test
    fun unknownCharactersGiveNoName() {
        val files = Sram.files(sram(0, listOf(0x06, 0x5F, 0xA9, 0xA9, 0xA9, 0xA9), 24))
        assertNull(files[0]!!.name)
    }

    @Test
    fun rejectsBadChecksum() {
        val d = sram(0, listOf(0x00, 0xA9, 0xA9, 0xA9, 0xA9, 0xA9), 24)
        assertTrue(Sram.hasAnyFile(d))
        d[0x10] = 1
        assertFalse(Sram.hasAnyFile(d))
    }

    @Test
    fun rejectsShortData() = assertFalse(Sram.hasAnyFile(ByteArray(100)))
}
