package app.drishti.ui

import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.drishti.core.DisplaySize
import app.drishti.input.RemoteKeys

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setDisplay(realDisplaySize())
        // OEM xdpi: used only to seed the calibration rectangle and for a sanity warning.
        val xdpi = resources.displayMetrics.xdpi.toDouble()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            MaterialTheme { AppRoot(state, viewModel, xdpi) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setDisplay(realDisplaySize())
    }

    // Intercept before Compose focus handling can consume D-pad keys.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dir = RemoteKeys.toDir(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) viewModel.onResponse(dir)
        return true
    }

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
