package io.github.doutorraposo.moonpearl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.animation.animateColorAsState
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
import org.libsdl.app.SDLControllerManager

/**
 * In-game menu, shown as a translucent activity over [GameActivity], which pauses the game while
 * it is open. The chosen action goes back as the activity result and GameActivity turns it into
 * upstream key presses.
 */
class GameMenuActivity : ComponentActivity() {
    enum class Action { SAVE, LOAD, CHAPTER, RESET, QUIT }

    private val result = Intent()
    /** Buttons pressed while the menu was open; releases of anything else are ignored. */
    private val buttonsDown = HashSet<Int>()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val states = SaveStates(filesDir)
        val prefs = AppPrefs(this)
        var speed = intent.getIntExtra(EXTRA_SPEED, 1)
        var touch = intent.getBooleanExtra(EXTRA_TOUCH_VISIBLE, true)
        val hasTouch = intent.getBooleanExtra(EXTRA_HAS_TOUCH, false)
        publish(speed, touch)

        setContent {
            AppTheme {
                GameMenu(
                    states = states,
                    prefs = prefs,
                    speed = speed,
                    onSpeed = { speed = it; publish(speed, touch) },
                    touchVisible = touch.takeIf { hasTouch },
                    onTouchVisible = { touch = it; publish(speed, touch) },
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

    private fun publish(speed: Int, touch: Boolean) {
        result.putExtra(EXTRA_SPEED, speed).putExtra(EXTRA_TOUCH_VISIBLE, touch)
        setResult(RESULT_OK, result)
    }

    /**
     * The game saw the presses made before the menu opened but not their releases, so a held
     * direction would stay held when it resumes. Hand releases (never presses, so using the
     * menu does not act in the game) and stick/d-pad motion to SDL; the paused game only
     * records them, so it resumes with the controller's real state.
     */
    private fun forwardToGame(event: KeyEvent) {
        val fromController = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (fromController && event.action == KeyEvent.ACTION_UP) SDLControllerManager.onNativePadUp(event.deviceId, event.keyCode)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) SDLControllerManager.handleJoystickMotionEvent(event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        forwardToGame(event)
        // Controllers: A activates the focused item, B/Start/guide/R3 go back to the game.
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> return super.dispatchKeyEvent(
                KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER,
                    event.repeatCount, event.metaState, event.deviceId, event.scanCode, event.flags, event.source),
            )
            // The release of the button that opened the menu arrives here too, so only a full
            // press inside the menu closes it. The guide button and R3 toggle the menu.
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                if (event.action == KeyEvent.ACTION_DOWN) buttonsDown += event.keyCode
                else if (buttonsDown.remove(event.keyCode) && !event.isCanceled) finish()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_SPEED = "speed"
        const val EXTRA_TOUCH_VISIBLE = "touch_visible"
        const val EXTRA_HAS_TOUCH = "has_touch"
        const val EXTRA_ACTION = "action"
        const val EXTRA_ARG = "arg"
    }
}

private enum class MenuTab(@StringRes val label: Int) {
    STATES(R.string.menu_tab_states),
    CHAPTERS(R.string.menu_tab_chapters),
    CHEATS(R.string.menu_tab_cheats),
    IMAGE(R.string.menu_tab_image),
}

@Composable
private fun GameMenu(
    states: SaveStates,
    prefs: AppPrefs,
    speed: Int,
    onSpeed: (Int) -> Unit,
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
    var speedOn by remember { mutableIntStateOf(speed) }
    var touchOn by remember { mutableStateOf(touchVisible) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmChapter by remember { mutableIntStateOf(0) }
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { resumeFocus.requestFocus() }

    // The image tab shows its effect on the paused game, so the scrim gets out of the way there.
    val scrim by animateColorAsState(if (tab == MenuTab.IMAGE) Color(0x660B0F0C) else Color(0xE60B0F0C), label = "scrim")
    Surface(color = scrim, contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
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
                Text(
                    stringResource(R.string.menu_speed),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    GameKeys.speeds.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = speedOn == s,
                            onClick = { speedOn = s; onSpeed(s) },
                            shape = SegmentedButtonDefaults.itemShape(i, GameKeys.speeds.size),
                            modifier = Modifier.focusRing(),
                            icon = {},
                        ) {
                            Text(if (s == GameKeys.SPEED_MAX) stringResource(R.string.menu_speed_max) else "$s×", maxLines = 1)
                        }
                    }
                }
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
                SingleChoiceSegmentedButtonRow(Modifier.width(560.dp)) {
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
                    MenuTab.CHEATS -> CheatsTab(prefs)
                    // At the right edge, so the middle of the game (where Link is) stays in view.
                    MenuTab.IMAGE -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) { ImageTab() }
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

/**
 * Image filters, applied to the running game at once (it redraws while paused), so the effect
 * shows behind the menu. Only within the video output the game started with: every filter with
 * OpenGL, sharp or smooth with SDL. Saved like the setting in Display. A filter that hangs the
 * game is caught the same way as at startup (Shaders.markGameStart).
 */
@Composable
private fun ImageTab() {
    val context = LocalContext.current
    val data = remember { GameData(context) }
    var ini by remember { mutableStateOf(data.readIni()) }
    var imported by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { imported = withContext(Dispatchers.IO) { Shaders.imported(data.dir) } }
    val current = Shaders.current(ini)
    // The game picked its output at startup; switching it takes a restart.
    val openGl = Shaders.usesOpenGl(ini)

    fun choose(choice: Shaders.Choice) {
        val updated = Ini(ini.text).apply { Shaders.apply(this, choice, openGl) }
        data.writeIni(updated)
        ini = updated
        Shaders.markGameStart(updated, data.dir)
        GameActivity.nativeSetImageFilter((choice as? Shaders.Choice.Shader)?.path.orEmpty(), choice == Shaders.Choice.Smooth)
    }

    // Narrow, compact rows leave most of the game visible next to the list.
    val panel = Color(0xD90B0F0C)
    Column(Modifier.width(280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val shape = RoundedCornerShape(12.dp)
        @Composable
        fun row(label: String, choice: Shaders.Choice) {
            val selected = current == choice
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(panel)
                    .focusRing(shape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { choose(choice) })
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        row(stringResource(R.string.filter_sharp), Shaders.Choice.Sharp)
        row(stringResource(R.string.filter_smooth), Shaders.Choice.Smooth)
        if (!openGl) {
            Text(
                stringResource(R.string.menu_image_sdl),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.background(panel, shape).padding(12.dp),
            )
            return@Column
        }
        for (b in Shaders.Builtin.entries) row(stringResource(b.label), Shaders.Choice.Shader(b.path))
        for (path in imported) row(Shaders.displayName(path), Shaders.Choice.Shader(path))
    }
}

@Composable
private fun CheatsTab(prefs: AppPrefs) {
    var flags by remember { mutableIntStateOf(prefs.cheatFlags) }
    var codes by remember { mutableStateOf(prefs.cheatCodes) }
    var newCode by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    fun saveCodes(updated: List<Cheats.Code>) {
        codes = updated
        prefs.cheatCodes = updated
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.cheats_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        for (cheat in Cheats.BuiltIn.entries) {
            MenuSwitch(stringResource(cheat.label), flags and cheat.bit != 0, stringResource(cheat.description)) { on ->
                flags = if (on) flags or cheat.bit else flags and cheat.bit.inv()
                prefs.cheatFlags = flags
            }
        }

        Text(
            stringResource(R.string.cheats_codes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        Text(
            stringResource(R.string.cheats_codes_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        codes.forEachIndexed { i, code ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    MenuSwitch(code.name.ifBlank { code.code }, code.enabled, code.code.takeIf { code.name.isNotBlank() }) { on ->
                        saveCodes(codes.toMutableList().also { it[i] = code.copy(enabled = on) })
                    }
                }
                TextButton(
                    onClick = { saveCodes(codes.toMutableList().also { it.removeAt(i) }) },
                    modifier = Modifier.focusRing(),
                ) { Text(stringResource(R.string.saves_delete)) }
            }
        }

        val normalized = Cheats.normalize(newCode)
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newCode,
                onValueChange = { newCode = it.take(16) },
                label = { Text(stringResource(R.string.cheats_code)) },
                placeholder = { Text("7EF36D:A0") },
                isError = newCode.isNotBlank() && normalized == null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it.take(40) },
                label = { Text(stringResource(R.string.cheats_name)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    saveCodes(codes + Cheats.Code(normalized!!, newName.trim(), true))
                    newCode = ""
                    newName = ""
                },
                enabled = normalized != null && codes.size < Cheats.MAX_CODES,
                modifier = Modifier.focusRing(),
            ) { Text(stringResource(R.string.cheats_add)) }
        }
        if (newCode.isNotBlank() && normalized == null) {
            Text(
                stringResource(R.string.cheats_invalid),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The whole row toggles, so a controller can focus and flip it in one go. */
@Composable
private fun MenuSwitch(label: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = description?.let { { Text(it) } },
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
