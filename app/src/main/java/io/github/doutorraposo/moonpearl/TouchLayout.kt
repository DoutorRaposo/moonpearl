package io.github.doutorraposo.moonpearl

/**
 * Where each on-screen control sits. Positions are anchored to the nearest screen edge (or the
 * horizontal center) and measured in units of 1% of the screen's short side, so a layout keeps
 * its shape on screens of any aspect ratio.
 */
data class TouchLayout(val placements: Map<Element, Placement>) {
    enum class Element { DPAD, FACE, L, R, SELECT, START, MENU, TURBO, REWIND }
    enum class AnchorX { LEFT, CENTER, RIGHT }
    enum class AnchorY { TOP, BOTTOM }

    /** [dx]/[dy]: distance from the anchor to the element's center; [scale] multiplies its size. */
    data class Placement(val ax: AnchorX, val ay: AnchorY, val dx: Float, val dy: Float, val scale: Float = 1f)

    data class Point(val x: Float, val y: Float)

    /** Screen geometry: size in pixels, the unit (1% of the short side) and the cutout insets. */
    data class Screen(val width: Int, val height: Int, val safeLeft: Int = 0, val safeRight: Int = 0) {
        val unit get() = minOf(width, height) / 100f
    }

    operator fun get(e: Element): Placement = placements[e] ?: DEFAULT.placements.getValue(e)

    /** The element's center, pushed in where needed so the whole element stays on screen. */
    fun center(e: Element, s: Screen): Point {
        val p = this[e]
        val u = s.unit
        val x = when (p.ax) {
            AnchorX.LEFT -> s.safeLeft + p.dx * u
            AnchorX.CENTER -> s.width / 2f + p.dx * u
            AnchorX.RIGHT -> s.width - s.safeRight - p.dx * u
        }
        val y = when (p.ay) {
            AnchorY.TOP -> p.dy * u
            AnchorY.BOTTOM -> s.height - p.dy * u
        }
        val (hw, hh) = halfSize(e)
        val halfW = hw * p.scale * u
        val halfH = hh * p.scale * u
        return Point(
            x.coerceIn(s.safeLeft + halfW, maxOf(s.safeLeft + halfW, s.width - s.safeRight - halfW)),
            y.coerceIn(halfH, maxOf(halfH, s.height - halfH)),
        )
    }

    /** Moves an element's center to (x, y), re-anchoring it to the nearest edge or the center. */
    fun moved(e: Element, x: Float, y: Float, s: Screen): TouchLayout {
        val u = s.unit
        val ax = when {
            x < s.width / 3f -> AnchorX.LEFT
            x > s.width * 2 / 3f -> AnchorX.RIGHT
            else -> AnchorX.CENTER
        }
        val ay = if (y < s.height / 2f) AnchorY.TOP else AnchorY.BOTTOM
        val dx = when (ax) {
            AnchorX.LEFT -> (x - s.safeLeft) / u
            AnchorX.CENTER -> (x - s.width / 2f) / u
            AnchorX.RIGHT -> (s.width - s.safeRight - x) / u
        }
        val dy = if (ay == AnchorY.TOP) y / u else (s.height - y) / u
        return copy(placements = placements + (e to this[e].copy(ax = ax, ay = ay, dx = dx, dy = dy)))
    }

    fun scaled(e: Element, scale: Float): TouchLayout =
        copy(placements = placements + (e to this[e].copy(scale = scale.coerceIn(MIN_SCALE, MAX_SCALE))))

    /** One element per line: NAME ax ay dx dy scale. */
    fun encode(): String = Element.entries.joinToString("\n") { e ->
        val p = this[e]
        "${e.name} ${p.ax.name} ${p.ay.name} ${p.dx} ${p.dy} ${p.scale}"
    }

    companion object {
        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 1.8f

        /** Half the width and height of each element at scale 1, in units (see TouchControlsView). */
        fun halfSize(e: Element): Pair<Float, Float> = when (e) {
            Element.DPAD -> 17f to 17f
            Element.FACE -> 17.5f to 17.5f
            Element.L, Element.R -> 11f to 5f
            Element.SELECT, Element.START -> 7.5f to 3f
            Element.MENU, Element.TURBO, Element.REWIND -> 4.5f to 4.5f
        }

        /** Matches the pad's original fixed layout (margins of 6 units). */
        val DEFAULT = TouchLayout(
            mapOf(
                Element.DPAD to Placement(AnchorX.LEFT, AnchorY.BOTTOM, 23f, 23f),
                Element.FACE to Placement(AnchorX.RIGHT, AnchorY.BOTTOM, 23f, 23f),
                Element.L to Placement(AnchorX.LEFT, AnchorY.TOP, 17f, 11f),
                Element.R to Placement(AnchorX.RIGHT, AnchorY.TOP, 17f, 11f),
                Element.SELECT to Placement(AnchorX.CENTER, AnchorY.BOTTOM, -9.5f, 9f),
                Element.START to Placement(AnchorX.CENTER, AnchorY.BOTTOM, 9.5f, 9f),
                Element.MENU to Placement(AnchorX.RIGHT, AnchorY.TOP, 35.5f, 11f),
                Element.TURBO to Placement(AnchorX.RIGHT, AnchorY.TOP, 47.5f, 11f),
                Element.REWIND to Placement(AnchorX.RIGHT, AnchorY.TOP, 59.5f, 11f),
            ),
        )

        /** Lines that do not parse are skipped, so a corrupt entry falls back to the default. */
        fun decode(text: String?): TouchLayout {
            if (text.isNullOrBlank()) return DEFAULT
            val parsed = text.lines().mapNotNull { line ->
                val f = line.trim().split(' ')
                if (f.size != 6) return@mapNotNull null
                runCatching {
                    Element.valueOf(f[0]) to Placement(
                        AnchorX.valueOf(f[1]), AnchorY.valueOf(f[2]),
                        f[3].toFloat(), f[4].toFloat(), f[5].toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
                    )
                }.getOrNull()
            }.toMap()
            return TouchLayout(DEFAULT.placements + parsed)
        }
    }
}
