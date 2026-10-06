package io.github.doutorraposo.moonpearl

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSpritesTest {
    private fun u16(out: ByteArrayOutputStream, v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
    private fun u32(out: ByteArrayOutputStream, v: Int) { u16(out, v and 0xffff); u16(out, v ushr 16) }

    /** A ZSPR like the community files: 29-byte header, UTF-16 name and author, pixels, palette. */
    private fun zspr(name: String, author: String, pixels: ByteArray = ByteArray(0x7000), palette: ByteArray = ByteArray(124)): ByteArray {
        val names = ByteArrayOutputStream().apply {
            write((name + "\u0000").toByteArray(Charsets.UTF_16LE))
            write((author + "\u0000").toByteArray(Charsets.UTF_16LE))
            write(0)
        }.toByteArray()
        val pixelOffset = 29 + names.size
        return ByteArrayOutputStream().apply {
            write("ZSPR".toByteArray()); write(1); u32(this, 0)
            u32(this, pixelOffset); u16(this, pixels.size)
            u32(this, pixelOffset + pixels.size); u16(this, palette.size)
            u16(this, 1); write(ByteArray(6))
            write(names); write(pixels); write(palette)
        }.toByteArray()
    }

    @Test
    fun parsesNameAndAuthor() {
        val s = LinkSprites.parse(zspr("Megaman X", "PlaguedOne"))!!
        assertEquals("Megaman X", s.name)
        assertEquals("PlaguedOne", s.author)
        assertEquals(0x7000, s.pixels.size)
    }

    @Test
    fun rejectsWhatUpstreamRejects() {
        assertNull(LinkSprites.parse(zspr("Short", "x", pixels = ByteArray(0x6000))))
        assertNull(LinkSprites.parse("ZSPR".toByteArray()))
        assertNull(LinkSprites.parse(ByteArray(0x8000)))
        val truncated = zspr("Cut", "x").copyOf(0x5000)
        assertNull(LinkSprites.parse(truncated))
    }

    @Test
    fun previewUsesFrontHeadOverFrontBody() {
        val pixels = ByteArray(0x7000)
        // Top-left pixel of the head tile at sheet (16,0): tile 2, color 1 (bitplane 0 set).
        pixels[2 * 32] = 0x80.toByte()
        // Top-left pixel of the body tile at sheet (48,16): tile 16*2+6 = 38, color 2 (bitplane 1).
        pixels[38 * 32 + 1] = 0x80.toByte()
        val palette = ByteArray(124)
        palette[0] = 0x1f // color 1: red
        palette[2] = 0xe0.toByte(); palette[3] = 0x03 // color 2: green
        val out = LinkSprites.previewPixels(LinkSprites.parse(zspr("T", "a", pixels, palette))!!)
        assertEquals(0xFFFF0000.toInt(), out[0]) // head at y=0
        assertEquals(0xFF00FF00.toInt(), out[8 * 16]) // body starts 8 rows lower
        assertEquals(0, out[1])
    }

    @Test
    fun readsAssetFromAssetsFile() {
        // Layout read by LoadAssets(): 80 header bytes, count, key size, sizes, key, 4-aligned data.
        val assets = listOf(byteArrayOf(1, 2, 3), byteArrayOf(9, 8, 7, 6, 5))
        val data = ByteArrayOutputStream().apply {
            write(ByteArray(80)); u32(this, assets.size); u32(this, 2)
            assets.forEach { u32(this, it.size) }
            write(byteArrayOf(0x41, 0x42))
            var pos = 80 + 8 + assets.size * 4 + 2
            for (a in assets) {
                while (pos % 4 != 0) { write(0); pos++ }
                write(a); pos += a.size
            }
        }.toByteArray()
        assertArrayEquals(assets[1], LinkSprites.asset(data, 1))
        assertArrayEquals(assets[0], LinkSprites.asset(data, 0))
        assertNull(LinkSprites.asset(data, 2))
    }

    @Test
    fun importStoresUnderSpritesAndKeepsDuplicatesApart() {
        val dir: File = Files.createTempDirectory("game").toFile()
        val sprites = LinkSprites(dir)
        val a = sprites.import(ByteArrayInputStream(zspr("Alice", "Artheau")), "alice.1.zspr") as LinkSprites.ImportResult.Imported
        assertEquals("sprites/alice.zspr", a.sprite.iniValue)
        sprites.import(ByteArrayInputStream(zspr("Alice", "Artheau")), null) // same file again: reused
        val other = zspr("Alice", "Someone else")
        val b = sprites.import(ByteArrayInputStream(other), null) as LinkSprites.ImportResult.Imported
        assertEquals("sprites/alice-2.zspr", b.sprite.iniValue)
        assertEquals(2, sprites.list().size)
        assertTrue(sprites.import(ByteArrayInputStream(ByteArray(10)), null) is LinkSprites.ImportResult.NotRecognized)

        val ini = Ini("[Graphics]\nLinkGraphics = sprites/alice.zspr\n")
        assertNotNull(sprites.selected(ini))
        sprites.delete(a.sprite)
        assertNull(sprites.selected(ini))
    }
}
