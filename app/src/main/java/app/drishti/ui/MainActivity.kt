package app.drishti.ui

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.drishti.core.DisplaySize
import app.drishti.core.usesTestDisplay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setDisplay(realDisplaySize())
        // OEM xdpi: used only to seed the calibration rectangle and for a sanity warning.
        val xdpi = resources.displayMetrics.xdpi.toDouble()
        val compose = ComposeView(this).apply {
            setContent {
                val state by viewModel.state.collectAsStateWithLifecycle()
                MaterialTheme { AppRoot(state, viewModel, xdpi) }
            }
        }
        // Keys are taken before the IME and before the touch-mode check. Otherwise
        // Android swallows the first D-pad press after any touch just to leave touch
        // mode, and a patient's first answer after the operator taps the screen is lost.
        // The root must be on the focus path (focusable in touch mode) for pre-IME
        // dispatch to reach it.
        root = object : FrameLayout(this) {
            override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
                handleKey(event) || super.dispatchKeyEventPreIme(event)
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            addView(compose)
        }
        setContentView(root)
        root.requestFocus()
        // Compose clears view focus when a text field loses focus; take it straight back
        // so the next key-down still goes through pre-IME dispatch.
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus == null) root.post { if (root.findFocus() == null) root.requestFocus() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::applyWindow) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setDisplay(realDisplaySize())
    }

    /** Landscape + full screen for the test flow; test brightness and keep-awake while testing (D33). */
    private fun applyWindow(state: UiState) {
        // Re-claim focus if a screen change left nothing focused (see onCreate).
        if (root.findFocus() == null) root.requestFocus()
        val ready = state as? UiState.Ready
        val inFlow = ready?.screen == Screen.FLOW
        val orientation = if (inFlow) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (requestedOrientation != orientation) requestedOrientation = orientation

        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (inFlow) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }

        val testConfig = ready?.config?.takeIf { inFlow && ready.flow?.usesTestDisplay == true }
        val testing = testConfig != null
        val brightness = testConfig?.testBrightness?.toFloat() ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window.attributes.screenBrightness != brightness) {
            window.attributes = window.attributes.apply { screenBrightness = brightness }
        }
        if (testing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Fallback for keys that do not pass through the focused view chain.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleKey(event) || super.dispatchKeyEvent(event)

    private fun handleKey(event: KeyEvent): Boolean =
        viewModel.onKey(
            keyCode = event.keyCode,
            keyName = KeyEvent.keyCodeToString(event.keyCode),
            scanCode = event.scanCode,
            deviceName = event.device?.name ?: "unknown",
            isDown = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
            eventTimeMs = event.eventTime,
        )

    /** Full physical resolution in the current display mode, regardless of orientation or insets. */
    private fun realDisplaySize(): DisplaySize =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            DisplaySize.of(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            DisplaySize.of(m.widthPixels, m.heightPixels)
        }
}
