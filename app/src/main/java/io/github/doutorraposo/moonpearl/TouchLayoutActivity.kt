package io.github.doutorraposo.moonpearl

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.doutorraposo.moonpearl.TouchLayout.Element
import kotlin.math.roundToInt

/**
 * Lets the player move and resize the on-screen controls over a picture of their game. Shown in
 * landscape, full screen and into the display cutout, exactly like the game, so what they see
 * here is where the controls end up.
 */
class TouchLayoutActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val prefs = AppPrefs(this)
        val thumbnail = SaveStates(GameData(this).dir).thumbnailFile(0).takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.path) }
        setContent {
            AppTheme {
                LayoutEditor(
                    prefs = prefs,
                    background = thumbnail?.asImageBitmap(),
                    onDone = ::finish,
                )
            }
        }
    }
}

@Composable
private fun LayoutEditor(prefs: AppPrefs, background: androidx.compose.ui.graphics.ImageBitmap?, onDone: () -> Unit) {
    var layout by remember { mutableStateOf(prefs.touchLayout) }
    var opacity by remember { mutableFloatStateOf(prefs.touchOpacity) }
    var selected by remember { mutableStateOf<Element?>(null) }
    var panelVisible by remember { mutableStateOf(true) }
    /** The panel steps aside while a control is held, so it never covers what is being moved. */
    var holding by remember { mutableStateOf(false) }
    val elements = remember { TouchControlsView.elementsFor(prefs).ifEmpty { Element.entries.toSet() } }

    // Undo: the layout before each drag, size change or reset.
    val history = remember { mutableStateListOf<TouchLayout>() }
    var before by remember { mutableStateOf<TouchLayout?>(null) }
    fun begin() {
        if (before == null) before = layout
    }
    fun finish() {
        before?.let { if (it != layout) history += it }
        before = null
    }

    // The panel can be dragged by its top, e.g. off a control that ended up under it.
    var panelOffset by remember { mutableStateOf(Offset.Zero) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val maxX = ((constraints.maxWidth - panelSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((constraints.maxHeight - panelSize.height) / 2f).coerceAtLeast(0f)
        if (background != null) {
            Image(
                background,
                contentDescription = null,
                contentScale = if (prefs.fillScreen) ContentScale.Crop else ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }
        AndroidView(
            factory = { context ->
                TouchControlsView(context).apply {
                    editMode = true
                    this.elements = elements
                    onSelect = { e ->
                        if (e == null) {
                            panelVisible = !panelVisible
                        } else {
                            selected = e
                            holding = true
                            panelVisible = true
                            begin()
                        }
                    }
                    onLayoutChange = {
                        layout = it
                        holding = false
                        finish()
                    }
                }
            },
            update = { view ->
                if (view.layout != layout) view.layout = layout
                view.opacity = opacity
                view.selected = selected
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (panelVisible && !holding) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(panelOffset.x.roundToInt(), panelOffset.y.roundToInt()) }
                    .onSizeChanged { panelSize = it }
                    .width(380.dp),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    // The handle and title row moves the panel.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(maxX, maxY) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    panelOffset = Offset(
                                        (panelOffset.x + drag.x).coerceIn(-maxX, maxX),
                                        (panelOffset.y + drag.y).coerceIn(-maxY, maxY),
                                    )
                                }
                            }
                            .padding(top = 8.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
                        )
                        Text(
                            stringResource(R.string.layout_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    val current = selected
                    if (current != null) {
                        val scale = layout[current].scale
                        Text(
                            stringResource(R.string.layout_size, elementName(current), (scale * 100).roundToInt()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = scale,
                            onValueChange = {
                                begin()
                                layout = layout.scaled(current, it)
                            },
                            onValueChangeFinished = { finish() },
                            valueRange = TouchLayout.MIN_SCALE..TouchLayout.MAX_SCALE,
                        )
                    } else {
                        Text(stringResource(R.string.layout_hint), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.layout_opacity), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0.15f..1f)
                    Text(
                        stringResource(R.string.layout_panel_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            if (layout != TouchLayout.DEFAULT) history += layout
                            layout = TouchLayout.DEFAULT
                            selected = null
                        }) { Text(stringResource(R.string.layout_reset)) }
                        TextButton(
                            enabled = history.isNotEmpty(),
                            onClick = { layout = history.removeAt(history.lastIndex) },
                        ) { Text(stringResource(R.string.layout_undo)) }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onDone) { Text(stringResource(R.string.layout_cancel)) }
                        Button(onClick = {
                            prefs.touchLayout = layout
                            prefs.touchOpacity = opacity
                            onDone()
                        }) { Text(stringResource(R.string.layout_save)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun elementName(e: Element) = when (e) {
    Element.DPAD -> stringResource(R.string.layout_dpad)
    Element.FACE -> "A B X Y"
    Element.L -> "L"
    Element.R -> "R"
    Element.SELECT -> "SELECT"
    Element.START -> "START"
    Element.MENU -> stringResource(R.string.layout_menu)
    Element.TURBO -> stringResource(R.string.layout_turbo)
}
