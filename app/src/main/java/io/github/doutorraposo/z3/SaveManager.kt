package io.github.doutorraposo.z3

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup, restore and .srm exchange for the files under saves/. Only the player's own data is
 * touched: sram.dat (the three game files) and the save states saveN.sav with their previews.
 */
class SaveManager(gameDir: File) {
    private val savesDir = File(gameDir, "saves")
    val sramFile = File(savesDir, "sram.dat")
    private val states = SaveStates(gameDir)

    sealed interface Result {
        data object Done : Result
        data object NotRecognized : Result
    }

    fun readSram(): ByteArray? = sramFile.takeIf { it.isFile }?.readBytes()?.takeIf { it.size == Sram.SIZE }

    fun hasData() = backupFiles().isNotEmpty()

    fun exportSrm(out: OutputStream) {
        out.write(readSram() ?: throw IOException("no game files yet"))
    }

    /**
     * Replaces the game files with an .srm from an emulator or another device. The resume
     * point is dropped: save states carry their own copy of the save RAM, so loading it on the
     * next start would bring the old files back.
     */
    fun importSrm(input: InputStream): Result {
        val data = input.readAtMost(Sram.SIZE + 1)
        if (data.size != Sram.SIZE || !Sram.hasAnyFile(data)) return Result.NotRecognized
        savesDir.mkdirs()
        if (sramFile.isFile) sramFile.copyTo(File(savesDir, "sram.bak"), overwrite = true)
        writeAtomically(sramFile, data)
        deleteState(0)
        return Result.Done
    }

    fun exportZip(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            for (file in backupFiles()) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Restores a backup made by [exportZip], replacing every current save. */
    fun restoreZip(input: InputStream): Result {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || !isBackupName(name)) continue
                val bytes = zip.readAtMost(MAX_ENTRY + 1)
                if (bytes.size > MAX_ENTRY) return Result.NotRecognized
                entries[name] = bytes
            }
        }
        val sram = entries["sram.dat"]
        if (entries.isEmpty() || (sram != null && sram.size != Sram.SIZE)) return Result.NotRecognized

        savesDir.mkdirs()
        backupFiles().forEach { it.delete() }
        for ((name, bytes) in entries) writeAtomically(File(savesDir, name), bytes)
        return Result.Done
    }

    fun deleteState(slot: Int) {
        states.stateFile(slot).delete()
        states.thumbnailFile(slot).delete()
    }

    private fun backupFiles(): List<File> =
        savesDir.listFiles().orEmpty().filter { it.isFile && isBackupName(it.name) }.sortedBy { it.name }

    private fun isBackupName(name: String) = name == "sram.dat" || STATE_NAME.matches(name)

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("cannot write ${target.name}")
        }
    }

    private companion object {
        val STATE_NAME = Regex("""save\d\.(sav|png)""")
        const val MAX_ENTRY = 8 * 1024 * 1024
    }
}
