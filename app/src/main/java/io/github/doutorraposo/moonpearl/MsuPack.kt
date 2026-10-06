package io.github.doutorraposo.moonpearl

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import java.io.File

/**
 * MSU-1 audio packs (replacement soundtracks). Upstream opens each track with fopen() using
 * [Sound] MSUPath, but the pack sits in a folder the player picked with the system file picker,
 * which has no file path. Before the game starts, each track is opened through that folder's
 * permission and linked into the game folder as msu/track-N.ext -> /proc/self/fd/<fd>.
 * android_main.c opens those links by duplicating the descriptor (patch 0006 lets it replace
 * upstream's fopen), so the original files are read in place and nothing is copied.
 */
object MsuPack {
    enum class Format(val extension: String) { PCM("pcm"), OPUZ("opuz") }

    data class Track(val number: Int, val documentId: String)

    data class Pack(val prefix: String, val format: Format, val tracks: List<Track>) {
        /** MSU Deluxe adds region and entrance themes from track 37 on. */
        val hasDeluxeTracks get() = tracks.any { it.number > MAX_STANDARD_TRACK }
    }

    private const val MAX_STANDARD_TRACK = 34
    private val TRACK_NAME = Regex("""^(.*?)-(\d{1,3})\.(pcm|opuz)$""", RegexOption.IGNORE_CASE)
    private const val LINK_DIR = "msu"

    /** Picks the pack among file names: the prefix and format with the most tracks. */
    fun detect(names: List<Pair<String, String>>): Pack? {
        val groups = names.mapNotNull { (name, id) ->
            TRACK_NAME.matchEntire(name)?.let { m ->
                val format = if (m.groupValues[3].equals("opuz", true)) Format.OPUZ else Format.PCM
                Triple(m.groupValues[1] to format, m.groupValues[2].toInt(), id)
            }
        }.groupBy({ it.first }, { Track(it.second, it.third) })
        val (key, tracks) = groups.maxByOrNull { it.value.size } ?: return null
        return Pack(key.first, key.second, tracks.distinctBy { it.number }.sortedBy { it.number })
    }

    fun scan(context: Context, tree: Uri): Pack? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val names = ArrayList<Pair<String, String>>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) names += c.getString(0) to c.getString(1) }
        return detect(names)
    }

    /** zelda3.ini values for a pack (upstream: PCM needs 44100 Hz, OPUZ 48000 Hz). */
    fun iniValues(pack: Pack, deluxe: Boolean): Map<String, String> = mapOf(
        "EnableMSU" to when {
            deluxe && pack.format == Format.OPUZ -> "deluxe-opuz"
            deluxe -> "deluxe"
            pack.format == Format.OPUZ -> "opuz"
            else -> "true"
        },
        "MSUPath" to "$LINK_DIR/track-",
        "AudioFreq" to if (pack.format == Format.OPUZ) "48000" else "44100",
    )

    /**
     * Called in the game process before the game starts. Opens the tracks, links them and
     * updates zelda3.ini; returns the number of tracks linked. The descriptors stay open for
     * the life of the game process, which ends with the game.
     */
    fun prepareForGame(context: Context, ini: Ini, prefs: AppPrefs): Int {
        val linkDir = File(context.filesDir, LINK_DIR)
        linkDir.listFiles()?.forEach { it.delete() }
        val tree = prefs.msuFolder?.let(Uri::parse)
        val pack = if (prefs.msuEnabled && tree != null) runCatching { scan(context, tree) }.getOrNull() else null
        if (pack == null) {
            ini["Sound", "EnableMSU"] = "false"
            return 0
        }
        linkDir.mkdirs()
        var linked = 0
        for (track in pack.tracks) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, track.documentId)
            val fd = runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.detachFd() }.getOrNull() ?: continue
            runCatching {
                Os.symlink("/proc/self/fd/$fd", File(linkDir, "track-${track.number}.${pack.format.extension}").path)
                linked++
            }
        }
        val deluxe = prefs.msuDeluxe && pack.hasDeluxeTracks
        for ((key, value) in iniValues(pack, deluxe)) ini["Sound", key] = value
        ini["Sound", "MSUVolume"] = "${prefs.msuVolume}%"
        return linked
    }
}
