package io.github.doutorraposo.moonpearl

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks the player's ROM and builds the game data from it (GameData.import). Shown on the welcome
 * screen and, to import again, in Settings > App.
 */
@Composable
fun RomImport(data: GameData, installed: Boolean, onInstalled: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { data.import(uri) } }
            importing = false
            error = when (val r = result.getOrNull()) {
                GameData.ImportResult.Installed -> null
                is GameData.ImportResult.WrongRom -> context.getString(R.string.import_wrong_rom, "%08X".format(r.crc))
                GameData.ImportResult.NotRecognized -> context.getString(R.string.import_not_recognized)
                null -> context.getString(R.string.import_failed, result.exceptionOrNull()?.message.orEmpty())
            }
            if (data.hasAssets() && error == null) onInstalled()
        }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(if (installed) R.string.assets_ready else R.string.assets_missing),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (importing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.importing), style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val pick = { picker.launch(arrayOf("*/*")) }
        if (installed) {
            OutlinedButton(onClick = pick, enabled = !importing) { Text(stringResource(R.string.reimport_rom)) }
        } else {
            Button(onClick = pick, enabled = !importing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.select_rom)) }
        }
    }
}
