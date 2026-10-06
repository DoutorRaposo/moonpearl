package io.github.doutorraposo.moonpearl

import io.github.doutorraposo.moonpearl.ControllerMap.Pad
import io.github.doutorraposo.moonpearl.ControllerMap.Snes
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerMapTest {
    // Upstream's zelda3.ini default.
    private val upstream = "DpadUp, DpadDown, DpadLeft, DpadRight, Back, Start, B, A, Y, X, Lb, Rb"

    @Test
    fun defaultMatchesUpstream() {
        assertEquals(upstream, ControllerMap.BY_POSITION.iniValue())
        assertEquals(ControllerMap.BY_POSITION, ControllerMap.fromIni(upstream))
        assertEquals(ControllerMap.BY_POSITION, ControllerMap.fromIni(null))
    }

    @Test
    fun assigningAButtonInUseSwaps() {
        // SNES A goes to the bottom button, which SNES B had: B takes the right button.
        val map = ControllerMap.BY_POSITION.assigned(Snes.A, Pad.A)
        assertEquals(Pad.A, map[Snes.A])
        assertEquals(Pad.B, map[Snes.B])
        assertEquals(8, map.buttons.values.toSet().size)
    }

    @Test
    fun byLetter() {
        assertEquals("DpadUp, DpadDown, DpadLeft, DpadRight, Back, Start, A, B, X, Y, Lb, Rb", ControllerMap.BY_LETTER.iniValue())
    }

    @Test
    fun roundTripAndAliases() {
        val map = ControllerMap.BY_POSITION.assigned(Snes.Y, Pad.BACK).assigned(Snes.L, Pad.RB)
        assertEquals(map, ControllerMap.fromIni(map.iniValue()))
        // L1/R1 are upstream's other names for the shoulder buttons.
        assertEquals(Pad.LB, ControllerMap.fromIni(upstream.replace("Lb", "L1"))[Snes.L])
        // Unknown names, and L3 (the speed button), keep the default for that button.
        assertEquals(Pad.X, ControllerMap.fromIni(upstream.replace(", X,", ", Guide,"))[Snes.Y])
        assertEquals(Pad.X, ControllerMap.fromIni(upstream.replace(", X,", ", L3,"))[Snes.Y])
    }
}
