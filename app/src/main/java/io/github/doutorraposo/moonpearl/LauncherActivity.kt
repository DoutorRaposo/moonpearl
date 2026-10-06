package io.github.doutorraposo.moonpearl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LauncherActivity : ComponentActivity() {
    /** Set when the game process ended with a fatal error; shown once. */
    private val gameError = mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onResume() {
        super.onResume()
        GameData(this).takeLastError()?.let { gameError.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launcher is always dark, so ask for light system bar icons regardless of the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val data = GameData(this)
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LauncherScreen(data, onPlay = { startActivity(Intent(this, GameActivity::class.java)) })
                    gameError.value?.let { message ->
                        AlertDialog(
                            onDismissRequest = { gameError.value = null },
                            title = { Text(stringResource(R.string.game_error_title)) },
                            text = { Text(stringResource(R.string.game_error_message, message)) },
                            confirmButton = {
                                TextButton(onClick = { gameError.value = null }) { Text(stringResource(android.R.string.ok)) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LauncherScreen(data: GameData, onPlay: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasAssets by remember { mutableStateOf(data.hasAssets()) }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

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

    Page(stringResource(R.string.app_name), subtitle = stringResource(R.string.tagline)) {
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

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(
                title = stringResource(R.string.saves_title),
                subtitle = stringResource(R.string.launcher_saves_summary),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = { context.startActivity(Intent(context, SavesActivity::class.java)) },
            )
            LinkTile(
                data,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = { context.startActivity(Intent(context, LinkSpritesActivity::class.java)) },
            )
        }

        OutlinedButton(
            onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.settings_title)) }
    }
}

@Composable
private fun Tile(
    title: String,
    subtitle: String,
    modifier: Modifier,
    onClick: () -> Unit,
    image: @Composable (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            image?.let { Box(Modifier.padding(start = 8.dp)) { it() } }
        }
    }
}

/** The current Link look, refreshed whenever the launcher comes back to the front. */
@Composable
private fun LinkTile(data: GameData, modifier: Modifier, onClick: () -> Unit) {
    val defaultName = stringResource(R.string.sprites_default)
    var sprite by remember { mutableStateOf<LinkSprites.Sprite?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            sprite = withContext(Dispatchers.IO) {
                val sprites = LinkSprites(data.dir)
                sprites.selected(data.readIni())?.let { f -> LinkSprites.parse(f.readBytes())?.copy(file = f) }
                    ?: sprites.default(defaultName)
            }
        }
    }
    Tile(
        title = stringResource(R.string.section_link),
        subtitle = sprite?.name.orEmpty(),
        modifier = modifier,
        onClick = onClick,
        image = sprite?.let { s ->
            {
                val bitmap = remember(s) { LinkSprites.preview(s).asImageBitmap() }
                Image(bitmap, s.name, filterQuality = FilterQuality.None, modifier = Modifier.size(width = 32.dp, height = 48.dp))
            }
        },
    )
}
