package io.github.doutorraposo.moonpearl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picks Link's look: the stock graphics or an imported ZSPR file. */
class LinkSpritesActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LinkSpritesScreen(GameData(this), LinkSprites(filesDir))
                }
            }
        }
    }

    companion object {
        const val GALLERY_URL = "https://snesrev.github.io/sprites-gfx/snes/zelda3/link/"
    }
}

@Composable
private fun LinkSpritesScreen(data: GameData, sprites: LinkSprites) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultName = stringResource(R.string.sprites_default)
    var all by remember { mutableStateOf<List<LinkSprites.Sprite>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<LinkSprites.Sprite?>(null) }

    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            all = listOfNotNull(sprites.default(defaultName)) + sprites.list()
            selected = sprites.selected(data.readIni())?.let { "${LinkSprites.DIR}/${it.name}" }
        }
    }

    fun select(sprite: LinkSprites.Sprite) {
        val ini = data.readIni()
        val value = sprite.iniValue
        if (value == null) ini.remove("Graphics", "LinkGraphics") else ini["Graphics", "LinkGraphics"] = value
        data.writeIni(ini)
        selected = value
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { sprites.import(it, displayName(context, uri)) }
                }
            }
            when (val r = result.getOrNull()) {
                is LinkSprites.ImportResult.Imported -> {
                    select(r.sprite)
                    message = null
                }
                LinkSprites.ImportResult.NotRecognized -> message = context.getString(R.string.sprites_not_recognized)
                null -> message = context.getString(R.string.saves_failed, result.exceptionOrNull()?.message.orEmpty())
            }
            refresh++
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sprites_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.sprites_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.sprites_import)) }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LinkSpritesActivity.GALLERY_URL)))
                    }) { Text(stringResource(R.string.sprites_gallery)) }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        items(all, key = { it.iniValue ?: "default" }) { sprite ->
            SpriteCard(
                sprite = sprite,
                isSelected = sprite.iniValue == selected,
                onSelect = { select(sprite) },
                onDelete = { deleting = sprite }.takeIf { !sprite.isDefault },
            )
        }
    }

    deleting?.let { sprite ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.sprites_delete_confirm, sprite.name)) },
            confirmButton = {
                TextButton(onClick = {
                    if (sprite.iniValue == selected) all.firstOrNull { it.isDefault }?.let(::select)
                    sprites.delete(sprite)
                    deleting = null
                    refresh++
                }) { Text(stringResource(R.string.saves_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun SpriteCard(sprite: LinkSprites.Sprite, isSelected: Boolean, onSelect: () -> Unit, onDelete: (() -> Unit)?) {
    val bitmap = remember(sprite) { LinkSprites.preview(sprite).asImageBitmap() }
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                bitmap = bitmap,
                contentDescription = sprite.name,
                filterQuality = FilterQuality.None,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 64.dp, height = 96.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(sprite.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sprite.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.saves_delete)) }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
