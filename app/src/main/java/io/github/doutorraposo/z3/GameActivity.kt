package io.github.doutorraposo.z3

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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.libsdl.app.SDLActivity
import java.io.File

/**
 * Hosts the native game. Runs in its own process (":game"): upstream keeps its state in
 * C globals that are only initialised once, so every session gets a fresh process.
 */
class GameActivity : SDLActivity() {
    private var touch: TouchControlsView? = null
    private var menuButton: MenuButtonView? = null
    private var autosave = false
    /** See [GameKeys.speeds]. */
    private var speed = 1
    private var menuOpen = false
    private var selectHeld = false
    /** Set from the menu; unlike hiding for a controller, a touch does not bring the pad back. */
    private var touchHiddenByUser = false
    private var doubleTap: GestureDetector? = null
    private val states by lazy { SaveStates(filesDir) }
    private val menuShot by lazy { File(cacheDir, "menu_shot.png") }

    override fun onCreate(savedInstanceState: Bundle?) {
        val data = GameData(this)
        data.prepare()
        val ini = data.readIni()
        autosave = ini.getBool("General", "Autosave")
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

        val fullScreen = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (prefs.touchControls) {
            touch = TouchControlsView(this) { code, down ->
                if (down) onNativeKeyDown(code) else onNativeKeyUp(code)
            }.also {
                it.opacity = prefs.touchOpacity
                mLayout.addView(it, fullScreen)
            }
        }
        if (prefs.menuButton) {
            menuButton = MenuButtonView(this, ::openMenu).also {
                it.opacity = prefs.touchOpacity
                mLayout.addView(it, fullScreen)
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
        captureScreen(menuShot) {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(this, GameMenuActivity::class.java)
                    .putExtra(GameMenuActivity.EXTRA_SPEED, speed)
                    .putExtra(GameMenuActivity.EXTRA_HAS_TOUCH, touch != null)
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
        if (data == null) return

        setSpeed(data.getIntExtra(GameMenuActivity.EXTRA_SPEED, speed))
        touch?.let {
            val visible = data.getBooleanExtra(GameMenuActivity.EXTRA_TOUCH_VISIBLE, true)
            // Only act on a change: a pad hidden because a controller is in use stays hidden.
            if (visible == touchHiddenByUser) it.visibility = if (visible) View.VISIBLE else View.GONE
            touchHiddenByUser = !visible
        }

        // These key presses are queued now and run on the game thread as soon as SDL resumes.
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
            null -> Unit
        }
    }

    /** Fixed rates go through the patched frame loop; "max" holds upstream's turbo key. */
    private fun setSpeed(value: Int) {
        speed = value
        nativeSetSpeed(if (value == GameKeys.SPEED_MAX) 1 else value)
        if (value == GameKeys.SPEED_MAX) onNativeKeyDown(GameKeys.TURBO) else onNativeKeyUp(GameKeys.TURBO)
        menuButton?.badge = when (value) {
            1 -> null
            GameKeys.SPEED_MAX -> "»"
            else -> "$value×"
        }
    }

    // --- Input -----------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromController = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        // SDLSurface forwards the system back key to the game as a keyboard key, so
        // onBackPressed never runs. Catch it here and open the menu instead.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !fromController) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) openMenu()
            return true
        }
        if (fromController) {
            // Hide the overlay while a physical controller is in use; it comes back on the next touch.
            hideTouch()
            if (isMenuShortcut(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Controllers open the menu with the right stick button, the guide button, or Select+Start. */
    private fun isMenuShortcut(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectHeld = down
            KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (down && event.repeatCount == 0) openMenu()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_START -> if (selectHeld) {
                if (down && event.repeatCount == 0) openMenu()
                return true
            }
        }
        return false
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) hideTouch()
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        touch?.let { if (it.visibility != View.VISIBLE && !touchHiddenByUser) it.visibility = View.VISIBLE }
        doubleTap?.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    private fun hideTouch() {
        touch?.let {
            if (it.visibility == View.VISIBLE) {
                it.releaseAll()
                it.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // SDLActivity.onDestroy waits for main() to return (which writes the autosave),
        // so the process can go now; the launcher lives in the main process.
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val REQUEST_MENU = 1

        /** android_main.c, backed by patches/zelda3/0001-fixed-rate-fast-forward.patch. */
        @JvmStatic
        external fun nativeSetSpeed(speed: Int)
    }
}
