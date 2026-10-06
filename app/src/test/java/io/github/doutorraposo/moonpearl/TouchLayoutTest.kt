package io.github.doutorraposo.moonpearl

import io.github.doutorraposo.moonpearl.TouchLayout.AnchorX
import io.github.doutorraposo.moonpearl.TouchLayout.AnchorY
import io.github.doutorraposo.moonpearl.TouchLayout.Element
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchLayoutTest {
    private val phone = TouchLayout.Screen(2400, 1080) // unit = 10.8 px

    @Test
    fun defaultMatchesTheOriginalFixedLayout() {
        val d = TouchLayout.DEFAULT
        // D-pad: 6 units of margin plus a 17-unit radius from the bottom-left corner.
        assertEquals(TouchLayout.Point(23 * 10.8f, 1080 - 23 * 10.8f), d.center(Element.DPAD, phone))
        assertEquals(2400 - 23 * 10.8f, d.center(Element.FACE, phone).x, 0.01f)
        assertEquals(1200 + 9.5f * 10.8f, d.center(Element.START, phone).x, 0.01f)
    }

    @Test
    fun moveReanchorsToTheNearestEdge() {
        val moved = TouchLayout.DEFAULT.moved(Element.DPAD, 2000f, 200f, phone)
        val p = moved[Element.DPAD]
        assertEquals(AnchorX.RIGHT, p.ax)
        assertEquals(AnchorY.TOP, p.ay)
        val c = moved.center(Element.DPAD, phone)
        assertEquals(2000f, c.x, 0.01f)
        assertEquals(200f, c.y, 0.01f)
    }

    @Test
    fun anchoredLayoutKeepsShapeOnOtherScreens() {
        val moved = TouchLayout.DEFAULT.moved(Element.FACE, 2200f, 880f, phone)
        // Same distance from the bottom-right corner on a 4:3 tablet, in units.
        val tablet = TouchLayout.Screen(2000, 1500)
        val c = moved.center(Element.FACE, tablet)
        assertEquals(2000 - 200 / 10.8f * 15f, c.x, 0.01f)
        assertEquals(1500 - 200 / 10.8f * 15f, c.y, 0.01f)
    }

    @Test
    fun growingNearAnEdgeKeepsTheElementOnScreen() {
        val big = TouchLayout.DEFAULT.scaled(Element.FACE, TouchLayout.MAX_SCALE)
        val c = big.center(Element.FACE, phone)
        val half = 17.5f * TouchLayout.MAX_SCALE * 10.8f
        assertEquals(2400 - half, c.x, 0.01f)
        assertEquals(1080 - half, c.y, 0.01f)
        // Dragged past the edge: drawn at the edge.
        val dragged = TouchLayout.DEFAULT.moved(Element.MENU, 5f, 5f, phone)
        assertEquals(TouchLayout.Point(4.5f * 10.8f, 4.5f * 10.8f), dragged.center(Element.MENU, phone))
    }

    @Test
    fun cutoutInsetIsRespected() {
        val notch = phone.copy(safeLeft = 100)
        assertEquals(100 + 23 * 10.8f, TouchLayout.DEFAULT.center(Element.DPAD, notch).x, 0.01f)
    }

    @Test
    fun encodeDecodeRoundTripAndBadInput() {
        val layout = TouchLayout.DEFAULT.moved(Element.L, 300f, 300f, phone).scaled(Element.FACE, 1.3f)
        assertEquals(layout, TouchLayout.decode(layout.encode()))
        assertEquals(TouchLayout.DEFAULT, TouchLayout.decode("garbage\nDPAD LEFT"))
        assertEquals(TouchLayout.MAX_SCALE, TouchLayout.decode("FACE RIGHT BOTTOM 23 23 9").get(Element.FACE).scale)
    }
}
