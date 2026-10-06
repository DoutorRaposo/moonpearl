package io.github.doutorraposo.z3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IniTest {
    private val sample = """
        [General]
        # Automatically save state on quit
        Autosave = 0
        ExtendedAspectRatio = 4:3

        [Graphics]
        Fullscreen = 0
        # LinkGraphics = foo.zspr

        [KeyMap]
        Controls = Up, Down, Left, Right, Right Shift, Return, x, z, s, a, c, v
    """.trimIndent()

    @Test
    fun readsValuesPerSection() {
        val ini = Ini(sample)
        assertEquals("0", ini["Graphics", "Fullscreen"])
        assertEquals("4:3", ini["general", "extendedaspectratio"])
        assertNull(ini["Graphics", "Autosave"])
        assertNull(ini["Graphics", "LinkGraphics"]) // commented out
    }

    @Test
    fun replacesValueAndKeepsComments() {
        val ini = Ini(sample)
        ini.setBool("General", "Autosave", true)
        assertTrue(ini.getBool("General", "Autosave"))
        assertTrue(ini.text.contains("# Automatically save state on quit\nAutosave = 1\n"))
        assertEquals(sample.lines().size, ini.text.lines().size)
    }

    @Test
    fun insertsMissingKeyAtEndOfSection() {
        val ini = Ini(sample)
        ini["Graphics", "LinearFiltering"] = "1"
        assertTrue(ini.text.contains("# LinkGraphics = foo.zspr\nLinearFiltering = 1\n\n[KeyMap]"))
        assertFalse(ini.getBool("Graphics", "Fullscreen"))
    }

    @Test
    fun insertsMissingSection() {
        val ini = Ini(sample)
        ini["Features", "MiscBugFixes"] = "1"
        assertEquals("1", ini["Features", "MiscBugFixes"])
        assertTrue(ini.text.endsWith("[Features]\nMiscBugFixes = 1"))
    }

    @Test
    fun aspectRatioKeepsModifiers() {
        assertEquals("extend_y, 16:9, unchanged_sprites", AspectRatio.toIni("extend_y, 4:3, unchanged_sprites", AspectRatio.WIDE_16_9))
        assertEquals("18:9", AspectRatio.toIni("4:3", AspectRatio.WIDE_18_9))
        assertEquals("unchanged_sprites, 16:10", AspectRatio.toIni("unchanged_sprites", AspectRatio.WIDE_16_10))
        assertEquals(AspectRatio.WIDE_16_9, AspectRatio.fromIni("extend_y, 16:9"))
        assertEquals(AspectRatio.STANDARD, AspectRatio.fromIni(null))
    }
}
