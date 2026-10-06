package io.github.doutorraposo.moonpearl

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Owns the directory the native game runs from (Context.filesDir):
 *
 *   zelda3.ini           stock upstream config with Android defaults applied
 *   zelda3_assets.dat    built on the device from the user's ROM
 *   saves/               sram.dat, save states, saves/ref/ reference saves
 */
class GameData(private val context: Context) {
    val dir: File = context.filesDir
    val assetsFile = File(dir, "zelda3_assets.dat")
    val iniFile = File(dir, "zelda3.ini")

    sealed interface ImportResult {
        data object Installed : ImportResult
        data class WrongRom(val crc: Long) : ImportResult
        data object NotRecognized : ImportResult
    }

    fun hasAssets(): Boolean = assetsFile.isFile && hasAssetsSignature(assetsFile.inputStream().use { it.readAtMost(SIG.size) })

    /** Builds zelda3_assets.dat from a ROM (.sfc/.smc, optionally zipped) or copies a ready-made .dat. Blocking. */
    fun import(uri: Uri): ImportResult {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_INPUT + 1) }
            ?: throw IOException("cannot open $uri")
        if (raw.size > MAX_INPUT) return ImportResult.NotRecognized
        val data = if (isZip(raw)) unzipFirstGameFile(raw) ?: return ImportResult.NotRecognized else raw

        if (hasAssetsSignature(data)) {
            writeAtomically(assetsFile, data)
            return ImportResult.Installed
        }

        val patch = context.assets.open(PATCH_ASSET).use { it.readBytes() }
        val expected = Bps.header(patch)
        val rom = stripCopierHeader(data)
        if (rom.size != expected.sourceSize) return ImportResult.WrongRom(Bps.crc32(rom, 0, rom.size))
        return try {
            writeAtomically(assetsFile, Bps.apply(rom, patch))
            ImportResult.Installed
        } catch (e: Bps.SourceMismatch) {
            ImportResult.WrongRom(e.actualCrc)
        }
    }

    /** Makes sure the config and save folders exist. Called before the game starts. */
    fun prepare() {
        File(dir, "saves/ref").mkdirs()
        val existing = iniFile.takeIf { it.exists() }?.readText()
        val ini = if (existing != null) {
            Ini(existing)
        } else {
            Ini(context.assets.open("zelda3.ini").use { it.readBytes().decodeToString() }).apply(::applyAndroidDefaults)
        }
        if (ini["Graphics", "LinkGraphics"] != null && LinkSprites(dir).selected(ini) == null) ini.remove("Graphics", "LinkGraphics")
        // Recopied whenever the app is installed or updated, even without a version change.
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        Shaders.installBuiltins(context, dir, "${BuildConfig.VERSION_CODE}-$installed")
        Shaders.validate(ini, dir)
        // The touch overlay depends on this mapping, so keep it in place even if the file was edited.
        ini["KeyMap", "Controls"] = TouchControlsView.KEYMAP_CONTROLS
        for ((key, value) in GameKeys.bindings) ini["KeyMap", key] = value
        if (ini.text != existing) writeIni(ini)
        val refs = context.assets.list("saves/ref").orEmpty()
        for (name in refs) {
            val target = File(dir, "saves/ref/$name")
            if (!target.exists())
                context.assets.open("saves/ref/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    /** See [Shaders.takeFailure]: the shader that kept the last session from running, switched off. */
    fun takeShaderFailure(): String? {
        if (!File(dir, "shader_check").isFile) return null
        val ini = readIni()
        val before = ini.text
        val path = Shaders.takeFailure(ini, dir)
        if (ini.text != before) writeIni(ini)
        return path
    }

    /** The message of the last fatal game error (written by android_main.c), consumed once. */
    fun takeLastError(): String? {
        val file = File(dir, "last_error.txt")
        if (!file.isFile) return null
        return file.readText().trim().also { file.delete() }.ifEmpty { null }
    }

    fun readIni(): Ini {
        prepare()
        return Ini(iniFile.readText())
    }

    fun writeIni(ini: Ini) = writeAtomically(iniFile, ini.text.toByteArray())

    private fun applyAndroidDefaults(ini: Ini) {
        ini["Graphics", "Fullscreen"] = "1"
        ini["General", "Autosave"] = "1"
        ini["General", "ExtendedAspectRatio"] = AspectRatio.forScreen(context).iniValue
    }

    private fun stripCopierHeader(data: ByteArray): ByteArray =
        if (data.size % 1024 == 512) data.copyOfRange(512, data.size) else data

    private fun isZip(data: ByteArray) =
        data.size > 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() &&
            data[2] == 3.toByte() && data[3] == 4.toByte()

    private fun unzipFirstGameFile(zip: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: return null
                val name = entry.name.lowercase()
                if (!entry.isDirectory && GAME_EXTENSIONS.any { name.endsWith(it) }) {
                    val bytes = zin.readAtMost(MAX_INPUT + 1)
                    return if (bytes.size > MAX_INPUT) null else bytes
                }
            }
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("cannot write ${target.name}")
        }
    }

    companion object {
        const val PATCH_ASSET = "zelda3_assets.bps"
        private const val MAX_INPUT = 16 * 1024 * 1024
        private val GAME_EXTENSIONS = listOf(".sfc", ".smc", ".dat")
        private val SIG: ByteArray = BuildConfig.ASSETS_SIG.split(',').map { it.toInt().toByte() }.toByteArray()

        fun hasAssetsSignature(data: ByteArray) =
            data.size >= SIG.size && data.copyOfRange(0, SIG.size).contentEquals(SIG)
    }
}

/** Reads up to [limit] bytes (InputStream.readNBytes needs API 33). */
fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
