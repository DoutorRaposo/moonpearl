package io.github.doutorraposo.moonpearl

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import org.libsdl.app.SDLActivity
import java.io.File

/**
 * Hosts the native game. Runs in its own process (":game"): upstream keeps its state in
 * C globals that are only initialised once, so every session gets a fresh process.
 */
class GameActivity : SDLActivity() {
    /** The touch pad and/or the menu button; null when both are switched off. */
    private var touch: TouchControlsView? = null
    private var hasPad = false
    private var autosave = false
    /** See [GameKeys.speeds]. */
    private var speed = 1
    private var menuOpen = false
    /** The game was paused (upstream's pause toggle) when the menu opened. */
    private var pausedForMenu = false
    /** Controller buttons whose press opened nothing yet; the menu opens on their release. */
    private val menuButtonsDown = HashSet<Int>()
    /** LT and RT, as buttons and as analog axes (controllers report either or both). */
    private val triggerKey = BooleanArray(2)
    private val triggerAxis = BooleanArray(2)
    private var speedToast: Toast? = null
    /** The speed before a trigger press that turned out to be the start of LT+RT. */
    private var speedBeforeTrigger = 1
    private var triggerSpeed = false
    private var holdSpeed = GameKeys.SPEED_MAX
    private var l3Hold = false
    /** Fast-forward holds in progress (touch button, L3); the set speed comes back when all end. */
    private var holds = 0
    private var rewindEnabled = false
    private var rewind: RewindOverlayView? = null
    /** Whether the touch pad showed before rewinding; it hides while the panel is up. */
    private var padBeforeRewind = false
    /** Chapter to load once the game runs (EXTRA_CHAPTER), or 0. */
    private var startChapter = 0
    private val chapterStart = object : Runnable {
        override fun run() {
            // Key presses only reach the game once its main loop runs (after the autosave loads).
            if (nativeMainLoopCount() == 0) {
                mLayout.postDelayed(this, 100)
                return
            }
            press(GameKeys.chapterKeys[startChapter - 1])
            startChapter = 0
        }
    }
    private val rewindPoll = object : Runnable {
        override fun run() {
            val view = rewind ?: return
            nativeRewindPosition()?.let { view.update(it[0], it[1], it[2]) }
            view.postDelayed(this, 100)
        }
    }
    /** Set from the menu; unlike hiding for a controller, a touch does not bring the pad back. */
    private var touchHiddenByUser = false
    private var doubleTap: GestureDetector? = null
    private val states by lazy { SaveStates(filesDir) }
    private val menuShot by lazy { File(cacheDir, "menu_shot.png") }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        val data = GameData(this)
        data.prepare()
        val ini = data.readIni()
        autosave = ini.getBool("General", "Autosave")
        val iniBeforeMsu = ini.text
        val msuTracks = MsuPack.prepareForGame(this, ini, AppPrefs(this))
        if (msuTracks > 0) android.util.Log.i("moonpearl", "MSU-1: $msuTracks tracks linked")
        if (ini.text != iniBeforeMsu) data.writeIni(ini)
        Shaders.markGameStart(ini, data.dir)
        // Keep "fill the screen" matched to the display, e.g. after moving the data to another device.
        if (AppPrefs(this).fillScreen) {
            val before = ini.text
            ini.useScreenAspectRatio(this)
            if (ini.text != before) data.writeIni(ini)
        }
        if (!data.hasAssets()) {
            super.onCreate(savedInstanceState)
            finish()
            return
        }

        super.onCreate(savedInstanceState)
        if (mBrokenLibraries) return

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val prefs = AppPrefs(this)
        // Upstream letterboxes through SDL_RenderSetLogicalSize; "overscan" makes that scale
        // to cover the screen instead. Read by SDL when main() creates the renderer.
        nativeSetenv("SDL_RENDER_LOGICAL_SIZE_MODE", if (prefs.fillScreen) "overscan" else "letterbox")

        applyCheats()
        triggerSpeed = prefs.triggerSpeed
        holdSpeed = prefs.holdSpeed
        l3Hold = prefs.l3Hold
        rewindEnabled = prefs.rewind
        nativeRewindEnable(rewindEnabled)
        nativeSetWidescreenHud(prefs.widescreenHud)

        startChapter = intent.getIntExtra(EXTRA_CHAPTER, 0).takeIf { it in 1..GameKeys.chapterKeys.size } ?: 0
        if (startChapter != 0) mLayout.post(chapterStart)

        val elements = TouchControlsView.elementsFor(prefs)
        hasPad = prefs.touchControls
        if (elements.isNotEmpty()) {
            touch = TouchControlsView(
                this,
                sendKey = { code, down -> if (down) onNativeKeyDown(code) else onNativeKeyUp(code) },
                onMenu = ::openMenu,
                onHold = { element, down ->
                    when (element) {
                        TouchLayout.Element.TURBO -> holdFastForward(down)
                        TouchLayout.Element.REWIND -> holdRewindButton(down)
                        else -> Unit
                    }
                },
            ).also {
                it.opacity = prefs.touchOpacity
                it.layout = prefs.touchLayout
                it.elements = elements
                mLayout.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        if (prefs.doubleTapMenu) {
            doubleTap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (touch?.isOverControl(e.x, e.y) == true) return false
                    openMenu()
                    return true
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        // SDL releases every key when the window loses focus (e.g. while the menu is open),
        // so hold the fast-forward key again.
        if (speed == GameKeys.SPEED_MAX && !mBrokenLibraries) onNativeKeyDown(GameKeys.TURBO)
    }

    override fun onPause() {
        touch?.releaseAll()
        // A hold whose release never comes (the app left) must not keep fast-forwarding.
        if (holds > 0) {
            holds = 1
            holdFastForward(false)
        }
        // Going to the background continues from the point shown, like pressing Continue.
        if (rewind != null) leaveRewind(cancel = false)
        if (autosave && !mBrokenLibraries) {
            // When finishing, main() writes the autosave itself on the way out.
            if (!isFinishing) saveResumePoint()
            captureScreen(states.thumbnailFile(0))
        }
        super.onPause()
    }

    /**
     * Upstream only writes the autosave when main() returns, which never happens if Android
     * kills the process in the background. Press upstream's "save state 1" shortcut instead
     * (Shift+F1, the slot Autosave restores). SDL hands the game every event queued before a
     * pause and only then blocks the game thread, so the save runs on the game thread before
     * the app goes to the background.
     */
    private fun saveResumePoint() = pressWith(GameKeys.SHIFT, GameKeys.slotKey(0))

    private fun press(keyCode: Int) {
        onNativeKeyDown(keyCode)
        onNativeKeyUp(keyCode)
    }

    private fun pressWith(modifier: Int, keyCode: Int) {
        onNativeKeyDown(modifier)
        press(keyCode)
        onNativeKeyUp(modifier)
    }

    // --- In-game menu ----------------------------------------------------------

    private fun openMenu() {
        if (menuOpen || mBrokenLibraries) return
        menuOpen = true
        touch?.releaseAll()
        // The menu is translucent, so the game stays visible and SDL (which pauses only in
        // onStop on Android 7+) keeps it running. Pause it with upstream's own toggle, which
        // stops frames and audio but still handles input, so releases keep the pad in sync.
        if (!pausedForMenu) {
            pressWith(GameKeys.SHIFT, GameKeys.PAUSE)
            pausedForMenu = true
        }
        captureScreen(menuShot) {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(this, GameMenuActivity::class.java)
                    .putExtra(GameMenuActivity.EXTRA_SPEED, speed)
                    .putExtra(GameMenuActivity.EXTRA_HAS_TOUCH, hasPad)
                    .putExtra(GameMenuActivity.EXTRA_TOUCH_VISIBLE, !touchHiddenByUser),
                REQUEST_MENU,
            )
        }
    }

    /** Saves the current frame (without overlays) as a save state preview, then runs [then]. */
    private fun captureScreen(target: File, then: () -> Unit = {}) {
        val surface = mSurface
        if (surface == null || surface.width == 0 || !surface.holder.surface.isValid) {
            target.delete()
            then()
            return
        }
        val full = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        val worker = HandlerThread("menu-shot").apply { start() }
        PixelCopy.request(surface, full, { result ->
            if (result == PixelCopy.SUCCESS) {
                target.outputStream().use { SaveStates.scaleForThumbnail(full).compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else {
                target.delete()
            }
            full.recycle()
            worker.quitSafely()
            Handler(Looper.getMainLooper()).post(then)
        }, Handler(worker.looper))
    }

    @Deprecated("SDLActivity is a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MENU) return
        menuOpen = false
        if (data != null) applyMenuResult(data)
        if (pausedForMenu) {
            pressWith(GameKeys.SHIFT, GameKeys.PAUSE)
            pausedForMenu = false
        }
    }

    private fun applyMenuResult(data: Intent) {
        setSpeed(data.getIntExtra(GameMenuActivity.EXTRA_SPEED, speed))
        applyCheats()
        touch?.let {
            val visible = data.getBooleanExtra(GameMenuActivity.EXTRA_TOUCH_VISIBLE, true)
            // Only act on a change: a pad hidden because a controller is in use stays hidden.
            if (visible == touchHiddenByUser) it.padVisible = visible
            touchHiddenByUser = !visible
        }

        // Queued key presses, run in order on the game thread before it is unpaused.
        val arg = data.getIntExtra(GameMenuActivity.EXTRA_ARG, 0)
        when (data.getStringExtra(GameMenuActivity.EXTRA_ACTION)?.let(GameMenuActivity.Action::valueOf)) {
            GameMenuActivity.Action.SAVE -> {
                states.writeThumbnail(arg, menuShot)
                pressWith(GameKeys.SHIFT, GameKeys.slotKey(arg))
            }
            GameMenuActivity.Action.LOAD -> press(GameKeys.slotKey(arg))
            GameMenuActivity.Action.CHAPTER -> press(GameKeys.chapterKeys[arg - 1])
            GameMenuActivity.Action.RESET -> pressWith(GameKeys.CTRL, GameKeys.RESET)
            GameMenuActivity.Action.QUIT -> finish()
            GameMenuActivity.Action.REWIND -> enterRewind()
            null -> Unit
        }
    }

    /** Fixed rates go through the patched frame loop; "max" holds upstream's turbo key. */
    private fun setSpeed(value: Int) {
        speed = value
        applyRate(if (holds > 0) fasterOf(holdSpeed, value) else value)
        touch?.menuBadge = when (value) {
            1 -> null
            GameKeys.SPEED_MAX -> "»"
            else -> "$value×"
        }
    }

    /**
     * Fast-forward while held (touch button, or L3 in hold mode) at the chosen rate, then back to
     * the set speed. A held rate never slows the game down from a faster set speed.
     */
    private fun holdFastForward(down: Boolean) {
        holds = (holds + if (down) 1 else -1).coerceAtLeast(0)
        val holding = holds > 0
        if (holding && down && holds > 1) return
        applyRate(if (holding) fasterOf(holdSpeed, speed) else speed)
    }

    private fun fasterOf(a: Int, b: Int) = when {
        a == GameKeys.SPEED_MAX || b == GameKeys.SPEED_MAX -> GameKeys.SPEED_MAX
        else -> maxOf(a, b)
    }

    /** Runs the game at [rate] without changing the set speed (see [setSpeed]). */
    private fun applyRate(rate: Int) {
        nativeSetSpeed(if (rate == GameKeys.SPEED_MAX) 1 else rate)
        if (rate == GameKeys.SPEED_MAX) onNativeKeyDown(GameKeys.TURBO) else onNativeKeyUp(GameKeys.TURBO)
    }

    // --- Rewind (rewind.c) ----------------------------------------------------------

    /** Stops the game where it is and shows the rewind panel. */
    private fun enterRewind() {
        if (!rewindEnabled || rewind != null || mBrokenLibraries) return
        nativeRewindMode(true, false)
        val view = RewindOverlayView(this, ::nativeRewindDirection, ::nativeRewindSeek, { leaveRewind(cancel = false) }, { leaveRewind(cancel = true) })
        rewind = view
        mLayout.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.post(rewindPoll)
        touch?.let {
            // Only the rewind button stays, so a finger holding it keeps going back.
            it.rewinding = true
            padBeforeRewind = it.padVisible
            it.padVisible = false
        }
    }

    /** Continues from the point shown, or with [cancel] from where the game was. */
    private fun leaveRewind(cancel: Boolean) {
        val view = rewind ?: return
        view.removeCallbacks(rewindPoll)
        mLayout.removeView(view)
        rewind = null
        nativeRewindMode(false, cancel)
        touch?.rewinding = false
        if (padBeforeRewind) touch?.padVisible = true
    }

    /** The touch rewind button goes back while held; the panel then offers the rest. */
    private fun holdRewindButton(down: Boolean) {
        if (down) enterRewind()
        if (rewind != null) nativeRewindDirection(if (down) -1 else 0)
    }

    /** In rewind mode LT goes back and RT forward while held (both or none: stay). */
    private fun updateRewindDirection() {
        val lt = triggerKey[0] || triggerAxis[0]
        val rt = triggerKey[1] || triggerAxis[1]
        nativeRewindDirection(if (lt == rt) 0 else if (lt) -1 else 1)
    }

    /** Controller keys in rewind mode: A or Start continue, B cancels, the d-pad scrubs. Nothing reaches the game. */
    private fun handleRewindKey(event: KeyEvent): Boolean {
        if (rewind == null) return false
        if (handleTriggerKey(event)) return true
        val up = event.action == KeyEvent.ACTION_UP && !event.isCanceled
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START -> if (up) leaveRewind(cancel = false)
            KeyEvent.KEYCODE_BUTTON_B -> if (up) leaveRewind(cancel = true)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                nativeRewindDirection(if (event.action == KeyEvent.ACTION_DOWN) dir else 0)
            }
        }
        return true
    }

    /** The menu edits cheats in AppPrefs; hand the current set to cheats.c. */
    private fun applyCheats() {
        val prefs = AppPrefs(this)
        val codes = prefs.cheatCodes.filter { it.enabled }.take(Cheats.MAX_CODES).map { it.packed }
        nativeSetCheats(prefs.cheatFlags, codes.toIntArray())
    }

    // --- Input -----------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromController = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        // SDLSurface forwards the system back key to the game as a keyboard key, so
        // onBackPressed never runs. Catch it here and open the menu instead.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !fromController) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                // Back leaves the rewind panel the way it came: at the present.
                if (rewind != null) leaveRewind(cancel = true) else openMenu()
            }
            return true
        }
        if (fromController) {
            // Hide the overlay while a physical controller is in use; it comes back on the next touch.
            hideTouch()
            if (handleRewindKey(event) || handleMenuShortcut(event) || handleTriggerKey(event) || handleSpeedCycle(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Controllers open the menu with the guide button or the right stick button. It opens on
     * release: opened on press, the release would land in the menu and close it, and an
     * unhandled guide button falls back to Home and leaves the app.
     */
    private fun handleMenuShortcut(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BUTTON_MODE && event.keyCode != KeyEvent.KEYCODE_BUTTON_THUMBR) return false
        if (event.action == KeyEvent.ACTION_DOWN) menuButtonsDown += event.keyCode
        else if (menuButtonsDown.remove(event.keyCode) && !event.isCanceled) openMenu()
        return true
    }

    /** L3 cycles the speed (1×, 2×, 3×, max), or fast-forwards while held. The game does not use it. */
    private fun handleSpeedCycle(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BUTTON_THUMBL) return false
        if (l3Hold) {
            if (event.repeatCount == 0) holdFastForward(event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val speeds = GameKeys.speeds
            stepSpeed(if (speed == speeds.last()) -speeds.lastIndex else 1)
        }
        return true
    }

    /**
     * LT and RT: together they open the rewind panel; on their own they step the speed (RT up,
     * LT down) if the player chose so. The game does not use them.
     */
    private fun handleTriggerKey(event: KeyEvent): Boolean {
        val i = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> 0
            KeyEvent.KEYCODE_BUTTON_R2 -> 1
            else -> return false
        }
        updateTrigger(i, key = event.action == KeyEvent.ACTION_DOWN)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            hideTouch()
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
                val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
                updateTrigger(0, axis = pressedWithHysteresis(lt, triggerAxis[0]))
                updateTrigger(1, axis = pressedWithHysteresis(rt, triggerAxis[1]))
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun pressedWithHysteresis(value: Float, wasPressed: Boolean) = if (wasPressed) value > 0.3f else value > 0.6f

    private fun updateTrigger(i: Int, key: Boolean = triggerKey[i], axis: Boolean = triggerAxis[i]) {
        val wasPressed = triggerKey[i] || triggerAxis[i]
        triggerKey[i] = key
        triggerAxis[i] = axis
        val pressed = key || axis
        if (pressed == wasPressed) return
        if (rewind != null) {
            updateRewindDirection()
            return
        }
        if (!pressed) return
        val other = triggerKey[1 - i] || triggerAxis[1 - i]
        if (other && rewindEnabled) {
            // LT+RT: undo the speed step the first trigger made, then rewind.
            if (triggerSpeed && speed != speedBeforeTrigger) {
                setSpeed(speedBeforeTrigger)
                speedToast?.cancel()
            }
            enterRewind()
        } else if (!other && triggerSpeed) {
            speedBeforeTrigger = speed
            stepSpeed(if (i == 1) 1 else -1)
        }
    }

    private fun stepSpeed(direction: Int) {
        val speeds = GameKeys.speeds
        val next = speeds[(speeds.indexOf(speed) + direction).coerceIn(0, speeds.lastIndex)]
        if (next == speed) return
        setSpeed(next)
        val label = if (next == GameKeys.SPEED_MAX) getString(R.string.menu_speed_max) else "$next×"
        speedToast?.cancel()
        speedToast = Toast.makeText(this, getString(R.string.speed_changed, label), Toast.LENGTH_SHORT).also { it.show() }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        touch?.let { if (!it.padVisible && !touchHiddenByUser && rewind == null) it.padVisible = true }
        doubleTap?.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    private fun hideTouch() {
        touch?.padVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        // SDLActivity.onDestroy waits for main() to return (which writes the autosave),
        // so the process can go now; the launcher lives in the main process.
        Process.killProcess(Process.myPid())
    }

    companion object {
        private const val REQUEST_MENU = 1

        /** Chapter (1-based) to start at, from the launcher; the menu has the same jump. */
        const val EXTRA_CHAPTER = "chapter"

        /** android_main.c, patches/zelda3/0010-widescreen-hud.patch: the HUD at the picture's edges. */
        @JvmStatic
        private external fun nativeSetWidescreenHud(enabled: Boolean)

        /** android_main.c: turns of the game's main loop so far (patch 0008); 0 until it runs. */
        @JvmStatic
        private external fun nativeMainLoopCount(): Int

        /** android_main.c, backed by patches/zelda3/0001-fixed-rate-fast-forward.patch. */
        @JvmStatic
        private external fun nativeSetSpeed(speed: Int)

        /**
         * android_main.c, backed by patches/zelda3/0008-live-image-filter.patch: the game thread
         * switches filters before its next frame and redraws a paused frame. [shader] is a path
         * relative to the game folder, or "" for none.
         */
        @JvmStatic
        external fun nativeSetImageFilter(shader: String, linear: Boolean)

        /** rewind.c (patches/zelda3/0009-rewind-hooks.patch): keep snapshots to rewind through. */
        @JvmStatic
        private external fun nativeRewindEnable(enabled: Boolean)

        /** rewind.c: enter rewind mode, or leave it (with [cancel], back at the present). */
        @JvmStatic
        private external fun nativeRewindMode(active: Boolean, cancel: Boolean)

        /** rewind.c: -1 goes back, 1 forward, 0 holds still. */
        @JvmStatic
        private external fun nativeRewindDirection(direction: Int)

        /** rewind.c: jump to a point, in snapshots back from the present (dragging the bar). */
        @JvmStatic
        private external fun nativeRewindSeek(stepsBack: Int)

        /** rewind.c: snapshots back from the present, kept, and at most (10 per second). */
        @JvmStatic
        private external fun nativeRewindPosition(): IntArray?

        /** cheats.c, run each frame through patches/zelda3/0002-frame-hook.patch. */
        @JvmStatic
        private external fun nativeSetCheats(flags: Int, codes: IntArray)
    }
}
