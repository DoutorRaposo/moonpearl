package io.github.doutorraposo.z3

import android.content.Context
import android.os.Build
import android.view.WindowManager
import kotlin.math.roundToInt

/** The ExtendedAspectRatio values upstream understands (see ParseConfigFile in src/config.c). */
enum class AspectRatio(val iniValue: String, val ratio: Float) {
    STANDARD("4:3", 4f / 3f),
    WIDE_16_10("16:10", 16f / 10f),
    WIDE_16_9("16:9", 16f / 9f),
    WIDE_18_9("18:9", 18f / 9f);

    companion object {
        fun fromIni(value: String?): AspectRatio {
            val tokens = value.orEmpty().split(',').map { it.trim() }
            return entries.firstOrNull { it.iniValue in tokens } ?: STANDARD
        }

        /** Replaces only the ratio token, keeping modifiers such as extend_y or unchanged_sprites. */
        fun toIni(current: String?, ratio: AspectRatio): String {
            val tokens = current.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val ratioTokens = entries.map { it.iniValue }.toSet()
            if (tokens.none { it in ratioTokens }) return (tokens + ratio.iniValue).joinToString(", ")
            return tokens.joinToString(", ") { if (it in ratioTokens) ratio.iniValue else it }
        }

        /** The widest mode that still fits the device screen without cropping. */
        fun forScreen(context: Context): AspectRatio {
            val screen = screenRatio(context)
            return entries.last { it.ratio <= screen + 0.01f }
        }

        /** Landscape width / height of the whole display, including system bar areas. */
        fun screenRatio(context: Context): Float {
            val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val m = context.resources.displayMetrics
                m.widthPixels to m.heightPixels
            }
            return maxOf(w, h).toFloat() / minOf(w, h)
        }
    }

    /** SNES lines lost at the top and at the bottom when this picture is scaled to cover [screenRatio]. */
    fun croppedLinesPerEdge(screenRatio: Float): Int =
        if (screenRatio <= ratio) 0 else (224 * (1 - ratio / screenRatio) / 2).roundToInt()

    /** SNES columns lost at each side when the screen is narrower than the picture. */
    fun croppedColumnsPerEdge(screenRatio: Float): Int =
        if (screenRatio >= ratio) 0 else (224 * (ratio - screenRatio) / 2).roundToInt()
}
