package io.github.doutorraposo.moonpearl

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import java.io.File

/**
 * How the picture is scaled to the screen: upstream's plain output (sharp or linear) or a GLSL
 * shader preset. Shaders only work with upstream's OpenGL output, which runs on OpenGL ES here
 * (fixed up by patches/zelda3/0007-opengl-es-output.patch); the plain options keep the SDL
 * renderer. Presets are run from the game folder: the bundled ones under shaders/builtin, the
 * player's own (copied from a folder they pick) under shaders/custom.
 */
object Shaders {
    enum class Builtin(val id: String, @StringRes val label: Int, @StringRes val description: Int) {
        CRT("crt-lottes", R.string.shader_crt, R.string.shader_crt_desc),
        LCD("lcd3x", R.string.shader_lcd, R.string.shader_lcd_desc),
        SHARP_BILINEAR("sharp-bilinear-simple", R.string.shader_sharp_bilinear, R.string.shader_sharp_bilinear_desc),
        XBR("xbr-lv2", R.string.shader_xbr, R.string.shader_xbr_desc),
        OMNISCALE("omniscale", R.string.shader_omniscale, R.string.shader_omniscale_desc);

        val path get() = "$DIR/$BUILTIN/$id/$id.glslp"
    }

    sealed interface Choice {
        data object Sharp : Choice
        data object Smooth : Choice
        /** A preset (.glslp) or single shader (.glsl), relative to the game folder. */
        data class Shader(val path: String) : Choice
    }

    const val DIR = "shaders"
    private const val BUILTIN = "builtin"
    private const val CUSTOM = "custom"
    private const val VERSION_FILE = ".version"
    private val SHADER_FILES = setOf("glsl", "glslp", "png", "inc", "h")
    private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

    fun current(ini: Ini): Choice {
        val shader = ini["Graphics", "Shader"].orEmpty()
        val opengl = ini["Graphics", "OutputMethod"].orEmpty().startsWith("OpenGL", ignoreCase = true)
        return when {
            shader.isNotEmpty() && opengl -> Choice.Shader(shader)
            ini.getBool("Graphics", "LinearFiltering") -> Choice.Smooth
            else -> Choice.Sharp
        }
    }

    fun apply(ini: Ini, choice: Choice) {
        when (choice) {
            Choice.Sharp, Choice.Smooth -> {
                ini["Graphics", "OutputMethod"] = "SDL"
                ini["Graphics", "Shader"] = ""
                ini.setBool("Graphics", "LinearFiltering", choice == Choice.Smooth)
            }
            is Choice.Shader -> {
                ini["Graphics", "OutputMethod"] = "OpenGL ES"
                ini["Graphics", "Shader"] = choice.path
                ini.setBool("Graphics", "LinearFiltering", false)
            }
        }
    }

    /** Falls back to plain output when the chosen shader is gone, e.g. after removing imports. */
    fun validate(ini: Ini, gameDir: File) {
        val choice = current(ini)
        if (choice is Choice.Shader && !File(gameDir, choice.path).isFile) apply(ini, Choice.Sharp)
    }

    private const val CHECK_FILE = "shader_check"

    /** Called as the game starts; android_main.c removes the file after ~2 s of running. */
    fun markGameStart(ini: Ini, gameDir: File) {
        val file = File(gameDir, CHECK_FILE)
        val choice = current(ini)
        if (choice is Choice.Shader) file.writeText(choice.path) else file.delete()
    }

    /**
     * The shader the last game session started with, if the game never ran long enough to
     * clear the marker (it hung or crashed). Switches it off; returns its path. Consumed once.
     */
    fun takeFailure(ini: Ini, gameDir: File): String? {
        val file = File(gameDir, CHECK_FILE)
        if (!file.isFile) return null
        val path = file.readText().trim()
        file.delete()
        if (path.isEmpty()) return null
        if (current(ini) == Choice.Shader(path)) apply(ini, Choice.Sharp)
        return path
    }

    fun builtin(path: String) = Builtin.entries.firstOrNull { it.path == path }

    /** Copies the bundled presets into the game folder once per app version. */
    fun installBuiltins(context: Context, gameDir: File, version: String) {
        val root = File(gameDir, "$DIR/$BUILTIN")
        val marker = File(root, VERSION_FILE)
        if (marker.isFile && marker.readText() == version) return
        root.deleteRecursively()
        fun copy(assetPath: String, target: File) {
            val children = context.assets.list(assetPath).orEmpty()
            if (children.isEmpty()) {
                target.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            } else {
                for (child in children) copy("$assetPath/$child", File(target, child))
            }
        }
        copy(DIR, root)
        marker.writeText(version)
    }

    /** Imported presets, as game-folder-relative paths: every .glslp, and .glsl files at the top. */
    fun imported(gameDir: File): List<String> {
        val root = File(gameDir, "$DIR/$CUSTOM")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && (it.extension == "glslp" || (it.extension == "glsl" && it.parentFile == root)) }
            .map { "$DIR/$CUSTOM/" + it.relativeTo(root).invariantSeparatorsPath }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
    }

    /** "shaders/custom/crt/crt-geom.glslp" -> "crt/crt-geom". */
    fun displayName(path: String) = path.removePrefix("$DIR/$CUSTOM/").substringBeforeLast('.')

    fun removeImported(gameDir: File) = File(gameDir, "$DIR/$CUSTOM").deleteRecursively()

    sealed interface ImportResult {
        data class Imported(val presets: Int) : ImportResult
        data object NothingFound : ImportResult
        data object TooBig : ImportResult
    }

    /**
     * Replaces the imported shaders with the shader files in a folder the player picked
     * (e.g. a copy of the libretro glsl-shaders collection). Presets refer to their files by
     * relative paths, so the folder structure is kept. Blocking.
     */
    fun import(context: Context, tree: Uri, gameDir: File): ImportResult {
        val staging = File(gameDir, "$DIR/.$CUSTOM-import").apply { deleteRecursively(); mkdirs() }
        var total = 0L
        fun walk(documentId: String, target: File, depth: Int): Boolean {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".") || name.contains('/')) continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 8 && !walk(id, File(target, name), depth + 1)) return false
                    } else if (name.substringAfterLast('.', "").lowercase() in SHADER_FILES) {
                        total += c.getLong(3)
                        if (total > MAX_IMPORT_BYTES) return false
                        val file = File(target, name).apply { parentFile?.mkdirs() }
                        context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
            }
            return true
        }
        val fits = walk(DocumentsContract.getTreeDocumentId(tree), staging, 0)
        if (!fits) {
            staging.deleteRecursively()
            return ImportResult.TooBig
        }
        val presets = staging.walkTopDown().count { it.isFile && it.extension in setOf("glslp", "glsl") }
        if (presets == 0) {
            staging.deleteRecursively()
            return ImportResult.NothingFound
        }
        removeImported(gameDir)
        staging.renameTo(File(gameDir, "$DIR/$CUSTOM"))
        return ImportResult.Imported(imported(gameDir).size)
    }
}
