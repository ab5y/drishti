package app.drishti.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.drishti.input.RemoteKeys

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            MaterialTheme { HomeScreen(state) }
        }
    }

    // Intercept before Compose focus handling can consume D-pad keys.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dir = RemoteKeys.toDir(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) viewModel.onResponse(dir)
        return true
    }
}
