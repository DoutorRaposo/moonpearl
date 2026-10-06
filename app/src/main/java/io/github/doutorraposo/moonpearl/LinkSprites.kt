package io.github.doutorraposo.moonpearl

import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Custom Link graphics in the community ZSPR format, which upstream loads through
 * [Graphics] LinkGraphics (ParseLinkGraphics in src/main.c). Imported files live in
 * files/sprites/ so the setting can be a path relative to the game folder.
 */
class LinkSprites(private val gameDir: File) {
    data class Sprite(val file: File?, val name: String, val author: String, val pixels: ByteArray, val palette: ByteArray) {
        val isDefault get() = file == null
        /** Value for [Graphics] LinkGraphics, relative to the game folder. */
        val iniValue get() = file?.let { "$DIR/${it.name}" }
    }

    sealed interface ImportResult {
        data class Imported(val sprite: Sprite) : ImportResult
        data object NotRecognized : ImportResult
    }

    private val dir get() = File(gameDir, DIR)

    /** Imported sprites, sorted by name. */
    fun list(): List<Sprite> = dir.listFiles { f -> f.isFile && f.name.endsWith(".zspr") }.orEmpty()
        .mapNotNull { f -> parse(f.readBytes())?.copy(file = f) }
        .sortedBy { it.name.lowercase() }

    /** The stock graphics, read from the user's zelda3_assets.dat. */
    fun default(defaultName: String): Sprite? {
        val assets = File(gameDir, "zelda3_assets.dat").takeIf { it.isFile }?.readBytes() ?: return null
        val pixels = asset(assets, BuildConfig.ASSET_LINK_GRAPHICS) ?: return null
        val palette = asset(assets, BuildConfig.ASSET_LINK_PALETTE) ?: return null
        if (pixels.size != PIXELS_SIZE) return null
        return Sprite(null, defaultName, "Nintendo", pixels, palette)
    }

    fun import(input: InputStream, suggestedName: String?): ImportResult {
        val data = input.readAtMost(MAX_SIZE + 1)
        if (data.size > MAX_SIZE) return ImportResult.NotRecognized
        val sprite = parse(data) ?: return ImportResult.NotRecognized
        dir.mkdirs()
        val base = slug(sprite.name.ifBlank { suggestedName?.substringBeforeLast('.').orEmpty() }).ifEmpty { "sprite" }
        var target = File(dir, "$base.zspr")
        var n = 2
        while (target.exists() && !target.readBytes().contentEquals(data)) target = File(dir, "$base-${n++}.zspr")
        target.writeBytes(data)
        if (!target.isFile) throw IOException("cannot write ${target.name}")
        return ImportResult.Imported(sprite.copy(file = target))
    }

    fun delete(sprite: Sprite) {
        sprite.file?.delete()
    }

    /** The sprite currently set in zelda3.ini, if it is still a valid imported file. */
    fun selected(ini: Ini): File? {
        val path = ini["Graphics", "LinkGraphics"]?.takeIf { it.isNotBlank() } ?: return null
        val file = File(gameDir, path)
        return file.takeIf { it.isFile && parse(it.readBytes()) != null }
    }

    companion object {
        const val DIR = "sprites"
        private const val PIXELS_SIZE = 0x7000
        private const val MAX_SIZE = 256 * 1024

        /** Same checks as upstream's ParseLinkGraphics, so the game never refuses a file we accepted. */
        fun parse(data: ByteArray): Sprite? {
            if (data.size < 29 || !data.copyOfRange(0, 4).contentEquals("ZSPR".toByteArray())) return null
            val pixelOffset = u32(data, 9)
            val pixelLength = u16(data, 13)
            val paletteOffset = u32(data, 15)
            val paletteLength = u16(data, 19)
            if (pixelLength != PIXELS_SIZE.toLong() ||
                pixelOffset + pixelLength > data.size || paletteOffset + paletteLength > data.size) return null
            var pos = 29
            fun utf16(): String {
                val start = pos
                while (pos + 1 < data.size && (data[pos].toInt() != 0 || data[pos + 1].toInt() != 0)) pos += 2
                val s = String(data, start, (pos - start).coerceAtLeast(0), Charsets.UTF_16LE)
                pos += 2
                return s
            }
            val name = utf16().trim()
            val author = utf16().trim()
            return Sprite(
                file = null,
                name = name,
                author = author,
                pixels = data.copyOfRange(pixelOffset.toInt(), (pixelOffset + pixelLength).toInt()),
                palette = data.copyOfRange(paletteOffset.toInt(), (paletteOffset + paletteLength).toInt()),
            )
        }

        /**
         * Link standing and facing down, 16x24: the front head (sheet 16,0) drawn over the
         * front body (sheet 48,16) 8 pixels lower, in the green mail palette.
         */
        fun preview(sprite: Sprite): Bitmap = Bitmap.createBitmap(previewPixels(sprite), 16, 24, Bitmap.Config.ARGB_8888)

        /** ARGB pixels of [preview], row by row; 0 is transparent. */
        fun previewPixels(sprite: Sprite): IntArray {
            val colors = IntArray(16)
            for (i in 1 until 16) {
                val at = (i - 1) * 2
                if (at + 1 >= sprite.palette.size) break
                val c = u16(sprite.palette, at).toInt()
                colors[i] = (0xFF shl 24) or (scale5(c and 31) shl 16) or (scale5((c shr 5) and 31) shl 8) or scale5((c shr 10) and 31)
            }
            val out = IntArray(16 * 24)
            fun blit(sheetX: Int, sheetY: Int, dstY: Int) {
                for (y in 0 until 16) for (x in 0 until 16) {
                    val sx = sheetX + x
                    val sy = sheetY + y
                    val tile = (sy / 8) * 16 + sx / 8
                    val b = tile * 32
                    val row = sy % 8
                    val bit = 7 - sx % 8
                    val p = sprite.pixels
                    val v = ((p[b + row * 2].toInt() shr bit) and 1) or
                        (((p[b + row * 2 + 1].toInt() shr bit) and 1) shl 1) or
                        (((p[b + 16 + row * 2].toInt() shr bit) and 1) shl 2) or
                        (((p[b + 16 + row * 2 + 1].toInt() shr bit) and 1) shl 3)
                    if (v != 0) out[(dstY + y) * 16 + x] = colors[v]
                }
            }
            blit(48, 16, 8)
            blit(16, 0, 0)
            return out
        }

        private fun scale5(v: Int) = (v * 255) / 31

        private fun slug(s: String) = s.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("").replace(Regex("-+"), "-").trim('-').take(40)

        private fun u16(d: ByteArray, at: Int) = ((d[at].toInt() and 0xff) or ((d[at + 1].toInt() and 0xff) shl 8)).toLong()
        private fun u32(d: ByteArray, at: Int) = u16(d, at) or (u16(d, at + 2) shl 16)

        /** One asset out of zelda3_assets.dat, laid out as LoadAssets() in src/main.c reads it. */
        fun asset(data: ByteArray, index: Int): ByteArray? {
            if (data.size < 88) return null
            val count = u32(data, 80).toInt()
            if (index !in 0 until count || data.size < 88 + count * 4) return null
            var offset = 88L + count * 4 + u32(data, 84)
            for (i in 0 until count) {
                val size = u32(data, 88 + i * 4)
                offset = (offset + 3) and 3L.inv()
                if (offset + size > data.size) return null
                if (i == index) return data.copyOfRange(offset.toInt(), (offset + size).toInt())
                offset += size
            }
            return null
        }
    }
}
