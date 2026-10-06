package io.github.doutorraposo.moonpearl

import java.util.zip.CRC32

/**
 * Applies BPS patches, mirroring ApplyBps() in upstream src/util.c.
 *
 * zelda3_assets.bps turns the stock US ROM into zelda3_assets.dat, so the app can
 * build the assets on the device without Python.
 */
object Bps {
    class InvalidPatch(message: String) : Exception(message)
    class SourceMismatch(val expectedCrc: Long, val actualCrc: Long) :
        Exception("source CRC %08X, expected %08X".format(actualCrc, expectedCrc))

    data class Header(val sourceSize: Int, val targetSize: Int, val sourceCrc: Long, val targetCrc: Long)

    fun header(patch: ByteArray): Header {
        if (patch.size < 4 + 3 + 12 || !patch.copyOfRange(0, 4).contentEquals(MAGIC))
            throw InvalidPatch("not a BPS patch")
        val footer = patch.size - 12
        if (crc32(patch, 0, patch.size - 4) != readU32(patch, footer + 8))
            throw InvalidPatch("patch is corrupted")
        val reader = Reader(patch, 4)
        val sourceSize = reader.number()
        val targetSize = reader.number()
        return Header(
            sourceSize = sourceSize.toIntExact(),
            targetSize = targetSize.toIntExact(),
            sourceCrc = readU32(patch, footer),
            targetCrc = readU32(patch, footer + 4),
        )
    }

    fun apply(source: ByteArray, patch: ByteArray): ByteArray {
        val header = header(patch)
        val sourceCrc = crc32(source, 0, source.size)
        if (source.size != header.sourceSize || sourceCrc != header.sourceCrc)
            throw SourceMismatch(header.sourceCrc, sourceCrc)

        val end = patch.size - 12
        val reader = Reader(patch, 4)
        reader.number() // source size
        reader.number() // target size
        reader.skip(reader.number().toIntExact()) // metadata

        val target = ByteArray(header.targetSize)
        var out = 0
        var sourceRelative = 0
        var targetRelative = 0
        while (reader.pos < end) {
            val cmd = reader.number()
            val length = ((cmd ushr 2) + 1).toIntExact()
            if (out + length > target.size) throw InvalidPatch("write past end of target")
            when ((cmd and 3).toInt()) {
                0 -> { // SourceRead
                    if (out + length > source.size) throw InvalidPatch("read past end of source")
                    System.arraycopy(source, out, target, out, length)
                    out += length
                }
                1 -> { // TargetRead
                    reader.copyTo(target, out, length)
                    out += length
                }
                2 -> { // SourceCopy
                    sourceRelative += reader.signedOffset()
                    if (sourceRelative < 0 || sourceRelative + length > source.size)
                        throw InvalidPatch("source copy out of range")
                    System.arraycopy(source, sourceRelative, target, out, length)
                    sourceRelative += length
                    out += length
                }
                else -> { // TargetCopy; may overlap the bytes being written, so copy one at a time
                    targetRelative += reader.signedOffset()
                    if (targetRelative < 0 || targetRelative >= out)
                        throw InvalidPatch("target copy out of range")
                    repeat(length) { target[out++] = target[targetRelative++] }
                }
            }
        }
        if (out != target.size) throw InvalidPatch("patch ended early")
        if (crc32(target, 0, target.size) != header.targetCrc) throw InvalidPatch("output CRC mismatch")
        return target
    }

    fun crc32(data: ByteArray, offset: Int, length: Int): Long =
        CRC32().apply { update(data, offset, length) }.value

    private val MAGIC = "BPS1".toByteArray(Charsets.US_ASCII)

    private fun readU32(data: ByteArray, at: Int): Long =
        (data[at].toLong() and 0xff) or
            ((data[at + 1].toLong() and 0xff) shl 8) or
            ((data[at + 2].toLong() and 0xff) shl 16) or
            ((data[at + 3].toLong() and 0xff) shl 24)

    private fun Long.toIntExact(): Int {
        if (this < 0 || this > Int.MAX_VALUE) throw InvalidPatch("value out of range")
        return toInt()
    }

    private class Reader(val data: ByteArray, var pos: Int) {
        fun byte(): Int {
            if (pos >= data.size - 12) throw InvalidPatch("unexpected end of patch")
            return data[pos++].toInt() and 0xff
        }

        fun number(): Long {
            var result = 0L
            var shift = 1L
            while (true) {
                val x = byte()
                result += (x and 0x7f) * shift
                if (x and 0x80 != 0) return result
                shift = shift shl 7
                result += shift
                if (shift > (1L shl 49)) throw InvalidPatch("number too large")
            }
        }

        fun signedOffset(): Int {
            val n = number()
            val magnitude = (n ushr 1).toIntExact()
            return if (n and 1L != 0L) -magnitude else magnitude
        }

        fun skip(n: Int) {
            if (pos + n > data.size - 12) throw InvalidPatch("unexpected end of patch")
            pos += n
        }

        fun copyTo(dest: ByteArray, at: Int, length: Int) {
            if (pos + length > data.size - 12) throw InvalidPatch("unexpected end of patch")
            System.arraycopy(data, pos, dest, at, length)
            pos += length
        }
    }
}
