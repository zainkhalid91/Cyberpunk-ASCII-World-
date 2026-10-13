package com.zain.neonascii.city

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.zain.neonascii.NeonState
import com.zain.neonascii.game.LedgeKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Street life and weather that make the city feel inhabited: a crowd under glowing umbrellas, an
// elevated sky-train, a police searchlight drone, ground fog, puddles, drips from every awning and
// flyers caught in the wind. None of it touches gameplay.

private val Clear = Color(0x00000000)
private val CoatColors = listOf(Color(0xFF2A2240), Color(0xFF332838), Color(0xFF1F2A3A), Color(0xFF3A2A2A))
private val UmbrellaColors = listOf(NeonCyan, NeonPink, NeonAmber, Color(0xFFE6EEFF), NeonViolet, NeonLime)
private val FogColor = Color(0xFF8C9CC8)
private val WindowWarm = Color(0xFFFFE2A8)
private val DropColor = Color(0xFFCFE6FF)

/** A pedestrian on the far side of the street, in level u. [umbrella] null = hood and visor instead. */
class Walker(var x: Float, var dir: Int, var speed: Float, val scale: Float, val umbrella: Color?, val coat: Color, val seed: Int) {
    var phase = 0f
    /** Some people stop for a while under shop lights. */
    var pause = 0f
}

/** A drop falling from a ledge edge, in level u. */
private class Drip { var x = 0f; var y = 0f; var vy = 0f; var alive = false }

/** A flyer tumbling in the wind, in screen pixels. */
private class Flyer { var x = 0f; var y = 0f; var vx = 0f; var vy = 0f; var spin = 0f; var angle = 0f; var color = Color.White; var alive = false }

class Ambience(seed: Int = 909) {
    private val rnd = Random(seed)
    val walkers = List(16) {
        Walker(
            x = 300f + rnd.nextFloat() * 1800f,
            dir = if (rnd.nextBoolean()) 1 else -1,
            speed = 55f + rnd.nextFloat() * 40f,
            scale = 0.62f + rnd.nextFloat() * 0.22f,
            umbrella = if (rnd.nextFloat() < 0.75f) UmbrellaColors[rnd.nextInt(UmbrellaColors.size)] else null,
            coat = CoatColors[rnd.nextInt(CoatColors.size)],
            seed = rnd.nextInt(),
        )
    }

    // ---- sky-train on an elevated track, at its own parallax depth
    val trainParallax = 0.72f
    val trainScale = 0.8f
    val trainCars = 6
    val carLen = 230f
    /** Train nose in u from the screen's left edge (parallax space); null = no train passing. */
    var trainX = 0f; private set
    var trainDir = 1; private set
    var trainActive = false; private set
    private var nextTrain = 6f
    private val trainSpeed = 430f
    /** 0..1 how much of the train is on screen, for the rumble. */
    var trainPresence = 0f; private set
    var trainPan = 0f; private set
    var sparkT = -1f; private set
    var sparkX = 0f; private set

    // ---- police searchlight drone crossing the play plane
    var copActive = false; private set
    var copX = 0f; private set
    var copDir = 1; private set
    private var nextCop = 18f
    /** Where the searchlight lands on the street, in level u. */
    var spotX = 0f; private set

    // ---- wind gusts
    var gust = 0f; private set
    private var gustT = -1f
    private var nextGust = 10f

    // Sparks shed by stuttering neon signs, in screen pixels.
    private val spX = FloatArray(48); private val spY = FloatArray(48)
    private val spVx = FloatArray(48); private val spVy = FloatArray(48)
    private val spLife = FloatArray(48)
    private var spNext = 0

    /** A visible sign stuttered at screen ([x], [y]): sometimes it spits a few sparks. */
    fun signSpark(x: Float, y: Float, u: Float) {
        if (rnd.nextFloat() > 0.06f) return
        repeat(2 + rnd.nextInt(3)) {
            val i = spNext; spNext = (spNext + 1) % spX.size
            spX[i] = x; spY[i] = y
            spVx[i] = (rnd.nextFloat() - 0.5f) * 260f * u
            spVy[i] = -rnd.nextFloat() * 120f * u
            spLife[i] = 0.8f + rnd.nextFloat() * 0.6f
        }
    }

    private val drips = List(120) { Drip() }
    private var dripNext = 0
    private var dripClock = 0f
    private val flyers = List(14) { Flyer() }
    private var rippleClock = 0f

    /** Fog banks: x in u (wrapping), y in u above the street, half sizes, drift speed, parallax. */
    private val fog = List(16) {
        val front = it >= 11
        floatArrayOf(rnd.nextFloat() * 3000f, if (front) -20f + rnd.nextFloat() * 50f else 10f + rnd.nextFloat() * 90f,
            220f + rnd.nextFloat() * 260f, 40f + rnd.nextFloat() * 50f, 8f + rnd.nextFloat() * 14f, if (front) 1.3f else 0.9f)
    }

    /** Puddles on the street: level x, width, depth below the street line (u), shimmer colour. */
    val puddles: List<FloatArray>
    val puddleColors: List<Color>

    init {
        val list = mutableListOf<FloatArray>()
        val cols = mutableListOf<Color>()
        var x = 350f
        while (x < 20000f) {
            list += floatArrayOf(x, 90f + rnd.nextFloat() * 160f, 14f + rnd.nextFloat() * 60f)
            cols += UmbrellaColors[rnd.nextInt(4)]
            x += 280f + rnd.nextFloat() * 420f
        }
        puddles = list; puddleColors = cols
    }

    fun update(s: NeonState, dt: Float, camDelta: Float) {
        val view = s.viewWidth
        val cam = s.camera
        val mid = cam + view / 2f

        // ---- gusts: a swell of wind every so often that tilts the rain and throws flyers about
        if (s.t > nextGust) { nextGust = s.t + 14f + rnd.nextFloat() * 16f; gustT = 0f }
        if (gustT >= 0f) {
            gustT += dt
            gust = sin((gustT / 4.5f).coerceIn(0f, 1f) * PI.toFloat())
            if (gustT > 4.5f) { gustT = -1f; gust = 0f }
            if (rnd.nextFloat() < gust * dt * 5f) spawnFlyer(s)
        }

        // ---- crowd: walk, pause under lights, scatter from fights, recycle at the view edges
        val fight = s.combat.activeArena != null
        for (w in walkers) {
            val away = if (w.x < s.runner.x) -1 else 1
            val scared = fight && abs(w.x - s.runner.x) < 1100f
            if (scared) { w.dir = away; w.pause = 0f }
            if (w.pause > 0f) { w.pause -= dt; continue }
            val v = w.speed * (if (scared) 2.8f else 1f)
            w.x += v * w.dir * dt
            w.phase += v * dt / (34f * w.scale)
            if (!scared && rnd.nextFloat() < dt * 0.04f) w.pause = 2f + rnd.nextFloat() * 4f
            if (abs(w.x - mid) > view / 2f + 500f) {
                // Re-enter from the far side of the view, walking inward.
                val side = if (w.x < mid) 1 else -1
                w.x = mid + side * (view / 2f + 200f + rnd.nextFloat() * 250f)
                w.dir = -side
                if (fight && abs(w.x - s.runner.x) < 1100f) w.x += side * 1200f
            }
        }

        // ---- sky-train
        val pview = view + 200f
        val len = trainCars * carLen * trainScale
        if (!trainActive && s.t > nextTrain) {
            trainActive = true
            trainDir = if (rnd.nextBoolean()) 1 else -1
            trainX = if (trainDir > 0) -60f else pview + 60f
        }
        if (trainActive) {
            trainX += trainSpeed * trainDir * dt - camDelta * trainParallax
            val tail = trainX - trainDir * len
            val left = minOf(trainX, tail); val right = maxOf(trainX, tail)
            val onScreen = (minOf(right, view) - maxOf(left, 0f)).coerceAtLeast(0f)
            trainPresence = (onScreen / minOf(len, view)).coerceIn(0f, 1f)
            trainPan = (((left + right) / 2f) / view * 2f - 1f).coerceIn(-1f, 1f)
            if (sparkT < 0f && onScreen > 0f && rnd.nextFloat() < dt * 0.9f) {
                sparkT = 0f
                sparkX = trainX - trainDir * carLen * trainScale * (0.5f + rnd.nextInt(trainCars))
            }
            if ((trainDir > 0 && left > pview + 100f) || (trainDir < 0 && right < -100f)) {
                trainActive = false; trainPresence = 0f
                nextTrain = s.t + 16f + rnd.nextFloat() * 18f
            }
        }
        if (sparkT >= 0f) {
            sparkT += dt
            sparkX += trainSpeed * trainDir * dt - camDelta * trainParallax
            if (sparkT > 0.35f) sparkT = -1f
        }

        // ---- searchlight drone
        if (!copActive && s.t > nextCop) {
            copActive = true
            copDir = if (rnd.nextBoolean()) 1 else -1
            copX = if (copDir > 0) cam - 300f else cam + view + 300f
        }
        if (copActive) {
            copX += 120f * copDir * dt
            spotX = copX + 260f * sin(s.t * 0.7f) + 80f * sin(s.t * 1.9f)
            if (copX < cam - 600f || copX > cam + view + 600f) {
                copActive = false
                nextCop = s.t + 22f + rnd.nextFloat() * 20f
            }
        }

        // ---- drips from the edges of awnings, fire escapes and catwalks
        dripClock += dt
        while (dripClock > 0.05f) {
            dripClock -= 0.05f
            for (l in s.level.ledges) {
                if (l.kind == LedgeKind.HoverPad || l.right < cam - 50f || l.x > cam + view + 50f) continue
                if (rnd.nextFloat() > 0.22f) continue
                val d = drips[dripNext]; dripNext = (dripNext + 1) % drips.size
                d.x = if (rnd.nextBoolean()) l.x + 4f else l.right - 4f
                d.y = l.yAt(s.t) - 4f
                d.vy = 0f; d.alive = true
            }
        }
        for (d in drips) {
            if (!d.alive) continue
            d.vy -= 1400f * dt
            d.y += d.vy * dt
            if (d.y <= 0f) {
                d.alive = false
                s.rain.splash(s.screenX(d.x), s.groundY, 0.75f)
            }
        }

        // ---- ripples in puddles
        rippleClock += dt
        while (rippleClock > 0.09f) {
            rippleClock -= 0.09f
            val p = puddles.getOrNull(rnd.nextInt(puddles.size)) ?: continue
            if (p[0] + p[1] < cam || p[0] > cam + view) continue
            val px = s.screenX(p[0] + rnd.nextFloat() * p[1])
            s.rain.splash(px, s.groundY + p[2] * s.u * (0.6f + rnd.nextFloat() * 0.8f), 0.55f)
        }

        // ---- sign sparks: fall, bounce once off the street, die out
        for (i in spX.indices) {
            if (spLife[i] <= 0f) continue
            spLife[i] -= dt
            spVy[i] += 1300f * s.u * dt
            spX[i] += spVx[i] * dt - camDelta * s.u
            spY[i] += spVy[i] * dt
            if (spY[i] > s.groundY && spVy[i] > 0f) { spY[i] = s.groundY; spVy[i] *= -0.35f; spVx[i] *= 0.6f }
        }

        // ---- flyers
        val wind = s.rain.wind
        for (f in flyers) {
            if (!f.alive) continue
            f.vx += ((wind * 900f + gust * 520f) * s.u - f.vx) * dt * 1.5f
            f.vy += (60f * s.u - f.vy) * dt + sin(s.t * 3f + f.spin) * 40f * s.u * dt
            f.x += f.vx * dt - camDelta * s.u
            f.y += f.vy * dt
            f.angle += f.spin * dt * (0.5f + gust)
            if (f.y > s.groundY + 20f * s.u) { f.vy = 0f; f.y = s.groundY + 20f * s.u; f.vx *= 0.9f }
            if (f.x > s.width + 80f || f.x < -200f) f.alive = false
        }
    }

    private fun spawnFlyer(s: NeonState) {
        val f = flyers.firstOrNull { !it.alive } ?: return
        f.alive = true
        f.x = -40f
        f.y = s.groundY - (60f + rnd.nextFloat() * 520f) * s.u
        f.vx = 300f * s.u; f.vy = 0f
        f.spin = (rnd.nextFloat() - 0.5f) * 14f
        f.angle = rnd.nextFloat() * 6f
        f.color = if (rnd.nextFloat() < 0.6f) Color(0xFFE8E4FF) else UmbrellaColors[rnd.nextInt(4)]
    }

    // ------------------------------------------------------------------ drawing

    /** Elevated track and the sky-train, between the mid skyline and the street. */
    fun drawTrain(scope: DrawScope, s: NeonState) = with(scope) {
        val u = s.u
        val sc = trainScale * u
        val railY = s.groundY - 470f * u
        // Track: a girder with pillars at its own parallax.
        drawRect(Color(0xFF130C26), Offset(0f, railY), Size(size.width, 16f * sc))
        drawRect(NeonViolet, Offset(0f, railY + 16f * sc), Size(size.width, 1.5f * u), alpha = 0.35f)
        val spacing = 520f
        val shift = (s.camera * trainParallax) % spacing
        var px = -shift
        while (px * u < size.width + 40f * u) {
            val x = px * u
            drawRect(Color(0xFF110A22), Offset(x - 9f * sc, railY), Size(18f * sc, s.groundY - railY))
            drawRect(Color(0xFF110A22), Offset(x - 26f * sc, railY + 16f * sc), Size(52f * sc, 10f * sc))
            val blink = ((s.t * 0.9f + px * 0.013f) % 1.4f) < 0.18f
            if (blink) { glow(NeonRed, Offset(x, railY + 30f * sc), 16f * u, 0.7f); drawCircle(NeonRed, 2.2f * u, Offset(x, railY + 30f * sc)) }
            px += spacing
        }
        if (!trainActive) return@with
        val carH = 52f * sc
        val top = railY - carH - 4f * sc
        for (k in 0 until trainCars) {
            val nose = trainX - trainDir * k * carLen * trainScale
            val x0 = minOf(nose, nose - trainDir * (carLen - 12f) * trainScale) * u
            val cw = (carLen - 12f) * sc
            if (x0 > size.width || x0 + cw < 0f) continue
            drawRoundRect(Color(0xFF221A3E), Offset(x0, top), Size(cw, carH), CornerRadius(10f * sc))
            drawRect(NeonCyan, Offset(x0 + 6f * sc, top + 4f * sc), Size(cw - 12f * sc, 2.5f * sc), alpha = 0.8f)
            // Window band with passengers.
            val wy = top + 14f * sc
            val wn = 9
            val ww = (cw - 20f * sc) / wn
            for (i in 0 until wn) {
                val wx = x0 + 10f * sc + i * ww
                val lit = hash(k, i, 41) > 0.12f
                if (lit) {
                    drawRect(WindowWarm, Offset(wx + 2f * sc, wy), Size(ww - 4f * sc, 20f * sc), alpha = 0.85f)
                    if (hash(k, i, 42) < 0.45f) {
                        // A passenger's head and shoulders against the light.
                        val cx = wx + ww * (0.3f + 0.4f * hash(k, i, 43))
                        drawCircle(Color(0xFF1A1226), 4.5f * sc, Offset(cx, wy + 9f * sc))
                        drawRect(Color(0xFF1A1226), Offset(cx - 7f * sc, wy + 13f * sc), Size(14f * sc, 7f * sc))
                    }
                } else drawRect(Color(0xFF0E0A1C), Offset(wx + 2f * sc, wy), Size(ww - 4f * sc, 20f * sc))
            }
            // Light spilling down from the windows onto the track.
            drawRect(Brush.verticalGradient(listOf(WindowWarm.copy(alpha = 0.18f), Clear), startY = railY + 16f * sc, endY = railY + 90f * sc),
                Offset(x0, railY + 16f * sc), Size(cw, 74f * sc))
        }
        // Headlight beam out of the nose.
        val nx = trainX * u
        val ny = top + carH * 0.55f
        val reach = trainDir * 420f * sc
        val beam = Path().apply { moveTo(nx, ny - 4f * sc); lineTo(nx + reach, ny - 40f * sc); lineTo(nx + reach, ny + 46f * sc); close() }
        drawPath(beam, Brush.horizontalGradient(listOf(Color(0x77FFF4D6), Clear), startX = nx, endX = nx + reach))
        glow(Color(0xFFFFF4D6), Offset(nx, ny), 30f * sc, 0.9f)
        // A pantograph spark: a bright stutter on the roof.
        if (sparkT >= 0f && ((sparkT * 30f).toInt() % 2 == 0)) {
            val c = Offset(sparkX * u, top - 8f * sc)
            glow(Color(0xFFBFF6FF), c, 70f * sc, 0.9f)
            drawLine(Color.White, c, c + Offset(10f * sc, -14f * sc), strokeWidth = 2f * u)
            drawLine(Color.White, c, c + Offset(-12f * sc, -8f * sc), strokeWidth = 1.5f * u)
        }
    }

    /** Wet sheen on the street: puddles that hold a smear of neon. */
    fun drawPuddles(scope: DrawScope, s: NeonState) = with(scope) {
        val u = s.u
        val cam = s.camera
        val view = s.viewWidth
        for (i in puddles.indices) {
            val p = puddles[i]
            if (p[0] + p[1] < cam - 50f || p[0] > cam + view + 50f) continue
            val x = s.screenX(p[0]); val w = p[1] * u
            val y = s.groundY + p[2] * u
            val c = puddleColors[i]
            drawOval(Color(0xFF1C2448), Offset(x, y - 7f * u), Size(w, 14f * u), alpha = 0.8f)
            val sh = sin(s.t * 1.6f + i) * 0.5f + 0.5f
            drawLine(c, Offset(x + w * 0.2f, y), Offset(x + w * (0.55f + 0.15f * sh), y), strokeWidth = 2f * u, alpha = 0.45f)
            drawLine(Color.White, Offset(x + w * 0.35f, y - 3f * u), Offset(x + w * 0.5f, y - 3f * u), strokeWidth = 1.2f * u, alpha = 0.35f * sh)
        }
    }

    /**
     * The crowd on the pavement. Drawn on the fine hero grid so they read as characters; the city
     * layer gets only a dark [haloOnly] patch behind each so the busy glyph field gives way.
     */
    fun drawWalkers(scope: DrawScope, s: NeonState, haloOnly: Boolean = false) = with(scope) {
        val u = s.u
        val cam = s.camera
        val view = s.viewWidth
        for (w in walkers) {
            if (w.x < cam - 120f || w.x > cam + view + 120f) continue
            val h = 150f * w.scale * u
            val x = s.screenX(w.x)
            val gy = s.groundY - 6f * u
            if (haloOnly) {
                glowOval(Color(0xFF040010), Offset(x, gy - 0.65f * h), 0.5f * h, 0.75f * h, 0.85f)
                continue
            }
            val swing = if (w.pause > 0f) 0f else sin(w.phase) * 0.17f * h
            val hip = Offset(x, gy - 0.47f * h)
            drawLine(Color(0xFF16101F), hip, Offset(x + swing, gy), strokeWidth = 0.07f * h, cap = StrokeCap.Round)
            drawLine(Color(0xFF16101F), hip, Offset(x - swing, gy), strokeWidth = 0.07f * h, cap = StrokeCap.Round)
            val coat = Path().apply {
                moveTo(x - 0.1f * h, gy - 0.84f * h); lineTo(x + 0.1f * h, gy - 0.84f * h)
                lineTo(x + 0.15f * h, gy - 0.38f * h); lineTo(x - 0.15f * h, gy - 0.38f * h); close()
            }
            drawPath(coat, w.coat)
            val head = Offset(x + w.dir * 0.02f * h, gy - 0.92f * h)
            drawCircle(Color(0xFF2A2030), 0.075f * h, head)
            val col = w.umbrella
            if (col != null) {
                // An LED umbrella: glowing shaft and rim, light falling on the walker underneath.
                val hand = Offset(x + w.dir * 0.09f * h, gy - 0.62f * h)
                val tip = Offset(x + w.dir * 0.05f * h, gy - 1.3f * h)
                glowOval(col, Offset(tip.x, gy - 0.9f * h), 0.45f * h, 0.5f * h, 0.35f)
                drawArc(Color(0xFF14101E), 180f, 180f, true, Offset(tip.x - 0.44f * h, tip.y - 0.02f * h), Size(0.88f * h, 0.5f * h), alpha = 0.9f)
                drawArc(col, 180f, 180f, false, Offset(tip.x - 0.44f * h, tip.y - 0.02f * h), Size(0.88f * h, 0.5f * h), style = Stroke(0.03f * h))
                drawLine(col, Offset(tip.x - 0.44f * h, tip.y + 0.23f * h), Offset(tip.x + 0.44f * h, tip.y + 0.23f * h), strokeWidth = 0.02f * h, alpha = 0.8f)
                drawLine(col, hand, Offset(tip.x, tip.y - 0.04f * h), strokeWidth = 0.025f * h)
            } else {
                // Hooded, with a lit visor.
                drawCircle(w.coat, 0.09f * h, head + Offset(-w.dir * 0.01f * h, -0.01f * h))
                drawLine(NeonCyan, head + Offset(w.dir * 0.01f * h, 0f), head + Offset(w.dir * 0.08f * h, 0f), strokeWidth = 0.025f * h)
                glow(NeonCyan, head, 0.16f * h, 0.4f)
            }
        }
    }

    /** Police drone sweeping a searchlight over the street. */
    fun drawCop(scope: DrawScope, s: NeonState) = with(scope) {
        if (!copActive) return@with
        val u = s.u
        val c = Offset(s.screenX(copX), s.groundY - 640f * u)
        val spot = Offset(s.screenX(spotX), s.groundY)
        val beam = Path().apply {
            moveTo(c.x - 6f * u, c.y + 8f * u); lineTo(c.x + 6f * u, c.y + 8f * u)
            lineTo(spot.x + 120f * u, spot.y); lineTo(spot.x - 120f * u, spot.y); close()
        }
        drawPath(beam, Brush.verticalGradient(listOf(Color(0x40DDF4FF), Color(0x18DDF4FF)), startY = c.y, endY = spot.y))
        glowOval(Color(0xFFDDF4FF), spot, 150f * u, 30f * u, 0.55f)
        drawRoundRect(Color(0xFF2A2440), Offset(c.x - 36f * u, c.y - 8f * u), Size(72f * u, 16f * u), CornerRadius(8f * u))
        drawLine(Color(0xFF2A2440), Offset(c.x - 56f * u, c.y - 10f * u), Offset(c.x + 56f * u, c.y - 10f * u), strokeWidth = 3f * u)
        val red = (s.t * 4f).toInt() % 2 == 0
        val lc = if (red) NeonRed else Color(0xFF3B6BFF)
        glow(lc, Offset(c.x + (if (red) -22f else 22f) * u, c.y - 10f * u), 34f * u, 0.9f)
        drawCircle(Color.White, 4f * u, Offset(c.x, c.y + 8f * u))
    }

    /** Low fog rolling along the street; in ASCII it only shows where a light catches it. */
    fun drawFog(scope: DrawScope, s: NeonState, front: Boolean) = with(scope) {
        val u = s.u
        val span = size.width / u + 1200f
        for (f in fog) {
            val isFront = f[5] > 1f
            if (isFront != front) continue
            val sx = ((f[0] - s.camera * f[5] + s.t * f[4] * (1f + gust * 4f)) % span + span) % span - 600f
            glowOval(FogColor, Offset(sx * u, s.groundY - f[1] * u), f[2] * u, f[3] * u, if (front) 0.12f else 0.2f)
        }
    }

    fun drawFlyers(scope: DrawScope, s: NeonState) = with(scope) {
        val u = s.u
        for (f in flyers) {
            if (!f.alive) continue
            val hw = 9f * u; val hh = 12f * u * abs(cos(f.angle))
            drawRect(f.color, Offset(f.x - hw, f.y - hh), Size(hw * 2f, hh * 2f + 1f), alpha = 0.85f)
        }
    }

    fun drawSparks(scope: DrawScope, s: NeonState) = with(scope) {
        val u = s.u
        for (i in spX.indices) {
            val life = spLife[i]
            if (life <= 0f) continue
            val a = (life / 0.6f).coerceAtMost(1f)
            val tail = Offset(spX[i] - spVx[i] * 0.025f, spY[i] - spVy[i] * 0.025f)
            drawLine(Color(0xFFFFB45A), tail, Offset(spX[i], spY[i]), strokeWidth = 2f * u, alpha = a)
            drawCircle(Color(0xFFFFF2D0), 1.6f * u, Offset(spX[i], spY[i]), alpha = a)
        }
    }

    /**
     * Life in the light, like the fireflies in the reference: moths circling every street lamp and
     * slow blinking motes drifting through the street. Both are computed from time, no state.
     */
    fun drawMotes(scope: DrawScope, s: NeonState) = with(scope) {
        val u = s.u
        val t = s.t
        val gy = s.groundY
        val ww = s.city.worldWidth
        val view = s.viewWidth
        for (lamp in s.city.lamps) {
            var sxU = ((lamp.x - s.camera) % ww + ww) % ww
            if (sxU > view + 400f) sxU -= ww
            if (sxU < -150f || sxU > view + 150f) continue
            val head = Offset((sxU + 32f) * u, gy - 236f * u)
            for (k in 0 until 5) {
                val ph = hash(lamp.seed, k, 3) * 6.28f
                val sp = 2.2f + hash(lamp.seed, k, 4) * 2.5f
                val a = t * sp + ph
                val r = (18f + 26f * hash(lamp.seed, k, 5)) * u
                val p = head + Offset(cos(a) * r + sin(a * 2.7f) * 6f * u, sin(a * 1.3f) * r * 0.6f + 14f * u)
                drawCircle(lamp.color, 4f * u, p, alpha = 0.25f)
                drawCircle(Color(0xFFFFF4DA), 1.5f * u, p)
            }
        }
        val span = view + 200f
        for (i in 0 until 40) {
            val x = ((hash(i, 1, 9) * 4000f - s.camera * (0.8f + 0.4f * hash(i, 2, 9)) + t * (6f + 10f * hash(i, 3, 9))) % span + span) % span - 100f
            val y = 30f + 380f * hash(i, 4, 9) + 18f * sin(t * (0.6f + hash(i, 5, 9)) + i)
            val blink = sin(t * (0.8f + 1.4f * hash(i, 6, 9)) + i * 1.7f)
            if (blink < 0.1f) continue
            val c = Offset(x * u + sin(t * 1.3f + i) * 8f * u, gy - y * u)
            val col = if (i % 4 == 0) NeonAmber else Color(0xFF8FE3FF)
            drawCircle(col, 6f * u, c, alpha = 0.18f * blink)
            drawCircle(col, 1.7f * u, c, alpha = 0.95f * blink)
        }
    }

    /** Falling drips, drawn crisp over the ASCII like the near rain. */
    fun drawDrips(scope: DrawScope, s: NeonState) = with(scope) {
        val u = s.u
        for (d in drips) {
            if (!d.alive) continue
            val x = s.screenX(d.x); val y = s.groundY - d.y * u
            val len = (4f + (-d.vy) * 0.012f).coerceAtMost(16f) * u
            drawLine(DropColor, Offset(x, y - len), Offset(x, y), strokeWidth = 1.6f * u, alpha = 0.75f)
        }
    }
}
