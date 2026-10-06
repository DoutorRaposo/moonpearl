package io.github.doutorraposo.z3

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

/** BPS variable-length number encoding. */
private fun ByteArrayOutputStream.writeNumber(value: Long) {
    var v = value
    while (true) {
        val x = (v and 0x7f).toInt()
        v = v ushr 7
        if (v == 0L) { write(0x80 or x); return }
        write(x)
        v--
    }
}

class BpsTest {
    /** Tiny BPS writer, enough to exercise every command type. */
    private class PatchWriter(private val source: ByteArray) {
        private val body = ByteArrayOutputStream()
        private val target = ByteArrayOutputStream()
        private var sourceRelative = 0
        private var targetRelative = 0

        private fun number(value: Long) = body.writeNumber(value)

        private fun offset(delta: Int) = number((Math.abs(delta).toLong() shl 1) or if (delta < 0) 1 else 0)

        fun sourceRead(length: Int) {
            number(((length - 1).toLong() shl 2) or 0)
            val at = target.size()
            target.write(source, at, length)
        }

        fun targetRead(bytes: ByteArray) {
            number(((bytes.size - 1).toLong() shl 2) or 1)
            body.write(bytes)
            target.write(bytes)
        }

        fun sourceCopy(from: Int, length: Int) {
            number(((length - 1).toLong() shl 2) or 2)
            offset(from - sourceRelative)
            target.write(source, from, length)
            sourceRelative = from + length
        }

        fun targetCopy(from: Int, length: Int) {
            number(((length - 1).toLong() shl 2) or 3)
            offset(from - targetRelative)
            repeat(length) { i -> target.write(target.toByteArray()[from + i].toInt()) }
            targetRelative = from + length
        }

        fun build(): Pair<ByteArray, ByteArray> {
            val out = ByteArrayOutputStream()
            out.write("BPS1".toByteArray())
            out.writeNumber(source.size.toLong())
            out.writeNumber(target.size().toLong())
            out.writeNumber(0)
            out.write(body.toByteArray())
            fun u32(v: Long) = repeat(4) { i -> out.write(((v ushr (8 * i)) and 0xff).toInt()) }
            u32(crc(source))
            u32(crc(target.toByteArray()))
            u32(crc(out.toByteArray()))
            return out.toByteArray() to target.toByteArray()
        }
    }

    private val source = Random(1234).nextBytes(4096)

    private fun samplePatch(): Pair<ByteArray, ByteArray> = PatchWriter(source).apply {
        sourceRead(100)
        targetRead(byteArrayOf(1, 2, 3, 4, 5))
        sourceCopy(2000, 300)
        sourceCopy(50, 20) // backwards
        targetCopy(100, 4)
        targetCopy(424, 40) // overlapping run, like RLE
        sourceRead(16)
    }.build()

    @Test
    fun appliesEveryCommandType() {
        val (patch, expected) = samplePatch()
        assertArrayEquals(expected, Bps.apply(source, patch))
    }

    @Test
    fun rejectsWrongSource() {
        val (patch, _) = samplePatch()
        val other = source.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val e = assertThrows(Bps.SourceMismatch::class.java) { Bps.apply(other, patch) }
        assertEquals(crc(source), e.expectedCrc)
        assertEquals(crc(other), e.actualCrc)
    }

    @Test
    fun rejectsCorruptedPatch() {
        val (patch, _) = samplePatch()
        patch[20] = (patch[20] + 1).toByte()
        assertThrows(Bps.InvalidPatch::class.java) { Bps.apply(source, patch) }
    }

    @Test
    fun bundledPatchTargetsTheUsRom() {
        val header = Bps.header(File("src/main/assets/zelda3_assets.bps").readBytes())
        assertEquals(1024 * 1024, header.sourceSize)
        assertEquals(0x777AAC2FL, header.sourceCrc)
    }
}
