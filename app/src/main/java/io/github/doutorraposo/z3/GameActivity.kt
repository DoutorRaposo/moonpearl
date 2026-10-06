package io.github.doutorraposo.z3

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.libsdl.app.SDLActivity

/**
 * Hosts the native game. Runs in its own process (":game"): upstream keeps its state in
 * C globals that are only initialised once, so every session gets a fresh process.
 */
class GameActivity : SDLActivity() {
    private var touch: TouchControlsView? = null
    private var autosave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val data = GameData(this)
        data.prepare()
        autosave = data.readIni().getBool("General", "Autosave")
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

        if (prefs.touchControls) {
            touch = TouchControlsView(this) { code, down ->
                if (down) onNativeKeyDown(code) else onNativeKeyUp(code)
            }.also {
                it.opacity = prefs.touchOpacity
                mLayout.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
    }

    override fun onPause() {
        touch?.releaseAll()
        if (autosave && !isFinishing && !mBrokenLibraries) saveResumePoint()
        super.onPause()
    }

    /**
     * Upstream only writes the autosave when main() returns, which never happens if Android
     * kills the process in the background. Press upstream's "save state 1" shortcut instead
     * (Shift+F1, the slot Autosave restores). SDL hands the game every event queued before a
     * pause and only then blocks the game thread, so the save runs on the game thread before
     * the app goes to the background.
     */
    private fun saveResumePoint() {
        onNativeKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
        onNativeKeyDown(KeyEvent.KEYCODE_F1)
        onNativeKeyUp(KeyEvent.KEYCODE_F1)
        onNativeKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val fromController = event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        // SDLSurface forwards the system back key to the game as a keyboard key, so
        // onBackPressed never runs. Catch it here; controllers keep their own mapping.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !fromController) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) confirmQuit()
            return true
        }
        // Hide the overlay while a physical controller is in use; bring it back on the next touch.
        if (fromController) hideTouch()
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) hideTouch()
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        touch?.let { if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE }
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

    private fun confirmQuit() {
        touch?.releaseAll()
        AlertDialog.Builder(this)
            .setMessage(R.string.quit_game_message)
            .setPositiveButton(R.string.quit_game_confirm) { _, _ -> finish() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // SDLActivity.onDestroy waits for main() to return (which writes the autosave),
        // so the process can go now; the launcher lives in the main process.
        Process.killProcess(Process.myPid())
    }
}
