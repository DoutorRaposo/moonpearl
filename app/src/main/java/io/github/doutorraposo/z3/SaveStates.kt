package io.github.doutorraposo.z3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Upstream save states live in saves/saveN.sav (written by the game itself). The app keeps a
 * screenshot next to each one as saveN.png, since upstream stores no preview or metadata.
 */
class SaveStates(private val gameDir: File) {
    data class Slot(val index: Int, val modified: Long?, val thumbnail: Bitmap?) {
        val isAuto get() = index == 0
        val exists get() = modified != null
    }

    data class Chapter(val index: Int, val title: String)

    private val savesDir get() = File(gameDir, "saves")

    fun stateFile(slot: Int) = File(savesDir, "save$slot.sav")
    fun thumbnailFile(slot: Int) = File(savesDir, "save$slot.png")

    /** Blocking: decodes thumbnails. */
    fun slots(): List<Slot> = (0 until GameKeys.STATE_SLOTS).map { i ->
        val state = stateFile(i)
        val thumb = thumbnailFile(i).takeIf { state.isFile && it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
        Slot(i, state.takeIf { it.isFile }?.lastModified(), thumb)
    }

    /** Upstream's reference saves, ordered like kReferenceSaves (and so like the LoadRef keys). */
    fun chapters(): List<Chapter> {
        val pattern = Regex("""Chapter (\d+) - (.+)\.sav""")
        return File(savesDir, "ref").list().orEmpty()
            .mapNotNull { pattern.matchEntire(it) }
            .map { Chapter(it.groupValues[1].toInt(), it.groupValues[2]) }
            .filter { it.index in 1..GameKeys.chapterKeys.size }
            .sortedBy { it.index }
    }

    fun writeThumbnail(slot: Int, source: File) {
        if (source.isFile) source.copyTo(thumbnailFile(slot), overwrite = true)
    }

    companion object {
        const val THUMBNAIL_WIDTH = 480

        fun scaleForThumbnail(full: Bitmap): Bitmap {
            val height = (full.height.toLong() * THUMBNAIL_WIDTH / full.width).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(full, THUMBNAIL_WIDTH, height, true)
        }
    }
}
