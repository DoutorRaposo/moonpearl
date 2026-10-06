package io.github.doutorraposo.moonpearl

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import java.io.File

/**
 * How the picture is scaled to the screen: plain (sharp or linear) or through a GLSL shader
 * preset. Everything goes through upstream's OpenGL output, which runs on OpenGL ES here (patches
 * 0007 and 0008), so the in-game menu can switch filters while the game runs. Devices without
 * OpenGL ES 3, or where the OpenGL output failed to start, use upstream's SDL renderer and get
 * only the plain options. Presets are run from the game folder: the bundled ones under
 * shaders/builtin, the player's own (copied from a folder they pick) under shaders/custom.
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

    /** What kept the last session from running: a shader, or the OpenGL output itself. */
    sealed interface Failure {
        data class Shader(val path: String) : Failure
        data object OpenGl : Failure
    }

    const val DIR = "shaders"
    private const val BUILTIN = "builtin"
    private const val CUSTOM = "custom"
    private const val VERSION_FILE = ".version"
    private const val CHECK_FILE = "shader_check"
    private const val CHECK_OPENGL = "opengl"
    private val SHADER_FILES = setOf("glsl", "glslp", "png", "inc", "h")
    private const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

    fun usesOpenGl(ini: Ini) = ini["Graphics", "OutputMethod"].orEmpty().startsWith("OpenGL", ignoreCase = true)

    /** OpenGL ES 3 (upstream's minimum), unless the OpenGL output already failed here. */
    fun openGlAvailable(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000 && !AppPrefs(context).openGlFailed
    }

    fun current(ini: Ini): Choice {
        val shader = ini["Graphics", "Shader"].orEmpty()
        return when {
            shader.isNotEmpty() && usesOpenGl(ini) -> Choice.Shader(shader)
            ini.getBool("Graphics", "LinearFiltering") -> Choice.Smooth
            else -> Choice.Sharp
        }
    }

    fun apply(ini: Ini, choice: Choice, openGl: Boolean = true) {
        val shader = (choice as? Choice.Shader)?.path?.takeIf { openGl }
        ini["Graphics", "OutputMethod"] = if (openGl) "OpenGL ES" else "SDL"
        ini["Graphics", "Shader"] = shader.orEmpty()
        ini.setBool("Graphics", "LinearFiltering", choice == Choice.Smooth)
    }

    /** Moves the output to OpenGL where available (older installs used SDL), or off it. */
    fun useOpenGl(ini: Ini, openGl: Boolean) {
        if (usesOpenGl(ini) != openGl) apply(ini, current(ini), openGl)
    }

    /** Falls back to plain output when the chosen shader is gone, e.g. after removing imports. */
    fun validate(ini: Ini, gameDir: File) {
        val choice = current(ini)
        if (choice is Choice.Shader && !File(gameDir, choice.path).isFile) apply(ini, Choice.Sharp, usesOpenGl(ini))
    }

    /**
     * Called as the game starts and when the menu switches filters: with OpenGL output, writes a
     * marker that android_main.c removes once the game has run a moment (see takeFailure).
     */
    fun markGameStart(ini: Ini, gameDir: File) {
        val file = File(gameDir, CHECK_FILE)
        val choice = current(ini)
        when {
            choice is Choice.Shader -> file.writeText(choice.path)
            usesOpenGl(ini) -> file.writeText(CHECK_OPENGL)
            else -> file.delete()
        }
    }

    /**
     * What the last game session was running when it hung or crashed before clearing the
     * marker, if it did. A shader is switched off; for the OpenGL output itself the caller
     * decides (see GameData.takeShaderFailure). Consumed once.
     */
    fun takeFailure(ini: Ini, gameDir: File): Failure? {
        val file = File(gameDir, CHECK_FILE)
        if (!file.isFile) return null
        val path = file.readText().trim()
        file.delete()
        return when {
            path.isEmpty() -> null
            path == CHECK_OPENGL -> Failure.OpenGl
            else -> {
                if (current(ini) == Choice.Shader(path)) apply(ini, Choice.Sharp)
                Failure.Shader(path)
            }
        }
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
