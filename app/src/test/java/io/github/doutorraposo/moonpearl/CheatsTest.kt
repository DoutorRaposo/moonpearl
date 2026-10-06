package io.github.doutorraposo.moonpearl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CheatsTest {
    @Test
    fun parsesCommonFormats() {
        val expected = (0xF36D shl 8) or 0xA0
        assertEquals(expected, Cheats.parse("7EF36D:A0"))
        assertEquals(expected, Cheats.parse("7ef36da0"))
        assertEquals(expected, Cheats.parse(" 7E F36D A0 "))
        assertEquals(expected, Cheats.parse("7EF36D-A0"))
        assertEquals((0x1FFFF shl 8) or 0x01, Cheats.parse("7FFFFF:01"))
    }

    @Test
    fun rejectsOutsideWorkRamAndGarbage() {
        assertNull(Cheats.parse("7DFFFF:00")) // below $7E0000
        assertNull(Cheats.parse("808000:EA")) // ROM: Game Genie style, nothing to patch here
        assertNull(Cheats.parse("7EF36D:A")) // too short
        assertNull(Cheats.parse("7EF36G:A0")) // not hex
    }

    @Test
    fun normalizes() {
        assertEquals("7EF36D:A0", Cheats.normalize("7ef36da0"))
        assertNull(Cheats.normalize("nope"))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val codes = listOf(
            Cheats.Code("7EF36D:A0", "Full health", true),
            Cheats.Code("7EF343:32", "Bombs | many", false),
        )
        assertEquals(codes, Cheats.decode(Cheats.encode(codes)))
        assertEquals(emptyList<Cheats.Code>(), Cheats.decode(""))
    }
}
