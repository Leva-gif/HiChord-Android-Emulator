package com.hichord.pocket

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hichord.pocket.audio.NativeAudioBridge
import com.hichord.pocket.state.HiChordViewModel
import com.hichord.pocket.ui.ClassicHiChordScreen

class MainActivity : ComponentActivity() {

    private val viewModel: HiChordViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        setContent {
            ClassicHiChordScreen(viewModel = viewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        if (NativeAudioBridge.startEngine()) {
            viewModel.onEngineStarted()
        } else {
            Toast.makeText(this, "Audio engine failed to start", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        viewModel.onEngineStopped()
        NativeAudioBridge.stopEngine()
        super.onStop()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
