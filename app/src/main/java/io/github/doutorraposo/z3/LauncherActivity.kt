package io.github.doutorraposo.z3

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launcher is always dark, so ask for light system bar icons regardless of the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val data = GameData(this)
        val prefs = AppPrefs(this)
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LauncherScreen(data, prefs, onPlay = { startActivity(Intent(this, GameActivity::class.java)) })
                }
            }
        }
    }
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
private fun LauncherScreen(data: GameData, prefs: AppPrefs, onPlay: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasAssets by remember { mutableStateOf(data.hasAssets()) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var ini by remember { mutableStateOf(data.readIni()) }
    var touchControls by remember { mutableStateOf(prefs.touchControls) }
    var touchOpacity by remember { mutableFloatStateOf(prefs.touchOpacity) }
    var fillScreen by remember { mutableStateOf(prefs.fillScreen) }
    var menuButton by remember { mutableStateOf(prefs.menuButton) }
    var doubleTapMenu by remember { mutableStateOf(prefs.doubleTapMenu) }
    val screenRatio = remember { AspectRatio.screenRatio(context) }

    fun editIni(block: Ini.() -> Unit) {
        val updated = Ini(ini.text).apply(block)
        data.writeIni(updated)
        ini = updated
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        importError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { data.import(uri) } }
            importing = false
            importError = when (val r = result.getOrNull()) {
                GameData.ImportResult.Installed -> null
                is GameData.ImportResult.WrongRom -> context.getString(R.string.import_wrong_rom, "%08X".format(r.crc))
                GameData.ImportResult.NotRecognized -> context.getString(R.string.import_not_recognized)
                null -> context.getString(R.string.import_failed, result.exceptionOrNull()?.message.orEmpty())
            }
            hasAssets = data.hasAssets()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Section(R.string.section_game_data) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(if (hasAssets) R.string.assets_ready else R.string.assets_missing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (importing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.importing), style = MaterialTheme.typography.bodySmall)
                    }
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    val pick = { picker.launch(arrayOf("*/*")) }
                    if (hasAssets) {
                        OutlinedButton(onClick = pick, enabled = !importing) { Text(stringResource(R.string.reimport_rom)) }
                    } else {
                        Button(onClick = pick, enabled = !importing) { Text(stringResource(R.string.select_rom)) }
                    }
                }
            }

            Button(
                onClick = onPlay,
                enabled = hasAssets && !importing,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(stringResource(R.string.play), style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(
                onClick = { context.startActivity(Intent(context, SavesActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.saves_title)) }

            Section(R.string.section_display) {
                SwitchRow(stringResource(R.string.fill_screen), fillScreen) {
                    fillScreen = it
                    prefs.fillScreen = it
                    if (it) editIni { useScreenAspectRatio(context) }
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val current = AspectRatio.fromIni(ini["General", "ExtendedAspectRatio"])
                    val lines = current.croppedLinesPerEdge(screenRatio)
                    val columns = current.croppedColumnsPerEdge(screenRatio)
                    Text(
                        when {
                            !fillScreen -> stringResource(R.string.fill_screen_off)
                            lines > 0 -> stringResource(R.string.fill_screen_crops_lines, lines)
                            columns > 0 -> stringResource(R.string.fill_screen_crops_columns, columns)
                            else -> stringResource(R.string.fill_screen_exact)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.aspect_ratio), style = MaterialTheme.typography.bodyMedium)
                    // With fill on, the widest mode that fits is the only sensible one: anything
                    // narrower is scaled up even more and loses more of the top and bottom.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        AspectRatio.entries.forEachIndexed { i, ratio ->
                            SegmentedButton(
                                selected = ratio == current,
                                enabled = !fillScreen,
                                onClick = {
                                    editIni { this["General", "ExtendedAspectRatio"] = AspectRatio.toIni(this["General", "ExtendedAspectRatio"], ratio) }
                                },
                                shape = SegmentedButtonDefaults.itemShape(i, AspectRatio.entries.size),
                            ) { Text(ratio.iniValue) }
                        }
                    }
                    Text(
                        stringResource(if (fillScreen) R.string.aspect_ratio_auto else R.string.aspect_ratio_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                IniSwitch(ini, "Graphics", "EnhancedMode7", R.string.enhanced_mode7, ::editIni)
                IniSwitch(ini, "Graphics", "LinearFiltering", R.string.linear_filtering, ::editIni)
                IniSwitch(ini, "Graphics", "DimFlashes", R.string.dim_flashes, ::editIni, R.string.dim_flashes_desc)
            }

            Section(R.string.section_controls) {
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
                SwitchRow(stringResource(R.string.menu_button), menuButton) {
                    menuButton = it
                    prefs.menuButton = it
                }
                SwitchRow(stringResource(R.string.double_tap_menu), doubleTapMenu) {
                    doubleTapMenu = it
                    prefs.doubleTapMenu = it
                }
                Text(
                    stringResource(R.string.menu_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                Text(
                    stringResource(R.string.controller_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }

            Section(R.string.section_game) {
                IniSwitch(ini, "General", "Autosave", R.string.autosave, ::editIni)
                IniSwitch(ini, "Graphics", "NoSpriteLimits", R.string.no_sprite_limits, ::editIni, R.string.no_sprite_limits_desc)
            }

            Section(R.string.section_enhancements) {
                Text(
                    stringResource(R.string.enhancements_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
                for (f in features) IniSwitch(ini, "Features", f.key, f.label, ::editIni, f.description)
            }

            Text(
                stringResource(R.string.legal_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun IniSwitch(
    ini: Ini,
    section: String,
    key: String,
    @StringRes label: Int,
    edit: (Ini.() -> Unit) -> Unit,
    @StringRes description: Int? = null,
) {
    SwitchRow(stringResource(label), ini.getBool(section, key), description?.let { stringResource(it) }) { checked ->
        edit { setBool(section, key, checked) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = description?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    )
}
