package io.github.doutorraposo.moonpearl

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Name of the current image filter, for the row that leads to [ShaderSettings]. */
@Composable
fun shaderChoiceLabel(choice: Shaders.Choice): String = when (choice) {
    Shaders.Choice.Sharp -> stringResource(R.string.filter_sharp)
    Shaders.Choice.Smooth -> stringResource(R.string.filter_smooth)
    is Shaders.Choice.Shader -> Shaders.builtin(choice.path)?.let { stringResource(it.label) } ?: Shaders.displayName(choice.path)
}

@Composable
fun ShaderSettings(ini: Ini, gameDir: File, edit: (Ini.() -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = Shaders.current(ini)
    fun choose(choice: Shaders.Choice) = edit { Shaders.apply(this, choice) }

    var imported by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { imported = withContext(Dispatchers.IO) { Shaders.imported(gameDir) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Shaders.import(context, uri, gameDir) }.getOrNull() }
            imported = withContext(Dispatchers.IO) { Shaders.imported(gameDir) }
            // The selected preset may have been replaced by the new import.
            edit { Shaders.validate(this, gameDir) }
            message = when (result) {
                is Shaders.ImportResult.Imported -> context.getString(R.string.shaders_imported, result.presets)
                Shaders.ImportResult.NothingFound -> context.getString(R.string.shaders_none_found)
                Shaders.ImportResult.TooBig -> context.getString(R.string.shaders_too_big)
                null -> context.getString(R.string.shaders_import_failed)
            }
            busy = false
        }
    }

    Hint(stringResource(R.string.filter_gpu_hint), Modifier.padding(horizontal = 4.dp))
    Section(R.string.filter_plain) {
        ChoiceRow(stringResource(R.string.filter_sharp), stringResource(R.string.filter_sharp_desc), current == Shaders.Choice.Sharp) {
            choose(Shaders.Choice.Sharp)
        }
        ChoiceRow(stringResource(R.string.filter_smooth), stringResource(R.string.filter_smooth_desc), current == Shaders.Choice.Smooth) {
            choose(Shaders.Choice.Smooth)
        }
    }
    Section(R.string.filter_shaders) {
        for (b in Shaders.Builtin.entries) {
            ChoiceRow(stringResource(b.label), stringResource(b.description), current == Shaders.Choice.Shader(b.path)) {
                choose(Shaders.Choice.Shader(b.path))
            }
        }
    }
    Section(R.string.filter_imported) {
        Hint(stringResource(R.string.shaders_import_hint), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        message?.let { Hint(it, Modifier.padding(horizontal = 16.dp)) }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { picker.launch(null) }) {
                Text(stringResource(if (busy) R.string.shaders_importing else R.string.shaders_import))
            }
            if (imported.isNotEmpty() && !busy) {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { Shaders.removeImported(gameDir) }
                        imported = emptyList()
                        message = null
                        edit { Shaders.validate(this, gameDir) }
                    }
                }) { Text(stringResource(R.string.shaders_remove)) }
            }
        }
        for (path in imported) {
            ChoiceRow(Shaders.displayName(path), null, current == Shaders.Choice.Shader(path)) {
                choose(Shaders.Choice.Shader(path))
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, description: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = description?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}
