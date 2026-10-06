package io.github.doutorraposo.z3

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveManagerTest {
    private val dir: File = Files.createTempDirectory("saves").toFile()
    private val saves = File(dir, "saves").apply { mkdirs() }
    private val manager = SaveManager(dir)

    /** Minimal valid SRAM: file 1 with just the marker and a fixed-up checksum. */
    private fun validSram(fill: Byte = 0): ByteArray {
        val d = ByteArray(Sram.SIZE) { fill }
        for (i in 0 until 0x500) d[i] = 0
        d[0x3E5] = 0xAA.toByte(); d[0x3E6] = 0x55
        var sum = 0
        for (i in 0 until 0x4FE step 2) sum += (d[i].toInt() and 0xff) or ((d[i + 1].toInt() and 0xff) shl 8)
        val fix = (0x5A5A - sum) and 0xffff
        d[0x4FE] = fix.toByte(); d[0x4FF] = (fix shr 8).toByte()
        return d
    }

    @Test
    fun backupRoundTrip() {
        File(saves, "sram.dat").writeBytes(validSram(1))
        File(saves, "save3.sav").writeBytes(byteArrayOf(1, 2, 3))
        File(saves, "save3.png").writeBytes(byteArrayOf(4))
        File(saves, "unrelated.txt").writeText("keep")

        val zip = ByteArrayOutputStream().also { manager.exportZip(it) }.toByteArray()
        File(saves, "save3.sav").delete()
        File(saves, "save5.sav").writeBytes(byteArrayOf(9)) // not in the backup: must go

        assertEquals(SaveManager.Result.Done, manager.restoreZip(ByteArrayInputStream(zip)))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(saves, "save3.sav").readBytes())
        assertArrayEquals(validSram(1), File(saves, "sram.dat").readBytes())
        assertFalse(File(saves, "save5.sav").exists())
        assertTrue(File(saves, "unrelated.txt").exists())
    }

    @Test
    fun rejectsZipWithoutSaves() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("photo.jpg")); it.write(1) }
        }.toByteArray()
        assertEquals(SaveManager.Result.NotRecognized, manager.restoreZip(ByteArrayInputStream(zip)))
    }

    @Test
    fun importSrmDropsResumePointAndKeepsBackup() {
        File(saves, "sram.dat").writeBytes(validSram(7))
        File(saves, "save0.sav").writeBytes(byteArrayOf(1))
        File(saves, "save1.sav").writeBytes(byteArrayOf(2))

        assertEquals(SaveManager.Result.Done, manager.importSrm(ByteArrayInputStream(validSram(3))))
        assertArrayEquals(validSram(3), manager.readSram())
        assertArrayEquals(validSram(7), File(saves, "sram.bak").readBytes())
        assertFalse(File(saves, "save0.sav").exists())
        assertTrue(File(saves, "save1.sav").exists())
    }

    @Test
    fun eraseFileClearsOnlyThatFileAndItsCopy() {
        val sram = validSram(5) // file 1 valid, the rest filled with 5s
        File(saves, "sram.dat").writeBytes(sram)
        File(saves, "save0.sav").writeBytes(byteArrayOf(1))

        manager.eraseFile(0)
        val after = manager.readSram()!!
        assertTrue((0 until 0x500).all { after[it] == 0.toByte() })
        assertTrue((0xF00 until 0x1400).all { after[it] == 0.toByte() })
        assertEquals(5.toByte(), after[0x500]) // file 2 untouched
        assertFalse(Sram.hasAnyFile(after))
        assertArrayEquals(sram, File(saves, "sram.bak").readBytes())
        assertFalse(File(saves, "save0.sav").exists())
    }

    @Test
    fun deleteGameFilesKeepsBackupAndStates() {
        File(saves, "sram.dat").writeBytes(validSram())
        File(saves, "save0.sav").writeBytes(byteArrayOf(1))
        File(saves, "save2.sav").writeBytes(byteArrayOf(2))

        manager.deleteGameFiles()
        assertFalse(File(saves, "sram.dat").exists())
        assertArrayEquals(validSram(), File(saves, "sram.bak").readBytes())
        assertFalse(File(saves, "save0.sav").exists())
        assertTrue(File(saves, "save2.sav").exists())
    }

    @Test
    fun importSrmRejectsOtherFiles() {
        assertEquals(SaveManager.Result.NotRecognized, manager.importSrm(ByteArrayInputStream(ByteArray(Sram.SIZE))))
        assertEquals(SaveManager.Result.NotRecognized, manager.importSrm(ByteArrayInputStream(ByteArray(100))))
    }
}
