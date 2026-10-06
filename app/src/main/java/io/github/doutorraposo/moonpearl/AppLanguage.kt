package io.github.doutorraposo.moonpearl

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language of the app's own screens. Android 13+ has per-app languages built in (also
 * reachable from system settings), so it is used there; older versions keep the choice in
 * preferences and every activity applies it in attachBaseContext through [wrap].
 */
object AppLanguage {
    /** Language tags offered in the launcher; "" follows the system. */
    val options = listOf("", "en", "pt-BR")

    private const val PREFS = "app"
    private const val KEY = "app_language"

    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val tags = context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            options.firstOrNull { it.isNotEmpty() && tags.startsWith(it.substringBefore('-')) } ?: ""
        } else {
            stored(context)
        }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates every activity of the app with the new language.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
            activity.recreate()
        }
    }

    /** Before Android 13: a context whose resources use the chosen language. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = stored(base).ifEmpty { return base }
        val config = base.resources.configuration.apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }

    private fun stored(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
}
