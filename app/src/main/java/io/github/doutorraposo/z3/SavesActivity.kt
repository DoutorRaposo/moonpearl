package io.github.doutorraposo.z3

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists the game files and save states, and moves them in and out of the device. */
class SavesActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    SavesScreen(SaveManager(filesDir), SaveStates(filesDir))
                }
            }
        }
    }
}

/** Something to confirm before it overwrites or deletes data. */
private sealed interface Pending {
    data class Restore(val uri: Uri) : Pending
    data class ImportSrm(val uri: Uri) : Pending
    data class DeleteState(val slot: Int) : Pending
    data class EraseFile(val slot: Int) : Pending
    data object DeleteAllFiles : Pending
}

@Composable
private fun SavesScreen(manager: SaveManager, states: SaveStates) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<Sram.SaveFile?>>(emptyList()) }
    var slots by remember { mutableStateOf<List<SaveStates.Slot>>(emptyList()) }
    var hasSram by remember { mutableStateOf(false) }
    var hasData by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }

    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            val sram = manager.readSram()
            hasSram = sram != null
            files = sram?.let(Sram::files) ?: List(3) { null }
            slots = states.slots().filter { it.exists }
            hasData = manager.hasData()
        }
    }

    /** Runs a file operation off the main thread and reports the outcome. */
    fun run(@StringRes success: Int, op: () -> SaveManager.Result) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(op) }
            message = when (result.getOrNull()) {
                SaveManager.Result.Done -> context.getString(success)
                SaveManager.Result.NotRecognized -> context.getString(R.string.saves_not_recognized)
                null -> context.getString(R.string.saves_failed, result.exceptionOrNull()?.message.orEmpty())
            }
            refresh++
        }
    }

    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) run(R.string.saves_exported) {
            context.contentResolver.openOutputStream(uri)!!.use(manager::exportZip)
            SaveManager.Result.Done
        }
    }
    val exportSrm = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) run(R.string.saves_exported) {
            context.contentResolver.openOutputStream(uri)!!.use(manager::exportSrm)
            SaveManager.Result.Done
        }
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = Pending.Restore(uri)
    }
    val pickSrm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = Pending.ImportSrm(uri)
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
            Text(stringResource(R.string.saves_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium) }

            SavesSection(R.string.saves_game_files) {
                files.forEachIndexed { i, file ->
                    ListItem(
                        headlineContent = {
                            Text(
                                when {
                                    file == null -> stringResource(R.string.saves_file_empty, i + 1)
                                    file.name != null -> stringResource(R.string.saves_file_named, i + 1, file.name)
                                    else -> stringResource(R.string.saves_file, i + 1)
                                },
                            )
                        },
                        supportingContent = file?.let { { Text(stringResource(R.string.saves_hearts, it.hearts)) } },
                        trailingContent = file?.let {
                            { TextButton(onClick = { pending = Pending.EraseFile(i) }) { Text(stringResource(R.string.saves_delete)) } }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
                Text(
                    stringResource(R.string.saves_srm_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickSrm.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.saves_import_srm)) }
                    OutlinedButton(onClick = { exportSrm.launch("zelda3.srm") }, enabled = hasSram) {
                        Text(stringResource(R.string.saves_export_srm))
                    }
                }
                TextButton(
                    onClick = { pending = Pending.DeleteAllFiles },
                    enabled = hasSram,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                ) { Text(stringResource(R.string.saves_delete_all_files), color = MaterialTheme.colorScheme.error) }
            }

            SavesSection(R.string.menu_tab_states) {
                if (slots.isEmpty()) {
                    Text(
                        stringResource(R.string.saves_no_states),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                for (slot in slots) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.width(112.dp).aspectRatio(20f / 9f).background(Color.Black)) {
                            slot.thumbnail?.let {
                                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (slot.isAuto) stringResource(R.string.menu_slot_auto) else stringResource(R.string.menu_slot, slot.index),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                slot.modified?.let { DateUtils.getRelativeTimeSpanString(it).toString() }.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { pending = Pending.DeleteState(slot.index) }) { Text(stringResource(R.string.saves_delete)) }
                    }
                }
            }

            SavesSection(R.string.saves_backup) {
                Text(
                    stringResource(R.string.saves_backup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportZip.launch("zelda3-saves-${LocalDate.now()}.zip") }, enabled = hasData) {
                        Text(stringResource(R.string.saves_export_backup))
                    }
                    OutlinedButton(onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                        Text(stringResource(R.string.saves_restore_backup))
                    }
                }
            }
        }
    }

    pending?.let { p ->
        val text = when (p) {
            is Pending.Restore -> stringResource(R.string.saves_restore_confirm)
            is Pending.ImportSrm -> stringResource(R.string.saves_import_srm_confirm)
            is Pending.DeleteState -> stringResource(R.string.saves_delete_confirm)
            is Pending.EraseFile -> stringResource(R.string.saves_erase_file_confirm, p.slot + 1)
            Pending.DeleteAllFiles -> stringResource(R.string.saves_delete_all_files_confirm)
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    when (p) {
                        is Pending.Restore -> run(R.string.saves_restored) {
                            context.contentResolver.openInputStream(p.uri)!!.use(manager::restoreZip)
                        }
                        is Pending.ImportSrm -> run(R.string.saves_imported) {
                            context.contentResolver.openInputStream(p.uri)!!.use(manager::importSrm)
                        }
                        is Pending.DeleteState -> run(R.string.saves_deleted) {
                            manager.deleteState(p.slot)
                            SaveManager.Result.Done
                        }
                        is Pending.EraseFile -> run(R.string.saves_file_erased) {
                            manager.eraseFile(p.slot)
                            SaveManager.Result.Done
                        }
                        Pending.DeleteAllFiles -> run(R.string.saves_files_deleted) {
                            manager.deleteGameFiles()
                            SaveManager.Result.Done
                        }
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun SavesSection(@StringRes title: Int, content: @Composable () -> Unit) {
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
