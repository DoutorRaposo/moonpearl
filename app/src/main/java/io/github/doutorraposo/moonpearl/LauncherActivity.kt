package io.github.doutorraposo.moonpearl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LauncherActivity : ComponentActivity() {
    /** Set when the game process ended with a fatal error; shown once. */
    private val gameError = mutableStateOf<String?>(null)
    /** A shader that hung or crashed the last session and was switched off; shown once. */
    private val failedShader = mutableStateOf<Shaders.Failure?>(null)
    /** Bumped on every return to the front, so the home screen picks up the latest autosave. */
    private val resumes = mutableIntStateOf(0)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onResume() {
        super.onResume()
        val data = GameData(this)
        val error = data.takeLastError()?.also { gameError.value = it }
        data.takeShaderFailure(hadError = error != null)?.let { failedShader.value = it }
        resumes.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launcher is always dark, so ask for light system bar icons regardless of the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val data = GameData(this)
        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    LauncherScreen(data, resumes.intValue, onPlay = { chapter ->
                        startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_CHAPTER, chapter))
                    })
                    failedShader.value?.takeIf { gameError.value == null }?.let { failure ->
                        AlertDialog(
                            onDismissRequest = { failedShader.value = null },
                            title = { Text(stringResource(R.string.shader_failed_title)) },
                            text = {
                                Text(
                                    when (failure) {
                                        is Shaders.Failure.Shader -> stringResource(
                                            R.string.shader_failed_message,
                                            shaderChoiceLabel(Shaders.Choice.Shader(failure.path)),
                                        )
                                        Shaders.Failure.OpenGl -> stringResource(R.string.opengl_failed_message)
                                    },
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { failedShader.value = null }) { Text(stringResource(android.R.string.ok)) }
                            },
                        )
                    }
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

/** The welcome screen until the game data is installed; then the home screen. */
@Composable
private fun LauncherScreen(data: GameData, resumes: Int, onPlay: (chapter: Int) -> Unit) {
    var hasAssets by remember { mutableStateOf(data.hasAssets()) }
    // Settings > App can import again (or the data can go away), so check on every return.
    LaunchedEffect(resumes) { hasAssets = data.hasAssets() }
    if (!hasAssets) {
        Page(stringResource(R.string.welcome_title), subtitle = stringResource(R.string.tagline)) {
            Section(R.string.section_game_data) {
                RomImport(data, installed = false, onInstalled = { hasAssets = true })
            }
        }
    } else {
        HomeScreen(data, resumes, onPlay)
    }
}

/**
 * Most launches are only to play, so that is the screen: the point the game resumes from, as
 * large as it gets, with the less frequent places around it.
 */
@Composable
private fun HomeScreen(data: GameData, resumes: Int, onPlay: (chapter: Int) -> Unit) {
    val context = LocalContext.current
    var resume by remember { mutableStateOf<SaveStates.Slot?>(null) }
    var autosave by remember { mutableStateOf(true) }
    var chapters by remember { mutableStateOf<List<SaveStates.Chapter>>(emptyList()) }
    var choosingChapter by remember { mutableStateOf(false) }
    var confirmChapter by remember { mutableStateOf<SaveStates.Chapter?>(null) }
    LaunchedEffect(resumes) {
        withContext(Dispatchers.IO) {
            val states = SaveStates(data.dir)
            autosave = data.readIni().getBool("General", "Autosave")
            resume = states.slots().first().takeIf { it.exists }
            chapters = states.chapters()
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) {
                    Icon(Gear, stringResource(R.string.settings_title))
                }
            }
            val continueCard = @Composable { modifier: Modifier ->
                ContinueCard(resume?.takeIf { autosave }, modifier) { onPlay(0) }
            }
            val places = @Composable { modifier: Modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Place(
                        stringResource(R.string.saves_title),
                        stringResource(R.string.launcher_saves_summary),
                        Modifier.fillMaxWidth(),
                    ) { context.startActivity(Intent(context, SavesActivity::class.java)) }
                    Place(
                        stringResource(R.string.launcher_chapters),
                        stringResource(R.string.launcher_chapters_summary),
                        Modifier.fillMaxWidth(),
                    ) { choosingChapter = true }
                }
            }
            if (landscape) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    continueCard(Modifier.weight(1.7f).fillMaxHeight())
                    places(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
            } else {
                // The picture's own shape (the game's aspect ratio), so none of it is cut off.
                val shape = resume?.takeIf { autosave }?.thumbnail?.let { it.width.toFloat() / it.height } ?: (4f / 3f)
                continueCard(Modifier.fillMaxWidth().widthIn(max = 640.dp).aspectRatio(shape))
                places(Modifier.fillMaxWidth())
            }
        }
    }

    if (choosingChapter) {
        AlertDialog(
            onDismissRequest = { choosingChapter = false },
            title = { Text(stringResource(R.string.launcher_chapters)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (chapter in chapters) {
                        TextButton(onClick = { choosingChapter = false; confirmChapter = chapter }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.menu_chapter, chapter.index, chapter.title), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingChapter = false }) { Text(stringResource(R.string.layout_cancel)) } },
        )
    }
    confirmChapter?.let { chapter ->
        AlertDialog(
            onDismissRequest = { confirmChapter = null },
            title = { Text(stringResource(R.string.menu_chapter, chapter.index, chapter.title)) },
            text = { Text(stringResource(R.string.launcher_chapter_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmChapter = null; onPlay(chapter.index) }) { Text(stringResource(R.string.play)) }
            },
            dismissButton = { TextButton(onClick = { confirmChapter = null }) { Text(stringResource(R.string.layout_cancel)) } },
        )
    }
}

/** The autosave's picture and age, or a plain start when there is none (or Autosave is off). */
@Composable
private fun ContinueCard(resume: SaveStates.Slot?, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(Modifier.fillMaxSize()) {
            val thumbnail = resume?.thumbnail
            if (thumbnail != null) {
                val bitmap = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(160.dp),
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD90B0F0C))))
                    .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 16.dp),
            ) {
                Text(
                    "▶  " + stringResource(if (resume != null) R.string.launcher_continue else R.string.play),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                resume?.modified?.let { time ->
                    Text(
                        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
private fun Place(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
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
            Spacer(Modifier.size(8.dp))
            Icon(ChevronRight, null)
        }
    }
}
