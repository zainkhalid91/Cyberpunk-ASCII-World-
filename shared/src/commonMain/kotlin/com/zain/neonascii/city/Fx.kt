package com.zain.neonascii.city

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.max
import kotlin.random.Random

/** A flying car. [x] is in u from the left screen edge; [lane] is u above the street. */
class Car(
    var x: Float,
    val lane: Float,
    val speed: Float,
    val dir: Int,
    val scale: Float,
    val police: Boolean,
    val glow: Color,
)

fun makeCars(rnd: Random): List<Car> {
    val lanes = floatArrayOf(380f, 450f, 520f, 600f, 690f, 560f, 420f)
    val glows = listOf(NeonCyan, NeonPink, NeonViolet, NeonLime)
    return lanes.mapIndexed { i, lane ->
        val scale = 0.45f + rnd.nextFloat() * 0.55f
        Car(
            x = rnd.nextFloat() * 1200f,
            lane = lane,
            speed = (70f + rnd.nextFloat() * 110f) * (0.5f + scale),
            dir = if (i % 2 == 0) 1 else -1,
            scale = scale,
            police = i == 3,
            glow = glows[rnd.nextInt(glows.size)],
        )
    }
}

/** One lightning strike: a jagged main channel plus a few forks, in screen pixels. */
class Bolt(val main: List<Offset>, val branches: List<List<Offset>>, val power: Float) {
    var age = 0f

    /** The classic double flash: bright, a dip, bright again, then fade. */
    val intensity: Float
        get() = when {
            age < 0.05f -> 1f
            age < 0.09f -> 0.25f
            age < 0.2f -> 0.95f
            age < 0.5f -> 0.95f * (1f - (age - 0.2f) / 0.3f)
            else -> 0f
        } * power

    companion object {
        fun create(from: Offset, to: Offset, u: Float, power: Float, rnd: Random): Bolt {
            val main = mutableListOf(from)
            jag(from, to, (to - from).getDistance() * 0.22f, 7, main, rnd)
            val branches = List(2 + rnd.nextInt(3)) {
                val start = main[(main.size * (0.2f + rnd.nextFloat() * 0.5f)).toInt()]
                val end = start + Offset((rnd.nextFloat() - 0.5f) * 420f * u, (90f + rnd.nextFloat() * 220f) * u)
                mutableListOf(start).also { jag(start, end, (end - start).getDistance() * 0.3f, 5, it, rnd) }
            }
            return Bolt(main, branches, power)
        }

        private fun jag(a: Offset, b: Offset, disp: Float, depth: Int, out: MutableList<Offset>, rnd: Random) {
            if (depth == 0) { out += b; return }
            val d = b - a
            val len = max(d.getDistance(), 0.001f)
            val normal = Offset(-d.y / len, d.x / len)
            val mid = (a + b) / 2f + normal * ((rnd.nextFloat() - 0.5f) * disp)
            jag(a, mid, disp * 0.5f, depth - 1, out, rnd)
            jag(mid, b, disp * 0.5f, depth - 1, out, rnd)
        }
    }
}

/**
 * Rain in two parts. Far streaks are drawn into the scene so the ASCII pass turns them into
 * shimmering glyphs; near drops and their splashes are drawn crisp on top, like in the reference.
 */
class Rain {
    private val max = 1400
    private val x = FloatArray(max); private val y = FloatArray(max); private val z = FloatArray(max)
    private var count = 0
    private val farMax = 220
    private val fx = FloatArray(farMax); private val fy = FloatArray(farMax)
    private val sMax = 160
    private val sx = FloatArray(sMax); private val sy = FloatArray(sMax); private val sAge = FloatArray(sMax) { 9f }
    private val sScale = FloatArray(sMax)
    private var sNext = 0
    private var w = 0f; private var h = 0f
    private val rnd = Random(7)
    var wind = 0.18f

    fun resize(width: Float, height: Float, u: Float) {
        if (width == w && height == h) return
        w = width; h = height
        count = (width / u * 0.9f).toInt().coerceIn(320, max)
        for (i in 0 until count) { x[i] = rnd.nextFloat() * width * 1.3f - width * 0.15f; y[i] = rnd.nextFloat() * height; z[i] = 0.25f + rnd.nextFloat() * 0.75f }
        for (i in 0 until farMax) { fx[i] = rnd.nextFloat() * width; fy[i] = rnd.nextFloat() * height }
    }

    /**
     * [canopy]/[canopyR]: the runner's holo-canopy in screen pixels; near drops stop on it.
     * [camShift]: how far the view scrolled this frame, in pixels, so rain stays in the world.
     */
    fun update(dt: Float, u: Float, groundY: Float, amount: Float, canopy: Offset, canopyR: Float, camShift: Float) {
        val active = (count * (0.22f + 0.22f * amount)).toInt().coerceAtMost(count) // amount runs up to ~1.5 in a cloudburst
        val r2 = canopyR * canopyR
        val inner2 = r2 * 0.55f
        for (i in 0 until active) {
            val v = (430f + 420f * z[i]) * u // slow rain
            y[i] += v * dt
            x[i] += v * wind * dt - camShift * z[i]
            val floor = groundY + (h - groundY) * (z[i] - 0.25f) / 0.75f
            var hit = y[i] > floor
            var hitY = floor
            if (!hit && z[i] > 0.55f) {
                val dx = x[i] - canopy.x
                val dy = y[i] - canopy.y
                val d2 = dx * dx + dy * dy
                if (dy < 0f && d2 < r2 && d2 > inner2) { hit = true; hitY = y[i] }
            }
            if (hit) {
                if (z[i] > 0.4f) splash(x[i], hitY, z[i])
                y[i] = -rnd.nextFloat() * h * 0.25f
                x[i] = rnd.nextFloat() * w * 1.3f - w * 0.25f
                z[i] = 0.25f + rnd.nextFloat() * 0.75f
            }
        }
        for (i in active until count) y[i] = -h // parked while the rain eases off
        for (i in 0 until farMax) {
            fy[i] += 620f * u * dt
            fx[i] += 620f * u * wind * dt - camShift * 0.5f
            if (fy[i] > groundY) { fy[i] = -rnd.nextFloat() * 200f * u; fx[i] = rnd.nextFloat() * w * 1.2f - w * 0.1f }
        }
        for (i in 0 until sMax) { sAge[i] += dt; sx[i] -= camShift }
    }

    /** A splash at a screen point, e.g. under a footstep; [depth] scales it. */
    fun splash(px: Float, py: Float, depth: Float) {
        sx[sNext] = px; sy[sNext] = py; sAge[sNext] = 0f; sScale[sNext] = depth
        sNext = (sNext + 1) % sMax
        if (pendingCount < pendingX.size) { pendingX[pendingCount] = px; pendingZ[pendingCount] = depth; pendingCount++ }
    }

    // Splashes since the last drain, so the mixer can answer them with drips.
    private val pendingX = FloatArray(64)
    private val pendingZ = FloatArray(64)
    private var pendingCount = 0

    /** Hands over this frame's splashes (screen x, depth 0..1) and clears the queue. */
    fun drainSplashes(block: (x: Float, depth: Float) -> Unit) {
        for (i in 0 until pendingCount) block(pendingX[i], pendingZ[i])
        pendingCount = 0
    }

    // Line batches per depth band: a few native draw calls instead of one per drop.
    private val bands = 4
    private val bandPts = Array(bands) { FloatArray(max * 4) }
    private val bandCount = IntArray(bands)
    private val farPts = FloatArray(farMax * 4)

    /** Far streaks, drawn inside the ASCII layer. */
    fun drawFar(scope: DrawScope, u: Float) = with(scope) {
        val len = 22f * u
        var n = 0
        for (i in 0 until farMax) {
            farPts[n++] = fx[i]; farPts[n++] = fy[i]
            farPts[n++] = fx[i] - len * wind; farPts[n++] = fy[i] - len
        }
        drawLineBatch(farPts, n, Color(0xFF8FA8FF), 0.22f, 1.4f * u)
    }

    /** Near drops, splashes and street ripples, drawn crisp over the ASCII layer. */
    fun drawNear(scope: DrawScope, u: Float, groundY: Float) = with(scope) {
        val dropColor = Color(0xFFCFDCFF)
        bandCount.fill(0)
        for (i in 0 until count) {
            if (y[i] < -10f) continue
            val d = z[i]
            val b = ((d - 0.25f) / 0.75f * bands).toInt().coerceIn(0, bands - 1)
            val len = (10f + 16f * d) * u
            val pts = bandPts[b]
            var n = bandCount[b]
            pts[n++] = x[i]; pts[n++] = y[i]
            pts[n++] = x[i] - len * wind; pts[n++] = y[i] - len
            bandCount[b] = n
        }
        for (b in 0 until bands) {
            val d = 0.25f + 0.75f * (b + 0.5f) / bands
            drawLineBatch(bandPts[b], bandCount[b], dropColor, 0.10f + 0.42f * d, (0.5f + 1.1f * d) * u)
        }
        for (i in 0 until sMax) {
            val a = sAge[i]
            if (a > 0.5f) continue
            val sc = sScale[i] * u
            if (sy[i] >= groundY - 2f && a < 0.5f) {
                // Ripple ring spreading on the wet street.
                val k = a / 0.5f
                val rx = (6f + 30f * k) * sc
                drawOval(dropColor, Offset(sx[i] - rx, sy[i] - rx * 0.22f), Size(rx * 2f, rx * 0.44f), alpha = (1f - k) * 0.35f, style = Stroke(0.9f * sc))
            }
            if (a > 0.24f) continue
            val k = a / 0.24f
            val spread = (4f + 16f * k) * sc
            val rise = (10f * k - 9f * k * k) * sc * 1.6f
            val alpha = (1f - k) * 0.55f
            drawLine(dropColor, Offset(sx[i] - spread * 0.3f, sy[i]), Offset(sx[i] - spread, sy[i] - rise), strokeWidth = 1.1f * sc, alpha = alpha)
            drawLine(dropColor, Offset(sx[i] + spread * 0.3f, sy[i]), Offset(sx[i] + spread, sy[i] - rise), strokeWidth = 1.1f * sc, alpha = alpha)
        }
    }
}
