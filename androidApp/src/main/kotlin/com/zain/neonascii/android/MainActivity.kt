package com.zain.neonascii.android

import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zain.neonascii.NeonAsciiApp
import com.zain.neonascii.audio.NeonAudio

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Target a steady 60 fps: on 120 Hz panels this gives every frame the full 16.6 ms.
        window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { NeonAsciiApp() }
    }

    override fun onResume() {
        super.onResume()
        NeonAudio.paused = false
    }

    override fun onPause() {
        NeonAudio.paused = true
        super.onPause()
    }
}
