package io.github.doutorraposo.moonpearl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.doutorraposo.moonpearl.ControllerMap.Snes

/**
 * Remaps the SNES buttons on a physical controller: pick a SNES button, then press the controller
 * button for it. [SettingsActivity] hands controller presses to [SettingsActivity.padCapture].
 * The wait shows in the row itself: a dialog would be a window of its own, and the controller's
 * keys would go to it instead of the activity.
 */
@Composable
fun ControllerRemap(ini: Ini, edit: (Ini.() -> Unit) -> Unit) {
    val activity = LocalContext.current as SettingsActivity
    val map = ControllerMap.fromIni(ini["GamepadMap", "Controls"])
    fun save(updated: ControllerMap) = edit { this["GamepadMap", "Controls"] = updated.iniValue() }

    var waiting by remember { mutableStateOf<Snes?>(null) }
    DisposableEffect(waiting) {
        val snes = waiting
        activity.padCapture = snes?.let {
            { pad: ControllerMap.Pad ->
                save(map.assigned(it, pad))
                waiting = null
            }
        }
        onDispose { activity.padCapture = null }
    }

    Hint(stringResource(R.string.remap_hint), Modifier.padding(horizontal = 4.dp))
    Section(null) {
        for (snes in Snes.entries) {
            val isWaiting = waiting == snes
            ListItem(
                headlineContent = { Text(stringResource(R.string.remap_snes, snes.label), fontWeight = FontWeight.Bold) },
                supportingContent = if (isWaiting) {
                    { Text(stringResource(R.string.remap_press_hint)) }
                } else {
                    null
                },
                trailingContent = {
                    if (isWaiting) Text(stringResource(R.string.remap_press), color = MaterialTheme.colorScheme.primary)
                    else Text(stringResource(map[snes].label))
                },
                colors = ListItemDefaults.colors(
                    containerColor = if (isWaiting) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                ),
                // Tapping the waiting row again cancels.
                modifier = Modifier.fillMaxWidth().clickable { waiting = if (isWaiting) null else snes },
            )
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { save(ControllerMap.BY_POSITION) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.remap_by_position))
        }
        OutlinedButton(onClick = { save(ControllerMap.BY_LETTER) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.remap_by_letter))
        }
    }
}
