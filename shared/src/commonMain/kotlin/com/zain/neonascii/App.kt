package com.zain.neonascii

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zain.neonascii.ascii.AsciiEffect
import com.zain.neonascii.ascii.AsciiUniforms
import com.zain.neonascii.ascii.buildGlyphAtlas
import com.zain.neonascii.ascii.createAsciiEffect
import com.zain.neonascii.audio.NeonAudio
import com.zain.neonascii.city.NeonCyan
import com.zain.neonascii.city.NeonPink
import com.zain.neonascii.city.TextCache
import com.zain.neonascii.city.drawCity
import com.zain.neonascii.game.drawBarriers
import com.zain.neonascii.game.drawCombatFx
import com.zain.neonascii.game.drawEnemies
import com.zain.neonascii.game.drawGhosts
import com.zain.neonascii.game.drawLaserSights
import com.zain.neonascii.game.drawRunner
import androidx.compose.ui.text.drawText
import com.zain.neonascii.res.Res
import com.zain.neonascii.res.jetbrains_mono_bold
import com.zain.neonascii.res.jetbrains_mono_medium
import org.jetbrains.compose.resources.Font
import kotlin.math.min
import kotlin.math.roundToInt

private val Ink = Color(0xFF05010F)

/** True where on-screen touch controls should be shown (Android). */
expect val isTouchPlatform: Boolean

@Composable
fun NeonAsciiApp(state: NeonState = remember { NeonState() }, audio: Boolean = true) {
    val mediumFont = Font(Res.font.jetbrains_mono_medium)
    val boldFont = Font(Res.font.jetbrains_mono_bold)
    val mono = remember(mediumFont) { FontFamily(mediumFont) }
    val monoBold = remember(boldFont) { FontFamily(boldFont) }
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val texts = remember(measurer, monoBold) { TextCache(measurer, monoBold) }

    LaunchedEffect(audio) {
        if (audio) {
            NeonAudio.ensureStarted()
            state.synth = NeonAudio.synth
            NeonAudio.loadRecordings()
        }
    }
    LaunchedEffect(Unit) {
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                if (last > 0) state.update(((now - last) / 1e9f).coerceIn(0f, 0.05f))
                last = now
                state.frame++
            }
        }
    }

    var screen by remember { mutableStateOf(IntSize.Zero) }
    // Zoom magnifies the ASCII image itself: glyph cells grow with the world, so you can read the
    // characters that make up every shape. Atlases are re-rendered per size (sharp, not stretched).
    val zoomNow = state.zoomShown / 10f
    val cellW = if (screen.width > 0) (min(screen.width, screen.height) / CellPresets[state.cellPreset] * zoomNow).roundToInt().coerceAtLeast(4) else 0
    val cellH = (cellW / 0.6f).roundToInt()
    val effects = remember(mono) { HashMap<Int, AsciiEffect?>() }
    val effect = if (cellW > 0) effects.getOrPut(cellW) { createAsciiEffect(buildGlyphAtlas(measurer, mono, cellW, cellH)) } else null
    val uniforms = remember { AsciiUniforms() }
    // The runner gets a finer grid of their own, so they read as the hero, as in the reference reel.
    val fineW = (cellW * 0.6f).roundToInt().coerceAtLeast(3)
    val fineH = (fineW / 0.6f).roundToInt()
    val fineEffect = if (cellW > 0) effects.getOrPut(-fineW) { createAsciiEffect(buildGlyphAtlas(measurer, mono, fineW, fineH)) } else null
    val fineUniforms = remember { AsciiUniforms().apply { opaque = false; ambient = 1f; lightCount = 0; groundY = 1e9f } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .onSizeChanged { screen = it; state.resize(it.width.toFloat(), it.height.toFloat()) }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e -> handleKey(state, e.key, e.type == KeyEventType.KeyDown) }
    ) {
        // The city and the runner, drawn normally and turned into glyphs by the shader on this layer.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    state.frame // re-run every frame
                    translationX = state.shakeX
                    translationY = state.shakeY
                    val fx = effect
                    renderEffect = if (fx != null) {
                        uniforms.width = state.width
                        uniforms.height = state.height
                        uniforms.cellW = cellW.toFloat()
                        uniforms.cellH = cellH.toFloat()
                        uniforms.time = state.t
                        uniforms.splitX = when (state.mode) {
                            ViewMode.Ascii -> 1e9f
                            ViewMode.Split -> state.splitX
                            ViewMode.Raw -> -1f
                        }
                        uniforms.groundY = state.groundY
                        uniforms.glitch = state.glitch
                        uniforms.shockX = state.shock.x
                        uniforms.shockY = state.shock.y
                        uniforms.shockAge = state.shockAge
                        state.lights.copyInto(uniforms.lights)
                        uniforms.lightCount = state.lightCount
                        uniforms.ambient = state.ambient
                        fx.renderEffect(uniforms)
                    } else null
                }
        ) {
            state.frame
            drawCity(state, texts)
        }

        // The runner alone, on the finer grid, over the city.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    state.frame
                    translationX = state.shakeX
                    translationY = state.shakeY
                    val fx = fineEffect
                    renderEffect = if (fx != null) {
                        fineUniforms.width = state.width
                        fineUniforms.height = state.height
                        fineUniforms.cellW = fineW.toFloat()
                        fineUniforms.cellH = fineH.toFloat()
                        fineUniforms.time = state.t
                        fineUniforms.splitX = uniforms.splitX
                        fineUniforms.glitch = state.glitch
                        fx.renderEffect(fineUniforms)
                    } else null
                }
        ) {
            state.frame
            state.ambience.drawWalkers(this, state)
            state.reactive.drawRopes(this, state)
            state.reactive.drawCans(this, state)
            drawBarriers(state)
            drawEnemies(state)
            drawGhosts(state)
            drawRunner(state)
            drawCombatFx(state)
        }

        // Crisp overlay: near rain, splashes and the split divider. Also takes the pointer.
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Pinch (or wheel, below) zooms at the pointer; dragging pans while zoomed in.
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (zoom != 1f) state.zoomAt(zoom, centroid)
                        state.pan(pan.x, pan.y)
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var downAt: Offset? = null
                        var downTime = 0L
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.first()
                            val touch = c.type == PointerType.Touch
                            state.pointerIsTouch = touch
                            when (e.type) {
                                PointerEventType.Press -> {
                                    downAt = c.position; downTime = c.uptimeMillis
                                    state.pointer = c.position
                                }
                                PointerEventType.Move -> {
                                    state.pointer = c.position
                                    if (state.mode == ViewMode.Split && (c.pressed || !touch)) state.splitX = c.position.x
                                }
                                PointerEventType.Release -> {
                                    val start = downAt
                                    if (start != null && (c.position - start).getDistance() < 30f && c.uptimeMillis - downTime < 350) {
                                        state.tap(c.position)
                                    }
                                    downAt = null
                                    if (touch) state.pointer = null
                                }
                                PointerEventType.Exit -> state.pointer = null
                                PointerEventType.Scroll -> state.zoomAt(if (c.scrollDelta.y < 0f) 1.15f else 1f / 1.15f, c.position)
                            }
                        }
                    }
                }
        ) {
            state.frame
            state.reactive.drawWeeds(this, state)
            state.rain.drawNear(this, state.u, state.groundY)
            state.ambience.drawDrips(this, state)
            state.ambience.drawSparks(this, state)
            state.ambience.drawMotes(this, state)
            state.reactive.drawRipples(this, state)
            drawLaserSights(state)
            // Name tag over the runner, crisp, like the player names in the reference.
            val tag = texts.get("RONIN", 11f * (state.u / state.zoom) * 1.6f) // same size at any zoom
            drawText(
                tag, Color.White,
                Offset(state.screenX(state.runner.x) - tag.size.width / 2f, state.groundY - (state.runner.y + 255f) * state.u - tag.size.height),
                alpha = 0.85f,
            )
            if (state.mode == ViewMode.Split) {
                val x = state.splitX
                drawLine(NeonCyan, Offset(x, 0f), Offset(x, size.height), strokeWidth = 6f, alpha = 0.25f)
                drawLine(NeonCyan, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f, alpha = 0.9f)
            }
        }

        if (state.hudVisible) Hud(state, mono, monoBold, cellW, cellH, screen)
        if (isTouchPlatform) TouchControls(state, monoBold)
    }
}

private fun handleKey(state: NeonState, key: Key, down: Boolean): Boolean {
    val input = state.input
    when (key) {
        Key.A, Key.DirectionLeft -> input.left = down
        Key.D, Key.DirectionRight -> input.right = down
        Key.S, Key.DirectionDown -> input.down = down
        Key.W, Key.DirectionUp, Key.Spacebar -> if (down) input.pressJump() else input.releaseJump()
        Key.J -> if (down) input.attackPressed = true
        Key.K -> if (down) input.shootPressed = true
        Key.ShiftLeft, Key.ShiftRight, Key.L -> if (down) input.dashPressed = true
        else -> {
            if (!down) return false
            when (key) {
                Key.One -> state.mode = ViewMode.Ascii
                Key.Two -> state.mode = ViewMode.Split
                Key.Three -> state.mode = ViewMode.Raw
                Key.Tab -> state.cycleMode()
                Key.C -> state.cycleCells()
                Key.M -> state.soundOn = !state.soundOn
                Key.H -> state.hudVisible = !state.hudVisible
                Key.T -> state.strikeRandom()
                Key.P -> { state.autopilot = !state.autopilot; if (!state.autopilot) input.clear() }
                Key.Equals, Key.Plus, Key.NumPadAdd -> state.zoomBy(1.25f)
                Key.Minus, Key.NumPadSubtract -> state.zoomBy(0.8f)
                Key.Zero -> state.resetZoom()
                else -> return false
            }
        }
    }
    return true
}

@Composable
private fun Hud(state: NeonState, mono: FontFamily, monoBold: FontFamily, cellW: Int, cellH: Int, size: IntSize) {
    val small = TextStyle(fontFamily = mono, fontSize = 10.sp, color = Color(0xCCBFEFFF))
    // Soft scrims so the HUD text reads over busy glyphs.
    Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color(0xCC05010F), Color.Transparent))))
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        Column(Modifier.align(Alignment.TopStart)) {
            BasicText("NEON//ASCII", style = TextStyle(fontFamily = monoBold, fontSize = 18.sp, color = NeonCyan, letterSpacing = 2.sp))
            BasicText("real-time ascii shader · compose multiplatform", style = small)
            Spacer(Modifier.height(8.dp))
            BasicText(
                "HP " + "█".repeat(state.hpShown) + "░".repeat(com.zain.neonascii.game.MAX_HP - state.hpShown),
                style = TextStyle(fontFamily = monoBold, fontSize = 13.sp, color = if (state.hpShown <= 1) NeonPink else NeonCyan, letterSpacing = 1.sp),
            )
            BasicText(
                "◆ DATA ${state.shardsTaken}/${state.level.shards.size}   ✕ KILLS ${state.killsShown}   ▸ ${state.progress}%" + if (state.autopilot) "   AUTO" else "",
                style = TextStyle(fontFamily = monoBold, fontSize = 11.sp, color = NeonPink, letterSpacing = 1.sp),
            )
            if (state.arenaShown.isNotEmpty()) BasicText(state.arenaShown, style = TextStyle(fontFamily = monoBold, fontSize = 11.sp, color = Color(0xFFFF5A4A), letterSpacing = 2.sp))
        }
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            val cols = if (cellW > 0) size.width / cellW else 0
            val rows = if (cellH > 0) size.height / cellH else 0
            BasicText("${state.fpsShown} fps · $cols×$rows", style = small.copy(color = NeonPink))
            Spacer(Modifier.height(8.dp))
            if (size.height > size.width) Spacer(Modifier.height(44.dp)) // below the title on phones
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ViewMode.entries.forEach { m -> Chip(m.label, state.mode == m, monoBold) { state.mode = m } }
                Chip("CELLS ${"SML"[state.cellPreset]}", false, monoBold) { state.cycleCells() }
                Chip(if (state.soundOn) "♪ ON" else "♪ OFF", state.soundOn, monoBold) { state.soundOn = !state.soundOn }
            }
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Chip("ZOOM －", false, monoBold) { state.zoomBy(0.8f) }
                Chip("${state.zoomShown / 10}.${state.zoomShown % 10}×", state.zoomShown > 10, monoBold) { state.resetZoom() }
                Chip("＋", false, monoBold) { state.zoomBy(1.25f) }
            }
        }
        if (state.comboShown >= 2) {
            BasicText(
                "${state.comboShown} HIT",
                style = TextStyle(fontFamily = monoBold, fontSize = (22 + minOf(state.comboShown, 10) * 1.5f).sp, color = NeonPink, letterSpacing = 2.sp),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        if (state.bannerShown.isNotEmpty() || state.downed) {
            BasicText(
                if (state.downed) "SIGNAL LOST\nrespawning…" else state.bannerShown,
                style = TextStyle(fontFamily = monoBold, fontSize = 24.sp, color = if (state.downed) NeonPink else Color(0xFFFF5A4A), letterSpacing = 4.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (state.cleared) {
            BasicText(
                "SECTOR CLEAR\nDATA ${state.shardsTaken}/${state.level.shards.size}",
                style = TextStyle(fontFamily = monoBold, fontSize = 26.sp, color = NeonCyan, letterSpacing = 4.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (!isTouchPlatform) {
            // The controls stay on screen (H hides the whole HUD for clean recordings).
            BasicText(
                "A/D move · SPACE jump ×2 · S drop · J saber (×3 combo, deflects) · K blaster · SHIFT dash · stomp enemies\n" +
                    "wheel/+/- zoom · 0 reset · 1/2/3 view · C cells · T lightning · P autopilot · M mute · H hide HUD",
                style = small.copy(color = small.color.copy(alpha = 0.85f), textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun TouchControls(state: NeonState, font: FontFamily) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp, vertical = 26.dp)) {
        Row(Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HoldButton("◀", 74.dp, font) { state.input.left = it }
            HoldButton("▶", 74.dp, font) { state.input.right = it }
        }
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                HoldButton("DASH", 58.dp, font) { if (it) state.input.dashPressed = true }
                Spacer(Modifier.width(10.dp))
                HoldButton("SHOOT", 66.dp, font) { if (it) state.input.shootPressed = true }
                Spacer(Modifier.width(12.dp))
                HoldButton("SABER", 74.dp, font) { if (it) state.input.attackPressed = true }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                HoldButton("▼", 56.dp, font) { state.input.down = it }
                Spacer(Modifier.width(14.dp))
                HoldButton("JUMP", 92.dp, font) { if (it) state.input.pressJump() else state.input.releaseJump() }
            }
        }
    }
}

/** A round button that reports press and release, so it can be held; multi-touch friendly. */
@Composable
private fun HoldButton(label: String, size: Dp, font: FontFamily, onHold: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(size)
            .background(if (held) NeonCyan.copy(alpha = 0.35f) else Color(0x66050112), CircleShape)
            .border(1.5.dp, NeonCyan.copy(alpha = if (held) 1f else 0.55f), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    held = true; onHold(true)
                    while (true) {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                        if (e.changes.none { it.pressed }) break
                    }
                    held = false; onHold(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(fontFamily = font, fontSize = if (label.length > 1) 14.sp else 22.sp, color = Color.White, letterSpacing = 1.sp))
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, font: FontFamily, onClick: () -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    BasicText(
        label,
        style = TextStyle(fontFamily = font, fontSize = 9.sp, color = if (selected) Color.White else Color(0xFF9FE9F5), letterSpacing = 1.sp),
        modifier = Modifier
            .background(if (selected) NeonCyan.copy(alpha = 0.28f) else Color(0x99050112), shape)
            .border(1.dp, NeonCyan.copy(alpha = if (selected) 0.9f else 0.4f), shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp),
    )
}
