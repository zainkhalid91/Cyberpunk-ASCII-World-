package com.zain.neonascii.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.zain.neonascii.NeonState
import com.zain.neonascii.city.NeonAmber
import com.zain.neonascii.city.NeonCyan
import com.zain.neonascii.city.NeonPink
import com.zain.neonascii.city.drawLineBatch
import com.zain.neonascii.city.glow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// The city answering the player, like the grass in the reference: weeds that bend away from
// whoever walks through and sway in the wind, loose cables that swing when brushed, cans that get
// kicked down the street, and rings spreading in puddles under every footstep. All in level u.

private val WeedLight = Color(0xFF4FC48A)
private val WeedDark = Color(0xFF2F8A66)
private val CableColor = Color(0xFF9A96B8)
private val CanColors = listOf(Color(0xFFD94A6A), Color(0xFF4AA8D9), Color(0xFFE0C04A))

interface ReactiveEvents {
    fun clink(x: Float, strength: Float)
}

/** A loose cable hanging from a ledge, simulated as a verlet rope with a bulb on the end. */
private class Rope(val ax: Float, val ay: Float, n: Int, val seg: Float, val bulb: Color) {
    val x = FloatArray(n) { ax }
    val y = FloatArray(n) { ay - it * seg }
    val px = x.copyOf()
    val py = y.copyOf()
}

private class Can(var x: Float, val color: Color) {
    var y = 0f; var vx = 0f; var vy = 0f; var angle = 0f; var spin = 0f
    val homeX = x
}

private class Ripple { var x = 0f; var depth = 0f; var age = 9f; var strength = 0f }

class Reactive(level: Level, private val events: ReactiveEvents, seed: Int = 515) {
    private val rnd = Random(seed)

    // ---- weeds: one spring per blade
    private val bx: FloatArray
    private val bh: FloatArray
    private val lean: FloatArray
    private val ang: FloatArray
    private val angV: FloatArray
    private val count: Int

    private val ropes = mutableListOf<Rope>()
    private val cans = mutableListOf<Can>()
    private val ripples = List(24) { Ripple() }
    private var rippleNext = 0
    private var lastSwing = -1
    private val nearX = FloatArray(16)
    private val nearV = FloatArray(16)
    private var nearCount = 0

    init {
        fun blocked(x: Float) = level.solids.any { it.bottom == 0f && x > it.x - 6f && x < it.right + 6f }
        val xs = ArrayList<Float>()
        var x = 150f
        while (x < level.length) {
            if (rnd.nextFloat() < 0.6f) {
                val n = 4 + rnd.nextInt(7)
                repeat(n) {
                    val bxv = x + (rnd.nextFloat() - 0.5f) * 40f
                    if (!blocked(bxv)) xs += bxv
                }
            }
            x += 35f + rnd.nextFloat() * 85f
        }
        count = xs.size
        bx = FloatArray(count) { xs[it] }
        bh = FloatArray(count) { 16f + rnd.nextFloat() * 30f }
        lean = FloatArray(count) { (rnd.nextFloat() - 0.5f) * 0.5f }
        ang = lean.copyOf()
        angV = FloatArray(count)

        val bulbs = listOf(NeonAmber, NeonCyan, NeonPink)
        for (l in level.ledges) {
            if (l.kind != LedgeKind.Catwalk && l.kind != LedgeKind.FireEscape && l.kind != LedgeKind.Awning) continue
            val k = if (l.w > 300f) 2 else 1
            repeat(k) {
                val ax = l.x + l.w * (0.2f + 0.6f * rnd.nextFloat())
                val len = 70f + rnd.nextFloat() * 70f
                ropes += Rope(ax, l.yAt(0f) - 10f, 7, len / 6f, bulbs[rnd.nextInt(bulbs.size)])
            }
        }
        var cx = 600f
        while (cx < level.length - 300f) {
            if (!blocked(cx)) cans += Can(cx, CanColors[rnd.nextInt(CanColors.size)])
            cx += 260f + rnd.nextFloat() * 520f
        }
    }

    /** A footstep or landing at level x: rings in a puddle if there is one, a faint ring if not. */
    fun step(s: NeonState, x: Float, power: Float) {
        val p = s.ambience.puddles.firstOrNull { x >= it[0] - 10f && x <= it[0] + it[1] + 10f }
        val r = ripples[rippleNext]; rippleNext = (rippleNext + 1) % ripples.size
        r.x = x; r.age = 0f
        r.depth = p?.get(2) ?: 6f
        r.strength = (if (p != null) 1f else 0.35f) * (0.6f + 0.6f * power)
    }

    fun reset() {
        for (c in cans) { c.x = c.homeX; c.y = 0f; c.vx = 0f; c.vy = 0f; c.angle = 0f; c.spin = 0f }
    }

    fun update(s: NeonState, dt: Float) {
        if (dt <= 0f) return
        val r = s.runner
        val cam = s.camera
        val view = s.viewWidth
        val lo = cam - 200f
        val hi = cam + view + 200f
        val t = s.t
        val wind = s.rain.wind
        val gust = s.ambience.gust
        val enemies = s.combat.enemies

        // ---- weeds (only enemies on the ground near the view can trample them)
        nearCount = 0
        for (e in enemies) {
            if (!e.alive || e.kind == EnemyKind.Drone || e.y > 50f || e.x < lo || e.x > hi || nearCount >= nearX.size) continue
            nearX[nearCount] = e.x; nearV[nearCount] = e.vx; nearCount++
        }
        val sway = 0.1f + gust * 0.35f
        for (i in 0 until count) {
            val x = bx[i]
            if (x < lo || x > hi) continue
            var target = lean[i] + wind * 1.2f + gust * 0.45f + sway * sin(t * 1.8f + x * 0.031f)
            target += push(x, r.x, r.y, r.vx)
            for (k in 0 until nearCount) target += push(x, nearX[k], 0f, nearV[k]) * 0.8f
            angV[i] += (70f * (target - ang[i]) - 7f * angV[i]) * dt
            ang[i] += angV[i] * dt
            ang[i] = ang[i].coerceIn(-1.45f, 1.45f)
        }

        // ---- ropes: verlet with gravity, wind and the runner's body pushing through
        val bodyX = r.x
        val bodyY = r.y + 95f
        val bodyR = 62f
        val sab = saberPoints(r)
        val swinging = r.swingT >= 0f
        for (rope in ropes) {
            if (rope.ax < lo || rope.ax > hi) continue
            val n = rope.x.size
            for (k in 1 until n) {
                val vx = (rope.x[k] - rope.px[k]) * 0.995f
                val vy = (rope.y[k] - rope.py[k]) * 0.995f
                rope.px[k] = rope.x[k]; rope.py[k] = rope.y[k]
                rope.x[k] += vx + (wind * 260f + gust * 420f + 40f * sin(t * 2.3f + k)) * dt * dt
                rope.y[k] += vy - 1100f * dt * dt
                // The runner shoulders through; a saber swing knocks it hard.
                val dx = rope.x[k] - bodyX
                val dy = rope.y[k] - bodyY
                val d2 = dx * dx + dy * dy
                if (d2 < bodyR * bodyR && d2 > 0.01f) {
                    val d = sqrt(d2)
                    rope.x[k] = bodyX + dx / d * bodyR
                    rope.y[k] = bodyY + dy / d * bodyR
                    rope.px[k] -= r.vx * dt * 0.6f
                }
                if (swinging) {
                    val sx = rope.x[k] - sab[2]
                    val sy = rope.y[k] - sab[3]
                    if (sx * sx + sy * sy < 45f * 45f) rope.px[k] -= r.facing * 9f
                }
            }
            rope.x[0] = rope.ax; rope.y[0] = rope.ay
            repeat(4) {
                for (k in 0 until n - 1) {
                    val dx = rope.x[k + 1] - rope.x[k]
                    val dy = rope.y[k + 1] - rope.y[k]
                    val d = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                    val diff = (d - rope.seg) / d
                    if (k == 0) {
                        rope.x[1] -= dx * diff; rope.y[1] -= dy * diff
                    } else {
                        rope.x[k] += dx * diff * 0.5f; rope.y[k] += dy * diff * 0.5f
                        rope.x[k + 1] -= dx * diff * 0.5f; rope.y[k + 1] -= dy * diff * 0.5f
                    }
                }
            }
        }

        // ---- cans: kicked by running feet or the saber, bounce and roll to a stop
        val newSwing = swinging && r.swingId != lastSwing
        for (c in cans) {
            if (c.x < lo - 600f || c.x > hi + 600f) continue
            val near = abs(c.x - r.x) < 34f && r.y < 30f && c.y < 20f
            if (near && abs(r.vx) > 60f && abs(c.vx) < abs(r.vx)) {
                c.vx = r.vx * 1.5f + sign(r.vx) * 80f
                c.vy = 200f + abs(r.vx) * 0.35f
                c.spin = -sign(r.vx) * (10f + rnd.nextFloat() * 8f)
                events.clink(c.x, 0.7f)
            }
            if (newSwing && abs(c.x - sab[2]) < 70f && c.y < 80f && sab[3] < 120f) {
                c.vx = r.facing * (620f + rnd.nextFloat() * 200f)
                c.vy = 420f + rnd.nextFloat() * 160f
                c.spin = -r.facing * 22f
                events.clink(c.x, 1f)
            }
            if (c.y > 0f || c.vy != 0f) {
                c.vy -= 1500f * dt
                c.y += c.vy * dt
                if (c.y <= 0f) {
                    c.y = 0f
                    if (c.vy < -120f) { c.vy = -c.vy * 0.42f; events.clink(c.x, (c.vy / 400f).coerceAtMost(0.6f)) } else c.vy = 0f
                    c.vx *= 0.75f
                }
            }
            if (c.vx != 0f) {
                c.x += c.vx * dt
                if (c.y == 0f) c.vx -= c.vx * minOf(1f, dt * 2.2f)
                c.angle += c.spin * dt
                if (c.y == 0f) c.spin = -c.vx / 9f
                if (abs(c.vx) < 6f && c.y == 0f) { c.vx = 0f; c.spin = 0f }
                for (b in s.level.solids) {
                    if (c.y < b.top && c.x > b.x && c.x < b.right) {
                        c.x = if (c.vx > 0f) b.x - 1f else b.right + 1f
                        c.vx = -c.vx * 0.5f
                        events.clink(c.x, 0.4f)
                    }
                }
                c.x = c.x.coerceIn(s.level.minX, s.level.maxX)
            }
        }
        if (swinging) lastSwing = r.swingId
        for (rp in ripples) rp.age += dt
    }

    /** How far a blade at [x] bends away from someone standing at [ax], [ay]. */
    private fun push(x: Float, ax: Float, ay: Float, avx: Float): Float {
        if (ay > 50f) return 0f
        val dx = x - ax
        if (abs(dx) > 46f) return 0f
        val k = 1f - abs(dx) / 46f
        val dir = if (dx >= 0f) 1f else -1f
        return dir * k * 1.3f + avx * 0.0012f * k
    }

    // ------------------------------------------------------------------ drawing
    // Weeds and ripples are crisp strokes over the ASCII (like the grass in the reference);
    // ropes and cans go on the fine hero grid.

    private val lightPts = FloatArray(8192)
    private val darkPts = FloatArray(8192)

    fun drawWeeds(scope: DrawScope, s: NeonState) = with(scope) {
        val u = s.u
        val cam = s.camera
        val hi = cam + s.viewWidth + 50f
        var nl = 0; var nd = 0
        for (i in 0 until count) {
            val x = bx[i]
            if (x < cam - 50f || x > hi) continue
            val pts = if (i % 3 == 0) darkPts else lightPts
            var n = if (i % 3 == 0) nd else nl
            if (n + 8 > pts.size) continue
            val h = bh[i] * u
            val a = ang[i]
            val x0 = s.screenX(x); val y0 = s.groundY
            val x1 = x0 + sin(a * 0.45f) * h * 0.5f; val y1 = y0 - cos(a * 0.45f) * h * 0.5f
            val x2 = x1 + sin(a) * h * 0.5f; val y2 = y1 - cos(a) * h * 0.5f
            pts[n++] = x0; pts[n++] = y0; pts[n++] = x1; pts[n++] = y1
            pts[n++] = x1; pts[n++] = y1; pts[n++] = x2; pts[n++] = y2
            if (i % 3 == 0) nd = n else nl = n
        }
        drawLineBatch(darkPts, nd, WeedDark, 0.9f, 1.8f * u)
        drawLineBatch(lightPts, nl, WeedLight, 0.9f, 1.5f * u)
    }

    fun drawRopes(scope: DrawScope, s: NeonState) = with(scope) {
        val u = s.u
        val cam = s.camera
        val hi = cam + s.viewWidth + 200f
        for (rope in ropes) {
            if (rope.ax < cam - 200f || rope.ax > hi) continue
            val n = rope.x.size
            for (k in 0 until n - 1) {
                drawLine(CableColor, Offset(s.screenX(rope.x[k]), s.groundY - rope.y[k] * u),
                    Offset(s.screenX(rope.x[k + 1]), s.groundY - rope.y[k + 1] * u), strokeWidth = 2.4f * u)
            }
            val end = Offset(s.screenX(rope.x[n - 1]), s.groundY - rope.y[n - 1] * u)
            glow(rope.bulb, end, 46f * u, 0.55f)
            drawCircle(Color.White, 5f * u, end)
            drawCircle(rope.bulb, 5f * u, end, style = Stroke(2f * u))
        }
    }

    fun drawCans(scope: DrawScope, s: NeonState) = with(scope) {
        val u = s.u
        val cam = s.camera
        val hi = cam + s.viewWidth + 50f
        for (c in cans) {
            if (c.x < cam - 50f || c.x > hi) continue
            val center = Offset(s.screenX(c.x), s.groundY - (c.y + 9f) * u)
            rotate(c.angle * 57.29578f, center) {
                drawRect(c.color, Offset(center.x - 8f * u, center.y - 12f * u), Size(16f * u, 24f * u))
                drawRect(Color.White, Offset(center.x - 8f * u, center.y - 12f * u), Size(16f * u, 4f * u), alpha = 0.8f)
                drawRect(Color.White, Offset(center.x - 4f * u, center.y - 6f * u), Size(3f * u, 14f * u), alpha = 0.5f)
            }
        }
    }

    /** Oval rings spreading from footsteps, crisp over the ASCII like the rain splashes. */
    fun drawRipples(scope: DrawScope, s: NeonState) = with(scope) {
        val u = s.u
        for (rp in ripples) {
            if (rp.age > 1.1f) continue
            val cx = s.screenX(rp.x)
            val cy = s.groundY + rp.depth * u
            for (ring in 0 until 3) {
                val a = rp.age - ring * 0.14f
                if (a < 0f) continue
                val k = a / 1.1f
                if (k >= 1f) continue
                val rx = (10f + 110f * k) * rp.strength * u
                drawOval(Color(0xFFCFE6FF), Offset(cx - rx, cy - rx * 0.22f), Size(rx * 2f, rx * 0.44f),
                    alpha = (1f - k) * 0.6f * rp.strength, style = Stroke(1.4f * u))
            }
        }
    }
}
