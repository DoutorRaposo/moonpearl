package io.github.doutorraposo.moonpearl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Settings, grouped in categories; each category is its own page. */
class SettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val data = GameData(this)
        val prefs = AppPrefs(this)
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(data, prefs, onClose = ::finish)
                }
            }
        }
    }
}

private enum class Category(@StringRes val title: Int, @StringRes val summary: Int) {
    DISPLAY(R.string.section_display, R.string.settings_display_summary),
    CONTROLS(R.string.section_controls, R.string.settings_controls_summary),
    GAME(R.string.section_game, R.string.settings_game_summary),
    ENHANCEMENTS(R.string.section_enhancements, R.string.settings_enhancements_summary),
    LANGUAGE(R.string.section_language, R.string.settings_language_summary),
    ABOUT(R.string.settings_about, R.string.settings_about_summary),
}

private class Feature(val key: String, @StringRes val label: Int, @StringRes val description: Int)

/**
 * Upstream [Features] switches, in the order they appear in zelda3.ini. The descriptions follow
 * the comments there and, for the two bug fix groups, what the flags change in the source.
 */
private val features = listOf(
    Feature("ItemSwitchLR", R.string.feature_item_switch_lr, R.string.feature_item_switch_lr_desc),
    Feature("TurnWhileDashing", R.string.feature_turn_while_dashing, R.string.feature_turn_while_dashing_desc),
    Feature("MirrorToDarkworld", R.string.feature_mirror_to_darkworld, R.string.feature_mirror_to_darkworld_desc),
    Feature("CollectItemsWithSword", R.string.feature_collect_items_with_sword, R.string.feature_collect_items_with_sword_desc),
    Feature("BreakPotsWithSword", R.string.feature_break_pots_with_sword, R.string.feature_break_pots_with_sword_desc),
    Feature("DisableLowHealthBeep", R.string.feature_disable_low_health_beep, R.string.feature_disable_low_health_beep_desc),
    Feature("SkipIntroOnKeypress", R.string.feature_skip_intro, R.string.feature_skip_intro_desc),
    Feature("ShowMaxItemsInYellow", R.string.feature_show_max_items_in_yellow, R.string.feature_show_max_items_in_yellow_desc),
    Feature("MoreActiveBombs", R.string.feature_more_active_bombs, R.string.feature_more_active_bombs_desc),
    Feature("CarryMoreRupees", R.string.feature_carry_more_rupees, R.string.feature_carry_more_rupees_desc),
    Feature("MiscBugFixes", R.string.feature_misc_bug_fixes, R.string.feature_misc_bug_fixes_desc),
    Feature("GameChangingBugFixes", R.string.feature_game_changing_bug_fixes, R.string.feature_game_changing_bug_fixes_desc),
    Feature("CancelBirdTravel", R.string.feature_cancel_bird_travel, R.string.feature_cancel_bird_travel_desc),
)

@Composable
private fun SettingsScreen(data: GameData, prefs: AppPrefs, onClose: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<Category?>(null) }
    var ini by remember { mutableStateOf(data.readIni()) }
    fun editIni(block: Ini.() -> Unit) {
        val updated = Ini(ini.text).apply(block)
        data.writeIni(updated)
        ini = updated
    }

    BackHandler(enabled = open != null) { open = null }
    val back = { open = null }
    when (open) {
        null -> Page(stringResource(R.string.settings_title), onBack = onClose) {
            Section(null) {
                for (c in Category.entries) {
                    ListItem(
                        headlineContent = { Text(stringResource(c.title)) },
                        supportingContent = { Text(stringResource(c.summary)) },
                        trailingContent = { Icon(ChevronRight, null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth().clickable { open = c },
                    )
                }
            }
        }
        Category.DISPLAY -> Page(stringResource(R.string.section_display), onBack = back) {
            DisplaySettings(ini, prefs, ::editIni)
        }
        Category.CONTROLS -> Page(stringResource(R.string.section_controls), onBack = back) {
            ControlSettings(prefs)
        }
        Category.GAME -> Page(stringResource(R.string.section_game), onBack = back) {
            Section(null) {
                IniSwitch(ini, "General", "Autosave", R.string.autosave, ::editIni)
                IniSwitch(ini, "Graphics", "NoSpriteLimits", R.string.no_sprite_limits, ::editIni, R.string.no_sprite_limits_desc)
            }
        }
        Category.ENHANCEMENTS -> Page(stringResource(R.string.section_enhancements), onBack = back,
            subtitle = stringResource(R.string.enhancements_hint)) {
            Section(null) {
                for (f in features) IniSwitch(ini, "Features", f.key, f.label, ::editIni, f.description)
            }
        }
        Category.LANGUAGE -> Page(stringResource(R.string.section_language), onBack = back) {
            LanguageSettings()
        }
        Category.ABOUT -> Page(stringResource(R.string.settings_about), onBack = back) {
            AboutSettings()
        }
    }
}

@Composable
private fun DisplaySettings(ini: Ini, prefs: AppPrefs, edit: (Ini.() -> Unit) -> Unit) {
    val context = LocalContext.current
    var fillScreen by remember { mutableStateOf(prefs.fillScreen) }
    val screenRatio = remember { AspectRatio.screenRatio(context) }
    Section(null) {
        SwitchRow(stringResource(R.string.fill_screen), fillScreen) {
            fillScreen = it
            prefs.fillScreen = it
            if (it) edit { useScreenAspectRatio(context) }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = AspectRatio.fromIni(ini["General", "ExtendedAspectRatio"])
            val lines = current.croppedLinesPerEdge(screenRatio)
            val columns = current.croppedColumnsPerEdge(screenRatio)
            Hint(
                when {
                    !fillScreen -> stringResource(R.string.fill_screen_off)
                    lines > 0 -> stringResource(R.string.fill_screen_crops_lines, lines)
                    columns > 0 -> stringResource(R.string.fill_screen_crops_columns, columns)
                    else -> stringResource(R.string.fill_screen_exact)
                },
                Modifier,
            )
            Text(stringResource(R.string.aspect_ratio), style = MaterialTheme.typography.bodyMedium)
            // With fill on, the widest mode that fits is the only sensible one: anything
            // narrower is scaled up even more and loses more of the top and bottom.
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AspectRatio.entries.forEachIndexed { i, ratio ->
                    SegmentedButton(
                        selected = ratio == current,
                        enabled = !fillScreen,
                        onClick = { edit { this["General", "ExtendedAspectRatio"] = AspectRatio.toIni(this["General", "ExtendedAspectRatio"], ratio) } },
                        shape = SegmentedButtonDefaults.itemShape(i, AspectRatio.entries.size),
                    ) { Text(ratio.iniValue) }
                }
            }
            Hint(
                stringResource(if (fillScreen) R.string.aspect_ratio_auto else R.string.aspect_ratio_hint),
                Modifier.padding(bottom = 8.dp),
            )
        }
        IniSwitch(ini, "Graphics", "EnhancedMode7", R.string.enhanced_mode7, edit)
        IniSwitch(ini, "Graphics", "LinearFiltering", R.string.linear_filtering, edit)
        IniSwitch(ini, "Graphics", "DimFlashes", R.string.dim_flashes, edit, R.string.dim_flashes_desc)
    }
}

@Composable
private fun ControlSettings(prefs: AppPrefs) {
    var touchControls by remember { mutableStateOf(prefs.touchControls) }
    var touchOpacity by remember { mutableFloatStateOf(prefs.touchOpacity) }
    var menuButton by remember { mutableStateOf(prefs.menuButton) }
    var doubleTapMenu by remember { mutableStateOf(prefs.doubleTapMenu) }
    Section(R.string.settings_touch) {
        SwitchRow(stringResource(R.string.touch_controls), touchControls) {
            touchControls = it
            prefs.touchControls = it
        }
        if (touchControls) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.touch_opacity), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = touchOpacity,
                    onValueChange = { touchOpacity = it },
                    onValueChangeFinished = { prefs.touchOpacity = touchOpacity },
                    valueRange = 0.15f..1f,
                )
            }
        }
    }
    Section(R.string.settings_menu) {
        SwitchRow(stringResource(R.string.menu_button), menuButton) {
            menuButton = it
            prefs.menuButton = it
        }
        SwitchRow(stringResource(R.string.double_tap_menu), doubleTapMenu) {
            doubleTapMenu = it
            prefs.doubleTapMenu = it
        }
        Hint(stringResource(R.string.menu_hint))
    }
    Section(R.string.settings_controller) {
        Hint(stringResource(R.string.controller_hint), Modifier.padding(16.dp))
    }
}

@Composable
private fun LanguageSettings() {
    val context = LocalContext.current
    val current = remember { AppLanguage.current(context) }
    Section(null) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
            AppLanguage.options.forEachIndexed { i, tag ->
                SegmentedButton(
                    selected = tag == current,
                    onClick = { if (tag != current) AppLanguage.set(context as android.app.Activity, tag) },
                    shape = SegmentedButtonDefaults.itemShape(i, AppLanguage.options.size),
                ) {
                    Text(
                        when (tag) {
                            "" -> stringResource(R.string.language_system)
                            "en" -> "English"
                            else -> "Português"
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSettings() {
    val context = LocalContext.current
    Section(null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)
            Hint(stringResource(R.string.legal_notice), Modifier)
            OutlinedButton(onClick = { context.startActivity(Intent(context, LicensesActivity::class.java)) }) {
                Text(stringResource(R.string.licenses_title))
            }
        }
    }
}
