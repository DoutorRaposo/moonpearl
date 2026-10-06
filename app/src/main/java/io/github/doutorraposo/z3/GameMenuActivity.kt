package io.github.doutorraposo.z3

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * In-game menu, shown as a translucent activity over [GameActivity]. While it is open SDL
 * pauses the game; the chosen action goes back as the activity result and GameActivity
 * turns it into upstream key presses, which run as soon as the game resumes.
 */
class GameMenuActivity : ComponentActivity() {
    enum class Action { SAVE, LOAD, CHAPTER, RESET, QUIT }

    private val result = Intent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val states = SaveStates(filesDir)
        var turbo = intent.getBooleanExtra(EXTRA_TURBO, false)
        var touch = intent.getBooleanExtra(EXTRA_TOUCH_VISIBLE, true)
        val hasTouch = intent.getBooleanExtra(EXTRA_HAS_TOUCH, false)
        publish(turbo, touch)

        setContent {
            AppTheme {
                GameMenu(
                    states = states,
                    turbo = turbo,
                    onTurbo = { turbo = it; publish(turbo, touch) },
                    touchVisible = touch.takeIf { hasTouch },
                    onTouchVisible = { touch = it; publish(turbo, touch) },
                    onResume = ::finish,
                    onAction = { action, arg ->
                        result.putExtra(EXTRA_ACTION, action.name).putExtra(EXTRA_ARG, arg)
                        setResult(RESULT_OK, result)
                        finish()
                    },
                )
            }
        }
    }

    private fun publish(turbo: Boolean, touch: Boolean) {
        result.putExtra(EXTRA_TURBO, turbo).putExtra(EXTRA_TOUCH_VISIBLE, touch)
        setResult(RESULT_OK, result)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Controllers: A activates the focused item, B/Start go back to the game.
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> return super.dispatchKeyEvent(
                KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
                    event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source),
            )
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START -> {
                if (event.action == KeyEvent.ACTION_UP) finish()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_TURBO = "turbo"
        const val EXTRA_TOUCH_VISIBLE = "touch_visible"
        const val EXTRA_HAS_TOUCH = "has_touch"
        const val EXTRA_ACTION = "action"
        const val EXTRA_ARG = "arg"
    }
}

private enum class MenuTab(@StringRes val label: Int) { STATES(R.string.menu_tab_states), CHAPTERS(R.string.menu_tab_chapters) }

@Composable
private fun GameMenu(
    states: SaveStates,
    turbo: Boolean,
    onTurbo: (Boolean) -> Unit,
    touchVisible: Boolean?,
    onTouchVisible: (Boolean) -> Unit,
    onResume: () -> Unit,
    onAction: (GameMenuActivity.Action, Int) -> Unit,
) {
    var slots by remember { mutableStateOf<List<SaveStates.Slot>?>(null) }
    var chapters by remember { mutableStateOf<List<SaveStates.Chapter>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            chapters = states.chapters()
            slots = states.slots()
        }
    }
    var tab by remember { mutableStateOf(MenuTab.STATES) }
    var turboOn by remember { mutableStateOf(turbo) }
    var touchOn by remember { mutableStateOf(touchVisible) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmChapter by remember { mutableIntStateOf(0) }
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { resumeFocus.requestFocus() }

    Surface(color = Color(0xE60B0F0C), contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                Modifier.width(240.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.menu_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Button(onClick = onResume, modifier = Modifier.focusRing().fillMaxWidth().focusRequester(resumeFocus)) {
                    Text(stringResource(R.string.menu_resume))
                }
                MenuSwitch(stringResource(R.string.menu_fast_forward), turboOn) { turboOn = it; onTurbo(it) }
                touchOn?.let { on ->
                    MenuSwitch(stringResource(R.string.touch_controls), on) { touchOn = it; onTouchVisible(it) }
                }
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.focusRing().fillMaxWidth()) {
                    Text(stringResource(R.string.menu_reset))
                }
                OutlinedButton(onClick = { onAction(GameMenuActivity.Action.QUIT, 0) }, modifier = Modifier.focusRing().fillMaxWidth()) {
                    Text(stringResource(R.string.menu_quit))
                }
                Text(
                    stringResource(R.string.menu_quit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.width(360.dp)) {
                    MenuTab.entries.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = tab == t,
                            onClick = { tab = t },
                            shape = SegmentedButtonDefaults.itemShape(i, MenuTab.entries.size),
                            modifier = Modifier.focusRing(),
                        ) { Text(stringResource(t.label)) }
                    }
                }
                when (tab) {
                    MenuTab.STATES -> slots?.let { list ->
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(170.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            items(list, key = { it.index }) { slot ->
                                SlotCard(
                                    slot,
                                    onSave = { onAction(GameMenuActivity.Action.SAVE, slot.index) },
                                    onLoad = { onAction(GameMenuActivity.Action.LOAD, slot.index) },
                                )
                            }
                        }
                    }
                    MenuTab.CHAPTERS -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            stringResource(R.string.menu_chapters_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        for (chapter in chapters) {
                            TextButton(onClick = { confirmChapter = chapter.index }, modifier = Modifier.focusRing().fillMaxWidth()) {
                                Text(
                                    stringResource(R.string.menu_chapter, chapter.index, chapter.title),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }

    }

    if (confirmReset) {
        ConfirmDialog(
            text = stringResource(R.string.menu_reset_confirm),
            onConfirm = { onAction(GameMenuActivity.Action.RESET, 0) },
            onDismiss = { confirmReset = false },
        )
    }
    if (confirmChapter != 0) {
        ConfirmDialog(
            text = stringResource(R.string.menu_chapter_confirm),
            onConfirm = { onAction(GameMenuActivity.Action.CHAPTER, confirmChapter) },
            onDismiss = { confirmChapter = 0 },
        )
    }
}

@Composable
private fun SlotCard(slot: SaveStates.Slot, onSave: () -> Unit, onLoad: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(20f / 9f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (slot.thumbnail != null) {
                Image(slot.thumbnail.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    stringResource(if (slot.exists) R.string.menu_slot_no_preview else R.string.menu_slot_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                if (slot.isAuto) stringResource(R.string.menu_slot_auto) else stringResource(R.string.menu_slot, slot.index),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                slot.modified?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!slot.isAuto) {
                    FilledTonalButton(onClick = onSave, modifier = Modifier.focusRing(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text(stringResource(R.string.menu_save))
                    }
                }
                OutlinedButton(
                    onClick = onLoad,
                    enabled = slot.exists,
                    modifier = Modifier.focusRing(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(stringResource(R.string.menu_load))
                }
            }
        }
    }
}

/** The whole row toggles, so a controller can focus and flip it in one go. */
@Composable
private fun MenuSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .focusRing(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

/**
 * Outlines the focused element while navigating with a controller or keyboard. Material's own
 * focus state layer is too faint to follow on a TV-style menu. Must come before the clickable.
 */
@Composable
private fun Modifier.focusRing(shape: Shape = RoundedCornerShape(50)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val ring = if (focused && keyboard) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier
    return onFocusChanged { focused = it.hasFocus }.then(ring)
}

@Composable
private fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
