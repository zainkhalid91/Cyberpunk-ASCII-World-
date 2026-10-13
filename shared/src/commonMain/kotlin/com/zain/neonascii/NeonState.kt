package com.zain.neonascii

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.zain.neonascii.audio.Synth
import com.zain.neonascii.city.Bolt
import com.zain.neonascii.city.City
import com.zain.neonascii.city.NeonCyan
import com.zain.neonascii.city.Rain
import com.zain.neonascii.city.makeCars
import com.zain.neonascii.game.DecorKind
import com.zain.neonascii.game.Input
import com.zain.neonascii.game.Level
import com.zain.neonascii.game.Runner
import com.zain.neonascii.game.RunnerEvents
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class ViewMode(val label: String) { Ascii("ASCII"), Split("SPLIT"), Raw("RAW") }

/** Cells across the short side of the screen, from fine to chunky. */
val CellPresets = floatArrayOf(132f, 100f, 72f)

class SteamPuff(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var maxLife: Float)

class Burst(val x: Float, val y: Float, val color: Color, val life: Float) { var age = 0f }

/** Zoom limits: 1 = the full street; higher magnifies the ASCII image (world and glyph cells together). */
const val MIN_ZOOM = 1f
const val MAX_ZOOM = 6f
/** Lights the ASCII pass can take (must match the shader's array). */
const val MAX_LIGHTS = 12

class NeonState {
    val city = City()
    /** Cached bitmaps of static scenery. */
    val sprites = com.zain.neonascii.city.SpriteCache()
    val level = Level()
    val runner = Runner(level.startX)
    val input = Input()
    private val rnd = Random(42)

    // Compose-observed state.
    var frame by mutableLongStateOf(0L)
    var mode by mutableStateOf(ViewMode.Ascii)
    var hudVisible by mutableStateOf(true)
    var soundOn by mutableStateOf(true)
    var cellPreset by mutableIntStateOf(0)
    var fpsShown by mutableIntStateOf(0)
    var shardsTaken by mutableIntStateOf(0)
    // HUD values, mirrored from the simulation when they change.
    var hpShown by mutableIntStateOf(com.zain.neonascii.game.MAX_HP)
    var killsShown by mutableIntStateOf(0)
    var comboShown by mutableIntStateOf(0)
    var bannerShown by mutableStateOf("")
    var arenaShown by mutableStateOf("")
    var downed by mutableStateOf(false)
    /** Screen shake offset in pixels, applied to the ASCII layers. */
    var shakeX = 0f; private set
    var shakeY = 0f; private set
    /** Current zoom x10, observed by the HUD and the glyph-cell size. */
    var zoomShown by mutableIntStateOf(10)
    var progress by mutableIntStateOf(0)
    var cleared by mutableStateOf(false)
    var autopilot by mutableStateOf(false)
    /** Prints the frame rate to stdout every few seconds (desktop perf checks). */
    var logFps = false

    // Simulation state, read during draw.
    var t = 0f; private set
    /** Left edge of the view in level u. */
    var camera = 0f; private set
    var width = 0f; private set
    var height = 0f; private set
    var zoom = 1f; private set
    private var groundShift = 0f

    /**
     * 1u = 1/1000 of the height (never so big that a portrait phone sees less than ~720u of street),
     * times the zoom. The glyph cells grow by the same zoom (see App), so zooming magnifies the
     * characters themselves.
     */
    val u: Float get() = min(height / 1000f, width / 720f) * zoom
    /** The street line. Zoomed in, the view also follows the runner up onto platforms. */
    val groundY: Float get() = height * (if (height > width) 0.8f else 0.78f) + groundShift
    /** [u] rounded to steps, so cached scenery is rebuilt only a few times while zooming. */
    val spriteU: Float get() = kotlin.math.exp(kotlin.math.round(kotlin.math.ln(u) / 0.14f) * 0.14f)

    /** Zoomed in, the camera is free: it stays where the user zoomed or panned instead of following. */
    val freeCamera: Boolean get() = zoom > 1.001f
    private val baseGround: Float get() = height * (if (height > width) 0.8f else 0.78f)

    /** Zooms by [factor] keeping the world point under [focus] (screen pixels) fixed. */
    fun zoomAt(factor: Float, focus: Offset) {
        if (width <= 0f) return
        val oldU = u
        val wx = camera + focus.x / oldU
        val wy = (groundY - focus.y) / oldU
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (zoom < 1.02f) zoom = 1f
        zoomShown = (zoom * 10f + 0.5f).toInt()
        if (!freeCamera) return // back at 1x: the follow camera eases home
        camera = wx - focus.x / u
        groundShift = focus.y + wy * u - baseGround
    }

    fun zoomBy(factor: Float) = zoomAt(factor, Offset(width / 2f, height / 2f))
    fun resetZoom() { zoom = 1f; zoomShown = 10 }

    /** Drags the free camera (screen pixels). */
    fun pan(dx: Float, dy: Float) {
        if (!freeCamera) return
        camera -= dx / u
        groundShift += dy
    }
    val viewWidth: Float get() = width / u

    var pointer: Offset? = null
    var pointerIsTouch = false
    var splitX = -1f

    val cars = makeCars(rnd)
    /** Crowd, sky-train, searchlight drone, fog, puddles, drips and flyers. */
    val ambience = com.zain.neonascii.city.Ambience()
    val bolts = mutableListOf<Bolt>()
    var flash = 0f; private set
    var shock = Offset.Zero; private set
    var shockAge = -1f; private set
    var glitch = 0f; private set
    private var glitchLeft = 0.7f
    private var glitchAmp = 1f
    private var nextGlitch = 9f
    private var nextLightning = 14f
    val rain = Rain()
    var rainAmount = 1f; private set
    /** 0..1 cloudburst after a strike: heavier rain on screen and in the heavy-rain recording. */
    var downpour = 0f; private set
    // Sheet lightning: a flicker inside the clouds with no bolt.
    var sheetX = 0f; private set
    var sheetAge = 9f; private set
    private var sheetPower = 0f
    private var nextSheet = 25f
    private var lastDrip = 0f

    val steam = List(140) { SteamPuff(0f, 0f, 0f, 0f, 0f, 1f) }
    private var steamNext = 0
    private var steamClock = 0f
    val bursts = mutableListOf<Burst>()

    /** Screen-space canopy over the runner, written while drawing and used to stop the rain. */
    var canopyCenter = Offset(-9999f, -9999f)
    val canopyRadius: Float get() = 52f * com.zain.neonascii.game.RUNNER_SCALE * u

    private var clearedAt = 0f
    private var autoJumpHold = 0f
    private var fpsAcc = 0f
    private var fpsFrames = 0
    private var fpsLogClock = 0f

    var synth: Synth? = null

    /** Lights for the ASCII pass: x, y, radius in screen pixels. Away from them the city goes dark. */
    val lights = FloatArray(MAX_LIGHTS * 3)
    var lightCount = 0; private set
    /** Base light level; lightning briefly reveals the whole city. */
    var ambient = 0.22f; private set

    private val events = object : RunnerEvents {
        override fun onStep(loudness: Float) {
            synth?.step(loudness)
            if (runner.y < 2f) reactive.step(this@NeonState, runner.x + runner.facing * 6f, loudness)
            rain.splash(screenX(runner.x + runner.facing * 6f), groundY - runner.y * u, 0.9f)
        }

        override fun onJump(double: Boolean) {
            if (double) bursts += Burst(runner.x, runner.y, NeonCyan, 0.4f)
        }

        override fun onSwing(combo: Int) {
            synth?.swing(combo)
        }

        override fun onShoot() {
            synth?.pew()
            combat.fireBolt()
        }

        override fun onDash() {
            synth?.dash()
        }

        override fun onLand(power: Float) {
            synth?.land(power)
            if (runner.y < 2f) reactive.step(this@NeonState, runner.x, 1f + power)
            val sx = screenX(runner.x)
            val sy = groundY - runner.y * u
            repeat(3) { rain.splash(sx + (it - 1) * 16f * u, sy, 1f) }
        }
    }

    private fun panOf(levelX: Float) = (screenX(levelX) / width * 2f - 1f).coerceIn(-1f, 1f)

    private val combatEvents = object : com.zain.neonascii.game.CombatEvents {
        override fun enemyShot(x: Float, orb: Boolean) { synth?.enemyShot(panOf(x), orb) }
        override fun enemyHit(x: Float, heavy: Boolean) { synth?.hit(panOf(x), heavy) }
        override fun clang(x: Float) { synth?.clang(panOf(x)) }
        override fun deflect(x: Float) { synth?.deflect(panOf(x)) }
        override fun playerHurt(x: Float) { synth?.hurt(); glitchLeft = 0.12f; glitchAmp = 0.6f }
        override fun enemyDown(x: Float, y: Float) {
            synth?.explode(panOf(x))
            bursts += Burst(x, y, Color(0xFFFF8A3D), 0.45f)
        }
        override fun stomp(x: Float) { synth?.stomp(panOf(x)) }
        override fun heal() { synth?.pickup() }
        override fun barrier(locked: Boolean) { synth?.barrier(locked) }
        override fun warpIn(x: Float, y: Float) { bursts += Burst(x, y, NeonCyan, 0.4f) }
    }

    /** Enemies, bullets and fight scenes. */
    val combat = com.zain.neonascii.game.CombatWorld(level, runner, combatEvents)

    /** Weeds, loose cables, cans and footstep ripples that react to the runner. */
    val reactive = com.zain.neonascii.game.Reactive(level, object : com.zain.neonascii.game.ReactiveEvents {
        override fun clink(x: Float, strength: Float) { synth?.clink(panOf(x), strength) }
    })
    private var deathT = -1f

    fun screenX(levelX: Float) = (levelX - camera) * u

    fun resize(w: Float, h: Float) {
        if (w == width && h == height) return
        width = w; height = h
        if (splitX < 0f || splitX > w) splitX = w / 2f
        rain.resize(w, h, u)
        camera = targetCamera()
    }

    private fun targetCamera(): Float {
        val lead = viewWidth * (0.36f - 0.06f * runner.facing)
        return (runner.x - lead).coerceIn(0f, level.length - viewWidth)
    }

    fun update(dtRaw: Float) {
        if (width <= 0f) return
        t += dtRaw
        val u = this.u

        fpsAcc += dtRaw; fpsFrames++
        if (fpsAcc >= 0.5f) { fpsShown = (fpsFrames / fpsAcc + 0.5f).toInt(); fpsAcc = 0f; fpsFrames = 0 }
        if (logFps) { fpsLogClock += dtRaw; if (fpsLogClock > 3f) { fpsLogClock = 0f; println("fps=$fpsShown x=${runner.x.toInt()} shards=$shardsTaken") } }

        // ---- the runner and the fight, with hit-stop (freeze frames) and slow motion
        if (autopilot) drive(dtRaw)
        var simDt = dtRaw
        if (combat.hitStop > 0f) { combat.hitStop -= dtRaw; simDt = 0f }
        if (combat.slowMo > 0f) { combat.slowMo -= dtRaw; simDt *= 0.3f }
        if (!cleared && simDt > 0f) {
            val steps = if (simDt > 1f / 90f) 2 else 1
            repeat(steps) { runner.update(simDt / steps, input, level, t, events) }
            combat.update(simDt, t)
        }
        reactive.update(this, simDt)
        // Screen shake decays quickly.
        combat.shake = (combat.shake - dtRaw * 60f).coerceAtLeast(0f)
        shakeX = (rnd.nextFloat() - 0.5f) * 2f * combat.shake * u
        shakeY = (rnd.nextFloat() - 0.5f) * 2f * combat.shake * u
        // Death: slow fade, then back to the last checkpoint with the fight reset.
        if (runner.dead && deathT < 0f) { deathT = 0f; glitchLeft = 0.6f; glitchAmp = 1f }
        if (deathT >= 0f) {
            deathT += dtRaw
            if (deathT > 1.8f) {
                deathT = -1f
                combat.restartArena()
                val cp = level.checkpoints.filter { it <= runner.x }.maxOrNull() ?: level.startX
                runner.respawn(cp)
                camera = targetCamera()
                glitchLeft = 0.4f; glitchAmp = 1f
            }
        }
        downed = deathT >= 0f
        if (hpShown != runner.hp.coerceAtLeast(0)) hpShown = runner.hp.coerceAtLeast(0)
        if (killsShown != combat.kills) killsShown = combat.kills
        if (comboShown != combat.combo) comboShown = combat.combo
        val banner = if (combat.bannerT > 0f) combat.bannerText else ""
        if (bannerShown != banner) bannerShown = banner
        val arena = combat.activeArena?.let { "ARENA · WAVE ${combat.wave + 1}/${it.waves.size}" } ?: ""
        if (arenaShown != arena) arenaShown = arena
        // At 1x the camera follows the runner; zoomed in it is free (see zoomAt / pan).
        val prevCam = camera
        if (!freeCamera) {
            groundShift += (0f - groundShift) * min(1f, dtRaw * 6f)
            camera += (targetCamera() - camera) * min(1f, dtRaw * 5f)
        }
        val camDelta = camera - prevCam

        for (sh in level.shards) {
            if (sh.taken) continue
            val dx = sh.x - runner.x
            val dy = sh.y - (runner.y + 100f)
            if (dx * dx + dy * dy < 70f * 70f) {
                sh.taken = true; sh.takenAt = t
                shardsTaken++
                synth?.pickup()
            }
        }
        progress = ((runner.x - level.startX) / (level.exitX - level.startX) * 100f).toInt().coerceIn(0, 100)
        if (!cleared && runner.x > level.exitX) {
            cleared = true; clearedAt = t
            glitchLeft = 0.35f; glitchAmp = 1f
            synth?.boom()
        }
        if (cleared && t - clearedAt > 4.5f) {
            cleared = false
            level.reset(); shardsTaken = 0
            combat.reset()
            reactive.reset()
            runner.respawn(level.startX)
            camera = targetCamera()
            glitchLeft = 0.5f; glitchAmp = 1f
        }

        // ---- weather
        downpour = (downpour - dtRaw / 16f).coerceAtLeast(0f)
        rainAmount = 0.8f + 0.2f * sin(t * 0.05f) + downpour * 0.5f
        rain.wind = 0.08f + 0.04f * sin(t * 0.11f) + ambience.gust * 0.2f
        rain.update(dtRaw, u, groundY, rainAmount, canopyCenter, canopyRadius, camDelta * u)
        rain.drainSplashes { x, depth ->
            val pan = x / width * 2f - 1f
            if (depth > 0.8f && abs(pan) < 0.8f && t - lastDrip > 0.12f) {
                lastDrip = t
                synth?.drip(pan, (depth - 0.8f) * 5f)
            }
        }

        ambience.update(this, dtRaw, camDelta)

        // ---- steam from street vents near the view
        steamClock += dtRaw
        while (steamClock > 0.07f) {
            steamClock -= 0.07f
            for (d in level.decor) {
                if (d.kind != DecorKind.Vent || d.x < camera - 200f || d.x > camera + viewWidth + 200f) continue
                if (rnd.nextFloat() > 0.5f) continue
                val p = steam[steamNext]; steamNext = (steamNext + 1) % steam.size
                p.x = d.x + (rnd.nextFloat() - 0.5f) * 40f; p.y = 4f
                p.vx = (rnd.nextFloat() - 0.3f) * 30f; p.vy = 70f + rnd.nextFloat() * 50f
                p.life = 2.4f + rnd.nextFloat(); p.maxLife = p.life
            }
        }
        // Steam parts around the runner and is thrown aside by a dash.
        val bodyX = runner.x
        val bodyY = runner.y + 90f
        val shove = if (runner.dashT > 0f) 2600f else 700f
        for (p in steam) {
            if (p.life <= 0f) continue
            p.life -= dtRaw
            val dx = p.x - bodyX
            val dy = p.y - bodyY
            val d2 = dx * dx + dy * dy
            if (d2 < 140f * 140f && d2 > 1f) {
                val d = kotlin.math.sqrt(d2)
                val k = (1f - d / 140f) * shove * dtRaw
                p.vx += dx / d * k + runner.vx * 0.4f * k / shove
                p.vy += dy / d * k * 0.5f
            }
            p.vx *= 1f - minOf(1f, dtRaw * 0.8f)
            p.x += (p.vx + rain.wind * 120f) * dtRaw
            p.y += p.vy * dtRaw
        }
        bursts.forEach { it.age += dtRaw }
        bursts.removeAll { it.age >= it.life }

        // ---- flying cars drift with the view
        val wu = viewWidth
        for (c in cars) {
            c.x += c.speed * c.dir * dtRaw - camDelta * 0.5f
            if (c.x > wu + 160f) c.x = -160f
            if (c.x < -160f) c.x = wu + 160f
        }

        // ---- lightning
        if (t > nextLightning) {
            nextLightning = t + 40f + rnd.nextFloat() * 30f
            val x = width * (0.1f + rnd.nextFloat() * 0.8f)
            strike(Offset(x, groundY - (300f + rnd.nextFloat() * 300f) * u), 0.55f + rnd.nextFloat() * 0.45f)
        }
        sheetAge += dtRaw
        if (t > nextSheet) {
            nextSheet = t + 22f + rnd.nextFloat() * 20f
            sheetX = width * (0.1f + rnd.nextFloat() * 0.8f)
            sheetAge = 0f
            sheetPower = 0.5f + rnd.nextFloat() * 0.5f
            synth?.thunder(sheetPower * 0.2f, 1.2f + rnd.nextFloat() * 1.5f, (sheetX / width * 2f - 1f))
        }
        flash = 0f
        bolts.forEach { it.age += dtRaw; flash = maxOf(flash, it.intensity) }
        bolts.removeAll { it.age > 0.55f }

        // ---- shockwave and glitch
        if (shockAge >= 0f) { shockAge += dtRaw; if (shockAge > 1.4f) shockAge = -1f }
        if (t > nextGlitch) {
            nextGlitch = t + 7f + rnd.nextFloat() * 9f
            glitchLeft = 0.1f + rnd.nextFloat() * 0.22f
            glitchAmp = 0.5f + rnd.nextFloat() * 0.5f
        }
        glitchLeft -= dtRaw
        glitch = if (glitchLeft > 0f) glitchAmp else 0f

        updateLights()

        synth?.let { s ->
            s.enabled = soundOn
            s.rainAmount = rainAmount
            s.downpour = downpour
            s.trainAmount = ambience.trainPresence
            s.trainPan = ambience.trainPan
        }
    }

    private fun addLight(x: Float, y: Float, radius: Float): Boolean {
        if (lightCount >= MAX_LIGHTS) return false
        if (x < -radius || x > width + radius || y < -radius || y > height + radius) return true
        lights[lightCount * 3] = x; lights[lightCount * 3 + 1] = y; lights[lightCount * 3 + 2] = radius
        lightCount++
        return true
    }

    private fun updateLights() {
        val u = u
        lightCount = 0
        ambient = (0.22f + flash * 0.95f + sheetIntensity * 0.35f).coerceAtMost(1f)
        // The runner's visor and canopy act as a lantern.
        addLight(screenX(runner.x), groundY - (runner.y + 110f) * u, 380f * u)
        val sab = com.zain.neonascii.game.saberPoints(runner)
        addLight(screenX(sab[0]), groundY - sab[1] * u, 240f * u)
        if (level.exitX - camera < viewWidth + 400f) addLight(screenX(level.exitX), groundY - 200f * u, 420f * u)
        // The searchlight pool and the passing sky-train light up their surroundings.
        if (ambience.copActive) addLight(screenX(ambience.spotX), groundY - 40f * u, 300f * u)
        if (ambience.trainActive && ambience.trainPresence > 0f) {
            val tx = ((1f + ambience.trainPan) * 0.5f) * width
            addLight(tx, groundY - 500f * u, 520f * u * ambience.trainPresence + 1f)
        }
        for (d in level.decor) {
            if (d.kind != DecorKind.RamenStall && d.kind != DecorKind.Storefront) continue
            if (d.x + d.w < camera - 200f || d.x > camera + viewWidth + 200f) continue
            addLight(screenX(d.x + d.w / 2f), groundY - 130f * u, 260f * u)
        }
        val ww = city.worldWidth
        for (lamp in city.lamps) {
            var sxU = ((lamp.x - camera) % ww + ww) % ww
            if (sxU > viewWidth + 400f) sxU -= ww
            if (sxU < -200f || sxU > viewWidth + 200f) continue
            if (!addLight((sxU + 32f) * u, groundY - 200f * u, 260f * u)) break
        }
        for (sh in level.shards) {
            if (sh.taken || sh.x < camera || sh.x > camera + viewWidth) continue
            if (!addLight(screenX(sh.x), groundY - sh.y * u, 120f * u)) break
        }
    }

    /** Demo driver: runs right, hops obstacles, climbs toward shards and fights. Toggle with P. */
    private fun drive(dt: Float) {
        input.left = false
        input.right = true
        autoJumpHold -= dt
        val r = runner
        val ahead = level.solids.any { it.x - r.x in 10f..110f && it.top > r.y + 10f && it.bottom < r.y + r.height }
        val ledgeAhead = level.ledges.any { it.x - r.x in 20f..130f && it.yAt(t) - r.y in 60f..320f }
        val shardAbove = level.shards.any { !it.taken && abs(it.x - r.x) < 90f && it.y - r.y > 140f }
        if (r.onGround && (ahead || ledgeAhead || shardAbove)) { input.pressJump(); autoJumpHold = 0.3f }
        else if (!r.onGround && r.vy < 50f && (ahead || shardAbove)) { input.releaseJump(); input.pressJump(); autoJumpHold = 0.25f }
        if (autoJumpHold <= 0f) input.releaseJump()
        // Fight: slash what is close (combos come from repeated presses), shoot what is far,
        // swing at incoming bolts to deflect them, and hop over leaping hounds.
        val foe = combat.nearestAhead(760f)
        if (foe != null) {
            val dx = (foe.x - r.x) * r.facing
            val dy = foe.centerY - (r.y + 100f)
            if (dx < 120f && combat.activeArena != null) input.right = false
            if (dx in -40f..170f && abs(dy) < 150f) input.attackPressed = true
            else if (dx in 170f..760f && abs(dy) < 90f && r.aimT <= 0f) input.shootPressed = true
            if (foe.kind == com.zain.neonascii.game.EnemyKind.Hound && foe.state == com.zain.neonascii.game.EState.Leap && r.onGround) { input.pressJump(); autoJumpHold = 0.3f }
        } else if (combat.activeArena != null) {
            // Nothing ahead but the arena is still live: turn around.
            val behind = combat.enemies.firstOrNull { it.alive && it.arena == combat.activeArena }
            if (behind != null && behind.x < r.x) { input.right = false; input.left = true }
        }
        if (combat.bullets.any { !it.reflected && abs(it.x - r.x) < 150f && abs(it.y - (r.y + 100f)) < 120f }) input.attackPressed = true
    }

    fun tap(p: Offset) {
        shock = p
        shockAge = 0f
        glitchLeft = 0.16f; glitchAmp = 0.8f
        strike(Offset(p.x, minOf(p.y, groundY - 120f * u)), 1f)
        synth?.boom()
    }

    fun strikeRandom() = tap(Offset(width * (0.15f + rnd.nextFloat() * 0.7f), groundY - 400f * u))

    /** Sheet-lightning brightness right now: a few stuttering pulses over 0.7 s. */
    val sheetIntensity: Float
        get() {
            if (sheetAge > 0.7f) return 0f
            val on = com.zain.neonascii.city.hash((sheetX).toInt(), (sheetAge * 18f).toInt(), 4) < 0.55f
            return (if (on) 1f else 0.15f) * (1f - sheetAge / 0.7f) * sheetPower
        }

    /** How brightly lightning lights a cloud at screen x (bolts burst out of the clouds above them). */
    fun cloudLight(x: Float): Float {
        var light = flash * 0.25f
        for (b in bolts) {
            val d = (x - b.main[0].x) / (width * 0.28f)
            light += b.intensity * kotlin.math.exp(-d * d)
        }
        val sheet = sheetIntensity
        if (sheet > 0f) {
            val d = (x - sheetX) / (width * 0.2f)
            light += sheet * kotlin.math.exp(-d * d)
        }
        return light.coerceAtMost(1.2f)
    }

    private fun strike(target: Offset, power: Float) {
        // Bolts leave from the cloud base.
        val from = Offset(target.x + (rnd.nextFloat() - 0.5f) * width * 0.3f, groundY * (0.14f + rnd.nextFloat() * 0.12f))
        downpour = (downpour + 0.5f * power).coerceAtMost(1f)
        bolts += Bolt.create(from, target, u, power, rnd)
        val pan = (target.x / width * 2f - 1f).coerceIn(-1f, 1f)
        synth?.thunder(power, delaySeconds = 0.15f + (1f - power) * 1.8f, pan = pan)
    }

    /** Called while drawing when a visible neon sign stutters: it may shed sparks. */
    fun onSignFlicker(x: Float, y: Float) = ambience.signSpark(x, y, u)

    fun cycleMode() { mode = ViewMode.entries[(mode.ordinal + 1) % ViewMode.entries.size] }
    fun cycleCells() { cellPreset = (cellPreset + 1) % CellPresets.size }
}
