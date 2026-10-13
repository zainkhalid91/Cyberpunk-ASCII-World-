package com.zain.neonascii.game

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import com.zain.neonascii.NeonState
import com.zain.neonascii.city.NeonAmber
import com.zain.neonascii.city.NeonCyan
import com.zain.neonascii.city.NeonPink
import com.zain.neonascii.city.NeonRed
import com.zain.neonascii.city.NeonViolet
import com.zain.neonascii.city.TextCache
import com.zain.neonascii.city.flicker
import com.zain.neonascii.city.glow
import com.zain.neonascii.city.glowOval
import com.zain.neonascii.city.hash
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val Clear = Color(0x00000000)
const val RUNNER_SCALE = 1.25f
private val Steel = Color(0xFF241A3A)
private val SteelLight = Color(0xFF3A2C58)
// Light enough to become dense glyphs in ASCII, so the runner reads against the busy city.
/** Darkens the glyph field around a subject so it stands out in ASCII. */
private val HaloColor = Color(0xFF040010)

/** Converts level space (u, y up) to screen pixels. */
private class View(val cam: Float, val u: Float, val gy: Float) {
    fun x(lx: Float) = (lx - cam) * u
    fun y(ly: Float) = gy - ly * u
}

// ------------------------------------------------------------------ world structures

/** Everything in the play plane that stands behind the runner. */
fun DrawScope.drawLevel(s: NeonState, text: TextCache) {
    val v = View(s.camera, s.u, s.groundY)
    val wu = size.width / s.u
    val minX = s.camera - 400f
    val maxX = s.camera + wu + 400f
    val level = s.level

    for (d in level.decor) if (d.x + d.w > minX && d.x < maxX) drawDecor(d, v, s, text)
    drawSteam(s, v)
    for (l in level.ledges) if (l.right > minX && l.x < maxX) drawLedge(l, v, s.t)
    for (b in level.solids) if (b.right > minX && b.x < maxX) drawSolid(b, v, s.t)
    for (sh in level.shards) if (sh.x > minX && sh.x < maxX) drawShard(sh, v, s.t)
    if (level.exitX > minX && level.exitX < maxX) drawExit(level.exitX, v, s.t, text)
}

private fun DrawScope.drawDecor(d: Decor, v: View, s: NeonState, text: TextCache) {
    val u = v.u
    val t = s.t
    when (d.kind) {
        DecorKind.Storefront -> {
            val x = v.x(d.x); val w = d.w * u; val top = v.y(d.h)
            drawRect(Color(0xFF120A24), Offset(x, top), Size(w, d.h * u))
            // Lit doorway and shop window.
            drawRect(d.color, Offset(x + w * 0.12f, v.y(150f)), Size(w * 0.3f, 150f * u), alpha = 0.55f)
            drawRect(Color(0xFFFFE0B0), Offset(x + w * 0.55f, v.y(130f)), Size(w * 0.32f, 90f * u), alpha = 0.35f)
            val fl = flicker(d.seed, t)
            val layout = text.get(d.text, 30f * u)
            val sx = x + (w - layout.size.width) / 2f
            val sy = v.y(d.h - 18f)
            val c = Offset(x + w / 2f, sy + layout.size.height / 2f)
            glow(d.color, c, w * 0.5f, 0.45f * fl)
            drawText(layout, d.color, Offset(sx, sy), alpha = fl)
            // Spill of light onto the wet street.
            glowOval(d.color, Offset(x + w * 0.35f, v.gy + 15f * u), w * 0.55f, 35f * u, 0.35f)
        }
        DecorKind.FireEscapeFrame -> {
            val x = v.x(d.x); val w = d.w * u
            // Wall behind, posts, ladders and stairs between landings.
            drawRect(Color(0xFF0F0820), Offset(x - 30f * u, v.y(d.h + 40f)), Size(w + 120f * u, (d.h + 40f) * u))
            for (k in 0 until 6) {
                val wy = v.y(80f + k * 70f)
                drawRect(if (hash(d.seed, k) < 0.5f) NeonAmber else NeonCyan, Offset(x + 40f * u, wy), Size(26f * u, 34f * u), alpha = 0.18f + 0.4f * hash(d.seed, k, 3))
            }
            drawLine(Steel, Offset(x + 6f * u, v.gy), Offset(x + 6f * u, v.y(d.h)), strokeWidth = 5f * u)
            drawLine(Steel, Offset(x + w + 54f * u, v.gy), Offset(x + w + 54f * u, v.y(d.h)), strokeWidth = 5f * u)
            val stairs = listOf(150f to 290f, 290f to 430f)
            stairs.forEachIndexed { i, (a, b) ->
                val fromX = if (i % 2 == 0) x + 150f * u else x + 70f * u
                val toX = if (i % 2 == 0) x + 70f * u else x + 150f * u
                drawLine(SteelLight, Offset(fromX, v.y(a)), Offset(toX, v.y(b)), strokeWidth = 4f * u)
                for (k in 1..6) {
                    val f = k / 7f
                    val px = fromX + (toX - fromX) * f
                    val py = v.y(a + (b - a) * f)
                    drawLine(Steel, Offset(px - 10f * u, py), Offset(px + 10f * u, py), strokeWidth = 2.5f * u)
                }
            }
        }
        DecorKind.CatwalkTruss -> {
            val x = v.x(d.x); val w = d.w * u; val top = v.y(d.h)
            drawLine(Steel, Offset(x + 10f * u, v.gy), Offset(x + 10f * u, top), strokeWidth = 7f * u)
            drawLine(Steel, Offset(x + w - 10f * u, v.gy), Offset(x + w - 10f * u, top), strokeWidth = 7f * u)
            val under = v.y(d.h - 34f)
            drawLine(Steel, Offset(x, under), Offset(x + w, under), strokeWidth = 3f * u)
            var k = 0f
            while (k < d.w) {
                drawLine(Steel, Offset(x + k * u, top), Offset(x + (k + 40f) * u, under), strokeWidth = 2f * u)
                k += 40f
            }
            // Hanging lamps under the walkway.
            k = 60f
            while (k < d.w) {
                val c = Offset(x + k * u, under + 22f * u)
                drawLine(Steel, Offset(c.x, under), c, strokeWidth = 1.5f * u)
                glow(d.color, c, 70f * u, 0.5f)
                drawCircle(Color.White, 3.5f * u, c)
                k += 140f
            }
        }
        DecorKind.RamenStall -> {
            val x = v.x(d.x); val w = d.w * u
            drawRect(Color(0xFF1B0F22), Offset(x, v.y(120f)), Size(w, 120f * u))
            drawRect(Color(0xFFFFC27A), Offset(x + 14f * u, v.y(108f)), Size(w - 28f * u, 50f * u), alpha = 0.55f)
            // Noren curtain strips and paper lanterns.
            for (k in 0 until 6) drawRect(NeonRed, Offset(x + (20f + k * 42f) * u, v.y(150f)), Size(34f * u, 36f * u), alpha = 0.7f)
            for (k in 0 until 3) {
                val c = Offset(x + (40f + k * 100f) * u, v.y(140f + 8f * sin(s.t * 1.5f + k)))
                glow(NeonAmber, c, 60f * u, 0.55f)
                drawOval(Color(0xFFFF8A3D), Offset(c.x - 9f * u, c.y - 12f * u), Size(18f * u, 24f * u))
            }
            val layout = text.get(d.text, 26f * u)
            drawText(layout, NeonAmber, Offset(x + (w - layout.size.width) / 2f, v.y(205f)), alpha = flicker(d.seed, s.t))
            glowOval(NeonAmber, Offset(x + w / 2f, v.gy + 20f * u), w * 0.7f, 40f * u, 0.3f)
        }
        DecorKind.Vent -> {
            val x = v.x(d.x)
            drawRect(Color(0xFF2A2040), Offset(x - 30f * u, v.gy - 2f * u), Size(60f * u, 6f * u))
            for (k in 0 until 5) drawLine(Color(0xFF0A0614), Offset(x - 26f * u + k * 13f * u, v.gy - 2f * u), Offset(x - 26f * u + k * 13f * u, v.gy + 4f * u), strokeWidth = 2f * u)
        }
    }
}

private fun DrawScope.drawLedge(l: Ledge, v: View, t: Float) {
    val u = v.u
    val x = v.x(l.x); val w = l.w * u; val y = v.y(l.yAt(t))
    when (l.kind) {
        LedgeKind.Awning, LedgeKind.StallRoof -> {
            val stripes = 8
            for (k in 0 until stripes) {
                val c = if (k % 2 == 0) l.color else Color(0xFF1A0E2A)
                drawRect(c, Offset(x + k * w / stripes, y), Size(w / stripes + 1f, 22f * u), alpha = if (k % 2 == 0) 0.75f else 1f)
            }
            drawRect(Color.White, Offset(x, y), Size(w, 2f * u), alpha = 0.5f)
            for (k in 0..stripes) drawCircle(l.color, 4f * u, Offset(x + k * w / stripes, y + 26f * u), alpha = 0.8f)
        }
        LedgeKind.FireEscape -> {
            drawRect(SteelLight, Offset(x, y), Size(w, 7f * u))
            drawLine(Steel, Offset(x, y - 46f * u), Offset(x + w, y - 46f * u), strokeWidth = 3f * u)
            var k = 0f
            while (k <= l.w) { drawLine(Steel, Offset(x + k * u, y), Offset(x + k * u, y - 46f * u), strokeWidth = 2f * u); k += 25f }
            drawRect(l.color, Offset(x, y + 7f * u), Size(w, 2f * u), alpha = 0.35f)
        }
        LedgeKind.Catwalk -> {
            drawRect(SteelLight, Offset(x, y), Size(w, 9f * u))
            drawRect(NeonAmber, Offset(x, y), Size(w, 2f * u), alpha = 0.8f)
            drawLine(Steel, Offset(x, y - 40f * u), Offset(x + w, y - 40f * u), strokeWidth = 2.5f * u)
            var k = 0f
            while (k <= l.w) { drawLine(Steel, Offset(x + k * u, y), Offset(x + k * u, y - 40f * u), strokeWidth = 2f * u); k += 50f }
        }
        LedgeKind.HoverPad -> {
            val c = Offset(x + w / 2f, y + 18f * u)
            val pulse = 0.7f + 0.3f * sin(t * 9f + l.seed)
            glowOval(l.color, Offset(c.x, c.y + 25f * u), w * 0.7f, 45f * u, 0.6f * pulse)
            // Thruster jets.
            for (side in listOf(0.2f, 0.8f)) {
                val jx = x + w * side
                drawLine(Brush.verticalGradient(listOf(l.color.copy(alpha = 0.8f), Clear), startY = y + 10f * u, endY = y + 80f * u), Offset(jx, y + 10f * u), Offset(jx, y + 80f * u * pulse), strokeWidth = 8f * u)
            }
            drawRoundRect(Color(0xFF2C2048), Offset(x, y), Size(w, 14f * u), CornerRadius(7f * u))
            drawRect(l.color, Offset(x + 8f * u, y + 2f * u), Size(w - 16f * u, 3f * u), alpha = 0.9f)
        }
    }
}

private fun DrawScope.drawSolid(b: Solid, v: View, t: Float) {
    val u = v.u
    val x = v.x(b.x); val w = b.w * u; val top = v.y(b.top); val h = b.h * u
    when (b.kind) {
        SolidKind.Crate -> {
            drawRect(Color(0xFF2B2140), Offset(x, top), Size(w, h))
            drawRect(Color(0xFF3D3060), Offset(x, top), Size(w, h), style = Stroke(3f * u))
            drawLine(Color(0xFF3D3060), Offset(x, top), Offset(x + w, top + h), strokeWidth = 3f * u)
            drawLine(Color(0xFF3D3060), Offset(x + w, top), Offset(x, top + h), strokeWidth = 3f * u)
            drawRect(NeonAmber, Offset(x, top + h * 0.42f), Size(w, 6f * u), alpha = 0.55f)
            if ((t * 1.7f + hash(b.seed, 1) * 4f) % 2f < 0.15f) drawCircle(NeonRed, 3f * u, Offset(x + w - 8f * u, top + 8f * u))
        }
        SolidKind.Dumpster -> {
            drawRect(Color(0xFF14302E), Offset(x, top + 8f * u), Size(w, h - 14f * u))
            drawRect(Color(0xFF1E4642), Offset(x - 4f * u, top), Size(w + 8f * u, 12f * u))
            // A glowing graffiti tag.
            val p = Path().apply {
                moveTo(x + w * 0.15f, top + h * 0.6f)
                cubicTo(x + w * 0.3f, top + h * 0.2f, x + w * 0.45f, top + h * 0.9f, x + w * 0.6f, top + h * 0.45f)
                quadraticTo(x + w * 0.75f, top + h * 0.2f, x + w * 0.85f, top + h * 0.55f)
            }
            drawPath(p, NeonPink, alpha = 0.85f, style = Stroke(3f * u, cap = StrokeCap.Round))
            drawCircle(Color(0xFF0A0614), 7f * u, Offset(x + 16f * u, top + h))
            drawCircle(Color(0xFF0A0614), 7f * u, Offset(x + w - 16f * u, top + h))
        }
        SolidKind.Vending -> {
            val c = Offset(x + w / 2f, top + h * 0.45f)
            glow(NeonCyan, c, 130f * u, 0.35f)
            drawRect(Color(0xFF1A1036), Offset(x, top), Size(w, h))
            drawRect(Color(0xFFBFF6FF), Offset(x + 8f * u, top + 10f * u), Size(w * 0.62f, h * 0.6f), alpha = 0.75f)
            for (k in 0 until 4) drawRect(if (k % 2 == 0) NeonPink else NeonAmber, Offset(x + 12f * u, top + (18f + k * 20f) * u), Size(w * 0.5f, 8f * u), alpha = 0.8f)
            drawRect(NeonCyan, Offset(x, top), Size(w, h), style = Stroke(2f * u), alpha = 0.7f)
        }
    }
}

private fun DrawScope.drawShard(sh: Shard, v: View, t: Float) {
    val u = v.u
    if (sh.taken) {
        val k = t - sh.takenAt
        if (k > 0.5f) return
        val c = Offset(v.x(sh.x), v.y(sh.y))
        drawCircle(NeonCyan, (20f + 90f * k) * u, c, alpha = (1f - k * 2f) * 0.8f, style = Stroke(3f * u))
        return
    }
    val bobY = sh.y + 10f * sin(t * 2.6f + sh.x * 0.01f)
    val c = Offset(v.x(sh.x), v.y(bobY))
    glow(HaloColor, c, 75f * u, 0.9f)
    val spin = cos(t * 3f + sh.x * 0.02f)
    val hw = 14f * u * max(0.25f, abs(spin))
    val hh = 22f * u
    glow(NeonCyan, c, 60f * u, 0.55f)
    val p = Path().apply { moveTo(c.x, c.y - hh); lineTo(c.x + hw, c.y); lineTo(c.x, c.y + hh); lineTo(c.x - hw, c.y); close() }
    drawPath(p, Color(0xFFDFFBFF))
    drawPath(p, NeonCyan, style = Stroke(2f * u))
}

private fun DrawScope.drawExit(exitX: Float, v: View, t: Float, text: TextCache) {
    val u = v.u
    val l = v.x(exitX - 110f); val r = v.x(exitX + 110f); val top = v.y(380f)
    val field = Brush.verticalGradient(listOf(NeonCyan.copy(alpha = 0.1f), NeonCyan.copy(alpha = 0.35f)), startY = top, endY = v.gy)
    drawRect(field, Offset(l, top), Size(r - l, v.gy - top))
    for (k in 0 until 6) {
        val yy = top + ((t * 0.5f + k / 6f) % 1f) * (v.gy - top)
        drawRect(NeonCyan, Offset(l, yy), Size(r - l, 3f * u), alpha = 0.5f)
    }
    for (x in listOf(l - 30f * u, r)) {
        drawRect(Color(0xFF1A1036), Offset(x, top - 20f * u), Size(30f * u, v.gy - top + 20f * u))
        drawRect(NeonPink, Offset(x, top - 20f * u), Size(30f * u, v.gy - top + 20f * u), style = Stroke(2.5f * u))
    }
    drawRect(Color(0xFF1A1036), Offset(l - 50f * u, top - 70f * u), Size(r - l + 100f * u, 50f * u))
    val layout = text.get("EXIT // SECTOR 02", 30f * u)
    val c = Offset((l + r) / 2f, top - 45f * u)
    glow(NeonPink, c, 260f * u, 0.5f)
    drawText(layout, NeonPink, Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f), alpha = flicker(911, t))
}

// ------------------------------------------------------------------ steam

/** Soft rising puffs from street vents; in ASCII they become drifting clouds of glyphs. */
private fun DrawScope.drawSteam(s: NeonState, v: View) {
    val u = v.u
    for (p in s.steam) {
        if (p.life <= 0f) continue
        val k = 1f - p.life / p.maxLife
        val r = (18f + 70f * k) * u
        val c = Offset(v.x(p.x), v.y(p.y))
        val a = (if (k < 0.15f) k / 0.15f else 1f - (k - 0.15f) / 0.85f) * 0.22f
        glow(Color(0xFFE8DDFF), c, r, a)
    }
}

// ------------------------------------------------------------------ the ronin

private val Robe = Color(0xFF5A3D2A)
private val RobeDark = Color(0xFF3A2619)
private val Tunic = Color(0xFFE2D2AE)
private val TunicShade = Color(0xFFB59F78)
private val Leather = Color(0xFF6B4528)
private val Boot = Color(0xFF1E1410)
private val Trousers = Color(0xFF4A3A30)
private val Steel2 = Color(0xFFC4C8D4)
private val SaberBlue = Color(0xFF4FB8FF)
private val BoltRed = Color(0xFFFF4A3A)
const val BLADE_LEN = 108f

/** Blade direction in radians from straight up toward the facing side. */
fun saberAngle(r: Runner): Float {
    if (r.swingT < 0f) return 0.55f + 0.06f * sin(r.phase)
    val k = (r.swingT / SWING_TIME).coerceIn(0f, 1f)
    val e = 1f - (1f - k) * (1f - k) * (1f - k)
    // Combo: overhead slash, rising upswing, then a full spinning finisher.
    return when (r.comboStep) {
        2 -> 2.7f - 3.6f * e
        3 -> -1.1f + 7.4f * e
        else -> -1.1f + 3.9f * e
    }
}

/** Saber hand in rig space (u, unscaled), relative to the feet. */
private fun saberHand(r: Runner, shoulder: Offset): Offset {
    val a = if (r.swingT >= 0f) saberAngle(r) * 0.85f + 0.3f else 2.0f
    return Offset(shoulder.x + sin(a) * 40f, shoulder.y + cos(a) * 40f)
}

/** World position (level u, y up) of the blade's middle and tip, used for light and hits. */
fun saberPoints(r: Runner): FloatArray {
    val s = RUNNER_SCALE
    val shoulder = Offset(0f, 124f)
    val h = saberHand(r, shoulder)
    val a = saberAngle(r)
    val f = r.facing
    val tipX = h.x + sin(a) * BLADE_LEN
    val tipY = h.y + cos(a) * BLADE_LEN
    return floatArrayOf(
        r.x + f * (h.x + tipX) * 0.5f * s, r.y + (h.y + tipY) * 0.5f * s,
        r.x + f * tipX * s, r.y + tipY * s,
    )
}

/**
 * The ronin, posed procedurally from the physics state: a Jedi-like wanderer with a deep hood,
 * layered tunic, long cloak, a blue saber in the front hand and a blaster in the back hand.
 * The city layer only gets [haloOnly]: a dark patch that clears the city's glyphs behind them. The
 * body itself is drawn alone on the finer ASCII layer, so its letters never mix with the city's.
 */
fun DrawScope.drawRunner(s: NeonState, haloOnly: Boolean = false) {
    val r = s.runner
    val v = View(s.camera, s.u, s.groundY)
    val u = s.u * RUNNER_SCALE // the rig is authored at 150u tall
    val f = r.facing.toFloat()
    val sx = v.x(r.x)
    val squash = 1f - r.landSquash * 1.1f
    val feetY = v.y(r.y)
    // Local rig space: lx forward (facing), ly up, in u.
    fun p(lx: Float, ly: Float) = Offset(sx + lx * f * u, feetY - ly * u * squash)
    fun path(pts: List<Offset>) = Path().apply {
        moveTo(p(pts[0].x, pts[0].y).x, p(pts[0].x, pts[0].y).y)
        for (i in 1 until pts.size) { val q = p(pts[i].x, pts[i].y); lineTo(q.x, q.y) }
        close()
    }

    val run = if (r.onGround) r.speedNorm else 0f
    val ph = r.phase
    val air = !r.onGround
    val t = s.t

    var thighA: Float; var kneeA: Float; var thighB: Float; var kneeB: Float
    if (air) {
        if (r.vy > 0f) { thighA = 1.0f; kneeA = -1.7f; thighB = -0.35f; kneeB = -0.7f } else { thighA = 0.45f; kneeA = -0.6f; thighB = -0.15f; kneeB = -0.35f }
    } else {
        thighA = run * 0.85f * sin(ph) + 0.06f
        thighB = run * 0.85f * sin(ph + PI.toFloat()) - 0.06f
        kneeA = -(0.12f + run * (0.95f * max(0f, sin(ph + 1.7f)) + 0.2f))
        kneeB = -(0.12f + run * (0.95f * max(0f, sin(ph + 1.7f + PI.toFloat())) + 0.2f))
    }
    val lean = run * 0.2f + (if (air) 0.08f else 0f) + (if (r.swingT >= 0f) 0.12f else 0f)
    val hipH = 74f - run * 5f * abs(sin(ph)) + (if (!air && run < 0.05f) 1.2f * sin(t * 2.2f) else 0f)
    val hip = Offset(0f, hipH)

    fun limb(origin: Offset, a1: Float, l1: Float, a2: Float, l2: Float): Pair<Offset, Offset> {
        val k = Offset(origin.x + sin(a1) * l1, origin.y - cos(a1) * l1)
        val e = Offset(k.x + sin(a1 + a2) * l2, k.y - cos(a1 + a2) * l2)
        return k to e
    }

    val (kneeBp, footBp) = limb(hip, thighB, 38f, kneeB, 38f)
    val (kneeAp, footAp) = limb(hip, thighA, 38f, kneeA, 38f)
    val shoulder = Offset(hip.x + sin(lean) * 50f, hip.y + cos(lean) * 50f)
    val head = Offset(shoulder.x + sin(lean) * 15f + 2f, shoulder.y + 19f)

    // Blaster arm (far side): hangs, or snaps forward to aim.
    val aim = (r.aimT / SHOT_POSE_TIME).coerceIn(0f, 1f)
    val swingArm = if (air) -1.6f else -run * 0.8f * sin(ph)
    val gunArm = swingArm * (1f - aim) + 1.57f * aim
    val (gunElbow, gunHand) = limb(Offset(shoulder.x - 4f, shoulder.y), gunArm, 27f, 0.45f * (1f - aim), 26f)
    // Saber arm (near side).
    val sHand = saberHand(r, shoulder)
    val sElbow = Offset((shoulder.x + sHand.x) / 2f + 6f, (shoulder.y + sHand.y) / 2f - 8f)
    val blade = saberAngle(r)

    if (haloOnly) {
        glowOval(HaloColor, p(0f, 85f), 150f * u, 175f * u, 1f)
        return
    }
    // Blink while invulnerable after a hit.
    if (r.invuln > 0f && r.dashT <= 0f && (t * 18f).toInt() % 2 == 0) return

    // Saber light on the surroundings.
    val tipL = Offset(sHand.x + sin(blade) * BLADE_LEN, sHand.y + cos(blade) * BLADE_LEN)
    val midL = Offset((sHand.x + tipL.x) / 2f, (sHand.y + tipL.y) / 2f)
    glow(SaberBlue, p(midL.x, midL.y), 110f * u, 0.3f)

    // ---- far side: back leg, cloak, blaster arm
    drawLine(Trousers, p(hip.x, hip.y), p(kneeBp.x, kneeBp.y), 10f * u, StrokeCap.Round)
    drawLine(Boot, p(kneeBp.x, kneeBp.y), p(footBp.x, footBp.y), 10f * u, StrokeCap.Round)
    drawLine(Boot, p(footBp.x - 3f, footBp.y), p(footBp.x + 11f, footBp.y), 6f * u, StrokeCap.Round)

    // Long cloak, trailing and tattered at the hem.
    val trail = r.vx * f / 340f
    val sway = sin(t * 7f) * 3f
    val hemY = 16f + 6f * abs(trail)
    val cloak = Path().apply {
        val a = p(shoulder.x - 14f, shoulder.y + 6f); moveTo(a.x, a.y)
        val b = p(shoulder.x + 6f, shoulder.y + 4f); lineTo(b.x, b.y)
        val c = p(hip.x - 4f, hip.y + 10f); lineTo(c.x, c.y)
        // Ragged hem from front to back.
        val front = 2f
        val back = -34f - trail * 30f
        for (k in 0..6) {
            val fx = front + (back - front) * k / 6f
            val fy = hemY + (if (k % 2 == 0) 0f else 9f) + sway * k / 6f + trail * 10f * k / 6f
            val q = p(fx, fy); lineTo(q.x, q.y)
        }
        val d = p(shoulder.x - 26f - trail * 10f, shoulder.y - 30f); lineTo(d.x, d.y)
        close()
    }
    drawPath(cloak, RobeDark)
    drawPath(cloak, Robe, style = Stroke(1.5f * u, join = StrokeJoin.Round))

    drawLine(Robe, p(shoulder.x - 4f, shoulder.y), p(gunElbow.x, gunElbow.y), 8f * u, StrokeCap.Round)
    drawLine(Robe, p(gunElbow.x, gunElbow.y), p(gunHand.x, gunHand.y), 9f * u, StrokeCap.Round)
    // Blaster: body, barrel, emitter.
    val gunDir = gunArm + 0.45f * (1f - aim) // forearm direction from down, toward facing
    val gx = sin(gunDir); val gy = -cos(gunDir)
    val gunEnd = Offset(gunHand.x + gx * 24f, gunHand.y + gy * 24f)
    drawLine(Color(0xFF2B2B33), p(gunHand.x, gunHand.y), p(gunEnd.x, gunEnd.y), 7f * u, StrokeCap.Square)
    drawLine(Color(0xFF55556A), p(gunHand.x - gy * 4f, gunHand.y + gx * 4f), p(gunHand.x - gy * 4f - gx * 5f, gunHand.y + gx * 4f - gy * 5f), 6f * u)
    drawCircle(BoltRed, 2.2f * u, p(gunEnd.x, gunEnd.y), alpha = 0.9f)
    if (r.aimT > SHOT_POSE_TIME - 0.08f) {
        glow(BoltRed, p(gunEnd.x + gx * 6f, gunEnd.y + gy * 6f), 40f * u, 0.9f)
        drawCircle(Color.White, 5f * u, p(gunEnd.x + gx * 4f, gunEnd.y + gy * 4f))
    }

    // ---- body: near leg, tunic, belt
    drawLine(Trousers, p(hip.x, hip.y), p(kneeAp.x, kneeAp.y), 10.5f * u, StrokeCap.Round)
    drawLine(Boot, p(kneeAp.x, kneeAp.y), p(footAp.x, footAp.y), 10.5f * u, StrokeCap.Round)
    drawLine(Boot, p(footAp.x - 3f, footAp.y), p(footAp.x + 12f, footAp.y), 6.5f * u, StrokeCap.Round)

    val torso = path(listOf(
        Offset(shoulder.x - 12f, shoulder.y + 2f), Offset(shoulder.x + 11f, shoulder.y),
        Offset(hip.x + 13f, hip.y + 4f), Offset(hip.x - 12f, hip.y + 4f),
    ))
    drawPath(torso, Tunic)
    // Crossover fold and shading of the layered tunic.
    drawLine(TunicShade, p(shoulder.x + 9f, shoulder.y - 1f), p(hip.x - 4f, hip.y + 14f), 2.5f * u)
    drawLine(TunicShade, p(shoulder.x - 10f, shoulder.y), p(hip.x - 11f, hip.y + 6f), 3f * u)
    // Tabard panel swinging between the legs.
    val tabSwing = -trail * 8f + sin(t * 8f) * 1.5f
    drawPath(path(listOf(Offset(hip.x - 6f, hip.y + 2f), Offset(hip.x + 9f, hip.y + 2f), Offset(hip.x + 7f + tabSwing, hip.y - 34f), Offset(hip.x - 5f + tabSwing, hip.y - 34f))), Tunic)
    // Belt with pouches and a buckle glint.
    drawLine(Leather, p(hip.x - 13f, hip.y + 6f), p(hip.x + 14f, hip.y + 6f), 6f * u, StrokeCap.Butt)
    drawRect(Leather, p(hip.x - 12f, hip.y + 2f).let { Offset(minOf(it.x, it.x - 6f * f * u), it.y) }, Size(6f * u, 9f * u))
    drawCircle(Color(0xFFE8D9A0), 2f * u, p(hip.x + 8f, hip.y + 6f))
    // A comlink LED on the belt.
    if ((t * 1.5f) % 1f < 0.5f) drawCircle(NeonCyan, 1.4f * u, p(hip.x - 2f, hip.y + 6f))

    // Hood: deep cowl with a shadowed face and faint eye glints.
    val headS = p(head.x, head.y)
    val hood = path(listOf(
        Offset(head.x - 17f, head.y - 14f), Offset(head.x - 18f, head.y + 6f), Offset(head.x - 8f, head.y + 20f),
        Offset(head.x + 8f, head.y + 19f), Offset(head.x + 17f, head.y + 4f), Offset(head.x + 14f, head.y - 12f),
    ))
    drawPath(hood, Robe)
    // Shadowed face under the cowl's rim (darker toward the front), with one eye catching the light.
    drawOval(Color(0xFF2A1A12), Offset(headS.x - (if (f > 0) 1f else 12f) * u, headS.y - 9f * u), Size(13f * u, 14f * u))
    drawOval(Color(0xFF140C08), Offset(headS.x + (if (f > 0) 4f else -10f) * u, headS.y - 6f * u), Size(6f * u, 9f * u))
    drawCircle(NeonCyan, 1.4f * u, p(head.x + 8f, head.y + 1f), alpha = 0.9f)
    drawPath(hood, RobeDark, style = Stroke(1.5f * u, join = StrokeJoin.Round))
    // Cloak collar over the shoulders.
    drawPath(path(listOf(Offset(shoulder.x - 16f, shoulder.y + 6f), Offset(shoulder.x + 12f, shoulder.y + 6f), Offset(shoulder.x + 8f, shoulder.y - 8f), Offset(shoulder.x - 14f, shoulder.y - 10f))), Robe)

    // ---- saber: swing trail, blade, hilt, arm
    val handS = p(sHand.x, sHand.y)
    if (r.swingT >= 0f) {
        for (k in r.bladeTrail.indices) {
            val a = r.bladeTrail[k]
            val tip = p(sHand.x + sin(a) * BLADE_LEN, sHand.y + cos(a) * BLADE_LEN)
            drawLine(SaberBlue, handS, tip, 10f * u, StrokeCap.Round, alpha = 0.22f * (1f - k / r.bladeTrail.size.toFloat()))
        }
    }
    val tipS = p(tipL.x, tipL.y)
    val baseS = p(sHand.x + sin(blade) * 10f, sHand.y + cos(blade) * 10f)
    drawLine(SaberBlue, baseS, tipS, 15f * u, StrokeCap.Round, alpha = 0.32f)
    drawLine(SaberBlue, baseS, tipS, 7.5f * u, StrokeCap.Round, alpha = 0.8f)
    drawLine(Color.White, baseS, tipS, 3.2f * u, StrokeCap.Round)
    val hiltEnd = p(sHand.x - sin(blade) * 9f, sHand.y - cos(blade) * 9f)
    drawLine(Steel2, hiltEnd, baseS, 5f * u, StrokeCap.Butt)
    drawLine(Color(0xFF1A1A20), p(sHand.x - sin(blade) * 3f, sHand.y - cos(blade) * 3f), p(sHand.x + sin(blade) * 4f, sHand.y + cos(blade) * 4f), 5.5f * u, StrokeCap.Butt)
    drawLine(Robe, p(shoulder.x + 2f, shoulder.y), p(sElbow.x, sElbow.y), 8.5f * u, StrokeCap.Round)
    drawLine(Robe, p(sElbow.x, sElbow.y), handS, 9.5f * u, StrokeCap.Round)
    drawCircle(Color(0xFFC79A7A), 3.2f * u, handS)

    s.canopyCenter = Offset(headS.x, headS.y - 26f * u)
}

/** Double-jump rings and other short bursts. */
fun DrawScope.drawBursts(s: NeonState) {
    val u = s.u
    for (b in s.bursts) {
        if (b.age >= b.life) continue
        val k = b.age / b.life
        val c = Offset((b.x - s.camera) * u, s.groundY - b.y * u)
        val rx = (20f + 90f * k) * u
        drawOval(b.color, Offset(c.x - rx, c.y - rx * 0.3f), Size(rx * 2f, rx * 0.6f), alpha = (1f - k) * 0.9f, style = Stroke(3f * u))
    }
}

// ------------------------------------------------------------------ depth layers

/** Hanging signs in front of the runner (parallax 1.35). */
fun DrawScope.drawForeground(s: NeonState, text: TextCache) {
    val u = s.u
    val cam = s.camera * 1.35f
    val wu = size.width / u
    val spacing = 1100f
    var k = kotlin.math.floor((cam - 200f) / spacing).toInt()
    while (k * spacing - cam < wu + 200f) {
        val wx = k * spacing + hash(k, 5) * 400f
        val x = (wx - cam) * u
        run {
            // A sign hanging from a cable.
            val c = if (hash(k, 8) < 0.5f) NeonPink else NeonViolet
            val top = s.groundY - (760f + 200f * hash(k, 9)) * u
            drawLine(Color(0xFF07040E), Offset(x - 120f * u, 0f), Offset(x, top), 3f * u)
            drawLine(Color(0xFF07040E), Offset(x + 120f * u, 0f), Offset(x + 70f * u, top), 3f * u)
            val layout = text.get(if (hash(k, 10) < 0.5f) "未来" else "夢", 46f * u)
            val box = Rect(x - 10f * u, top, x + 80f * u, top + 110f * u)
            val center = box.center
            glow(c, center, 160f * u, 0.35f)
            drawRect(Color(0xFF07040E), box.topLeft, box.size)
            drawRect(c, box.topLeft, box.size, style = Stroke(2.5f * u))
            drawText(layout, c, Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f), alpha = flicker(k * 31, s.t))
        }
        k++
    }
}

/** Power cables strung across the near street, between building tops. */
fun DrawScope.drawCables(s: NeonState) {
    val u = s.u
    val cam = s.camera * 0.85f
    val wu = size.width / u
    val spacing = 520f
    var k = kotlin.math.floor((cam - spacing) / spacing).toInt()
    while (k * spacing - cam < wu + spacing) {
        val x0 = (k * spacing - cam) * u
        val x1 = x0 + spacing * u
        val y0 = s.groundY - (620f + 220f * hash(k, 1)) * u
        val y1 = s.groundY - (620f + 220f * hash(k + 1, 1)) * u
        val sag = (60f + 50f * hash(k, 2)) * u
        val path = Path().apply { moveTo(x0, y0); quadraticTo((x0 + x1) / 2f, maxOf(y0, y1) + sag, x1, y1) }
        drawPath(path, Color(0xFF0A0616), style = Stroke(2.5f * u))
        if (hash(k, 3) < 0.6f) {
            // String lights.
            for (j in 1 until 8) {
                val f = j / 8f
                val px = x0 + (x1 - x0) * f
                val py = (1 - f) * (1 - f) * y0 + 2 * (1 - f) * f * (maxOf(y0, y1) + sag) + f * f * y1
                val col = if ((j + k) % 3 == 0) NeonAmber else if ((j + k) % 3 == 1) NeonPink else NeonCyan
                drawCircle(col, 3f * u, Offset(px, py + 4f * u), alpha = 0.9f)
            }
        }
        k++
    }
}

/** A holographic koi swimming between the towers. */
fun DrawScope.drawKoi(s: NeonState) {
    val u = s.u
    val t = s.t
    val span = size.width / u + 1200f
    val headXu = ((t * 45f - s.camera * 0.4f) % span + span) % span - 600f
    val baseY = s.groundY - (700f + 60f * sin(t * 0.4f)) * u
    val flick = if (hash(7, (t * 10f).toInt()) < 0.08f) 0.4f else 1f
    for (i in 13 downTo 0) {
        val px = (headXu - i * 15f) * u
        val py = baseY + sin(t * 3.2f - i * 0.45f) * (3f + i * 1.1f) * u
        val rr = (17f * (1f - i / 15f) + 3f) * u
        val col = if (i % 4 == 1) Color(0xFFFFFFFF) else if (i < 7) Color(0xFFFF7A3D) else NeonPink
        drawCircle(col, rr, Offset(px, py), alpha = 0.42f * flick)
        if (i == 13) {
            val tail = Path().apply {
                moveTo(px, py)
                lineTo(px - 34f * u, py - 22f * u + sin(t * 6f) * 8f * u)
                lineTo(px - 28f * u, py + 22f * u + sin(t * 6f) * 8f * u)
                close()
            }
            drawPath(tail, NeonPink, alpha = 0.4f * flick)
        }
        if (i == 3) {
            drawOval(NeonCyan, Offset(px - 10f * u, py + 6f * u), Size(26f * u, 10f * u), alpha = 0.35f * flick)
            drawOval(NeonCyan, Offset(px - 10f * u, py - 16f * u), Size(26f * u, 10f * u), alpha = 0.35f * flick)
        }
    }
    val head = Offset(headXu * u, baseY)
    glow(NeonPink, head, 200f * u, 0.25f * flick)
}
