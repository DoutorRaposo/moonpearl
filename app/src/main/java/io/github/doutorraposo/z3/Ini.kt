package io.github.doutorraposo.z3

/**
 * Minimal editor for zelda3.ini that keeps comments and layout intact, so the
 * file stays readable and upstream documentation inside it remains valid.
 */
class Ini(text: String) {
    private val lines = text.replace("\r\n", "\n").split('\n').toMutableList()

    val text: String get() = lines.joinToString("\n")

    operator fun get(section: String, key: String): String? {
        val i = find(section, key) ?: return null
        return lines[i].substringAfter('=').trim()
    }

    operator fun set(section: String, key: String, value: String) {
        val i = find(section, key)
        if (i != null) {
            lines[i] = lines[i].substringBefore('=').trimEnd() + " = " + value
            return
        }
        var header = lines.indexOfFirst { sectionName(it).equals(section, ignoreCase = true) }
        if (header < 0) {
            lines += listOf("", "[$section]")
            header = lines.lastIndex
        }
        // Insert after the last non-blank line of the section.
        var at = header + 1
        var lastContent = header
        while (at < lines.size && sectionName(lines[at]) == null) {
            if (lines[at].isNotBlank()) lastContent = at
            at++
        }
        lines.add(lastContent + 1, "$key = $value")
    }

    fun getBool(section: String, key: String) = when (get(section, key)?.lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }

    fun setBool(section: String, key: String, value: Boolean) = set(section, key, if (value) "1" else "0")

    private fun find(section: String, key: String): Int? {
        var current: String? = null
        for ((i, line) in lines.withIndex()) {
            val name = sectionName(line)
            if (name != null) {
                current = name
                continue
            }
            if (!section.equals(current, ignoreCase = true)) continue
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || !trimmed.contains('=')) continue
            if (trimmed.substringBefore('=').trim().equals(key, ignoreCase = true)) return i
        }
        return null
    }

    private fun sectionName(line: String): String? {
        val t = line.trim()
        return if (t.startsWith("[") && t.endsWith("]")) t.substring(1, t.length - 1).trim() else null
    }
}
