package com.zain.neonascii

import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.zain.neonascii.city.TextCache
import com.zain.neonascii.city.drawCity
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import java.io.File

/**
 * Dev tool: renders the app offscreen (no window, no audio) and writes PNGs, then times how long
 * the scene takes to record on the CPU. Run with `gradlew :shared:snapshot --no-configuration-cache`.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "snapshots").apply { mkdirs() }
    if ("perf-only" !in args) {
        shoot(out, "desktop", 1280, 800, 1f)
        if ("desktop-only" !in args) shoot(out, "phone", 1080, 2392, 2.8f)
    }
    perf(1280, 800, 1f)
    perf(1080, 2392, 2.8f)
    println("snapshots written to ${out.absolutePath}")
}

private fun shoot(out: File, name: String, w: Int, h: Int, density: Float) {
    val state = NeonState()
    val scene = ImageComposeScene(w, h, Density(density)) {
        NeonAsciiApp(remember { state }, audio = false)
    }
    val frameNs = 16_666_667L
    var now = 0L
    // Rendering on the CPU is slow, so advance the simulation directly and render only to save.
    fun run(frames: Int) {
        scene.render(now); now += frameNs
        repeat(frames) { state.update(1f / 60f) }
    }
    fun save(tag: String) {
        val img = scene.render(now)
        File(out, "$name-$tag.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    run(90)
    save("start")
    // Wait for the sky-train to pass with the crowd about.
    var waited = 0
    while (!(state.ambience.trainActive && state.ambience.trainPresence > 0.7f) && waited < 3000) { run(10); waited += 10 }
    save("train")
    state.mode = ViewMode.Raw
    run(1)
    save("train-raw")
    state.mode = ViewMode.Ascii
    state.zoomAt(2.6f, Offset(w * 0.5f, h * 0.72f))
    run(1)
    save("crowd-zoom")
    state.resetZoom()
    run(60)
    state.autopilot = true
    run(240)
    save("run1")
    state.zoomAt(3f, Offset(state.screenX(state.runner.x), state.groundY - 40f * state.u))
    run(1)
    save("feet-zoom")
    state.mode = ViewMode.Raw
    run(1)
    save("feet-zoom-raw")
    state.mode = ViewMode.Ascii
    state.resetZoom()
    state.mode = ViewMode.Split
    run(2)
    save("split")
    state.mode = ViewMode.Raw
    run(2)
    save("raw")
    state.mode = ViewMode.Ascii
    state.cellPreset = 1
    run(2)
    save("cells-m")
    state.cellPreset = 2
    run(2)
    save("cells-l")
    state.cellPreset = 0
    run(300)
    save("run2")
    // Play on into the fights and keep a few frames from them.
    var shots = 0
    repeat(60) {
        run(30)
        val near = state.combat.enemies.count { it.alive && kotlin.math.abs(it.x - state.runner.x) < 700f }
        if ((state.combat.activeArena != null || near >= 2) && shots < 4) { save("fight${++shots}") }
    }
    println("$name: x=${state.runner.x.toInt()} hp=${state.runner.hp} kills=${state.combat.kills} arenasCleared=${state.level.arenas.count { it.cleared }}")
    state.zoomBy(2.2f)
    run(10)
    save("fight-zoom")
    state.resetZoom()
    run(90)
    state.tap(Offset(w * 0.6f, h * 0.35f))
    run(2)
    save("lightning")
    println("$name: runner x=${state.runner.x.toInt()} of ${state.level.exitX.toInt()}, data ${state.shardsTaken}/${state.level.shards.size}")
    scene.close()
}

/** CPU cost of recording one frame of the scene (what the UI thread pays before the GPU). */
private fun perf(w: Int, h: Int, density: Float) {
    val d = Density(density)
    val state = NeonState()
    state.resize(w.toFloat(), h.toFloat())
    state.autopilot = true
    val texts = TextCache(TextMeasurer(createFontFamilyResolver(), d, LayoutDirection.Ltr), FontFamily.Monospace)
    fun frame() {
        state.update(1f / 60f)
        val recorder = PictureRecorder()
        val canvas = recorder.beginRecording(Rect.makeWH(w.toFloat(), h.toFloat())).asComposeCanvas()
        CanvasDrawScope().draw(d, LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) { drawCity(state, texts) }
        recorder.finishRecordingAsPicture().close()
    }
    repeat(300) { frame() } // warm up the JIT
    val start = System.nanoTime()
    val n = 3000
    repeat(n) { frame() }
    val ms = (System.nanoTime() - start) / 1e6 / n
    println("perf ${w}x$h: %.2f ms per frame to update + record the scene".format(ms))
}
