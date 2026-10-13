package com.zain.neonascii

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val window = rememberWindowState(width = 1280.dp, height = 800.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "NEON//ASCII",
        state = window,
        onPreviewKeyEvent = { e ->
            if (e.type != KeyEventType.KeyDown) return@Window false
            when (e.key) {
                Key.F11 -> {
                    window.placement = if (window.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                    true
                }
                Key.Escape -> {
                    if (window.placement == WindowPlacement.Fullscreen) { window.placement = WindowPlacement.Floating; true } else false
                }
                else -> false
            }
        },
    ) {
        NeonAsciiApp()
    }
}
