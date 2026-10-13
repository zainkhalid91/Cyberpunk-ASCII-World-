package com.zain.neonascii.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.zain.neonascii.NeonState
import com.zain.neonascii.city.NeonAmber
import com.zain.neonascii.city.NeonCyan
import com.zain.neonascii.city.NeonPink
import com.zain.neonascii.city.NeonRed
import com.zain.neonascii.city.NeonLime
import com.zain.neonascii.city.glow
import com.zain.neonascii.city.glowOval
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val Armor = Color(0xFF9AA3C6)
private val ArmorLight = Color(0xFFD4DAF0)
private val Trim = Color(0xFFFF8A2A)
private val Ninja = Color(0xFF9A72C8)
private val Katana = Color(0xFFFF3FD0)
private val Hide = Color(0xFFB4B4CC)
private val White = Color.White
private val Transparent = Color(0x00000000)

/** Screen helpers for one enemy: lx forward (its facing), ly up, in u from its feet. */
private class Rig(val sx: Float, val sy: Float, val f: Float, val u: Float) {
    fun p(lx: Float, ly: Float) = Offset(sx + lx * f * u, sy - ly * u)
}

/** Dark patches in the city layer behind every enemy, so the hero layer's glyphs read cleanly. */
fun DrawScope.drawEnemyHalos(s: NeonState) {
    val u = s.u
    for (e in s.combat.enemies) {
        if (!e.alive) continue
        val c = Offset((e.x - s.camera) * u, s.groundY - e.centerY * u)
        if (c.x < -300f || c.x > size.width + 300f) continue
        glowOval(Color(0xFF040010), c, (e.halfW + 70f) * u, (e.height * 0.5f + 60f) * u, 0.9f)
    }
    for (a in s.level.arenas) if (a == s.combat.activeArena) {
        for (bx in listOf(a.x0 + 30f, a.x1 - 30f)) glowOval(Color(0xFF040010), Offset((bx - s.camera) * u, s.groundY - 200f * u), 60f * u, 240f * u, 0.8f)
    }
}

/** Enemies, on the bright hero layer. */
fun DrawScope.drawEnemies(s: NeonState) {
    val u = s.u
    val t = s.t
    for (e in s.combat.enemies) {
        if (!e.alive) continue
        val sx = (e.x - s.camera) * u
        if (sx < -300f || sx > size.width + 300f) continue
        val r = Rig(sx, s.groundY - e.y * u, e.facing.toFloat(), u * 1.15f)
        val flash = e.hitFlash > 0f
        // Warp-in: a column of light that the enemy materialises inside.
        if (e.age < 0.45f) {
            val k = e.age / 0.45f
            drawRect(Brush.verticalGradient(listOf(Transparent, NeonCyan.copy(alpha = 0.7f * (1f - k)), Transparent), startY = r.sy - (e.height + 120f) * u, endY = r.sy), Offset(sx - 30f * u, r.sy - (e.height + 120f) * u), Size(60f * u, (e.height + 120f) * u))
        }
        when (e.kind) {
            EnemyKind.Trooper -> drawTrooper(e, r, flash, t)
            EnemyKind.Blade -> drawBlade(e, r, flash, t)
            EnemyKind.Hound -> drawHound(e, r, flash)
            EnemyKind.Drone -> drawDrone(e, r, flash, t)
        }
    }
}

private fun DrawScope.drawTrooper(e: Enemy, r: Rig, flash: Boolean, t: Float) {
    val u = r.u
    val body = if (flash) White else Armor
    val light = if (flash) White else ArmorLight
    val walk = if (abs(e.vx) > 5f) sin(e.walkPhase * 3f) else 0f
    val aiming = e.state == EState.Aim || e.state == EState.Fire
    // Legs.
    drawLine(body, r.p(-4f, 70f), r.p(-6f - walk * 14f, 0f), 11f * u, StrokeCap.Round)
    drawLine(light, r.p(4f, 70f), r.p(6f + walk * 14f, 0f), 11f * u, StrokeCap.Round)
    // Torso armor with orange trim.
    val torso = Path().apply {
        val a = r.p(-16f, 72f); moveTo(a.x, a.y)
        val b = r.p(16f, 72f); lineTo(b.x, b.y)
        val c = r.p(18f, 128f); lineTo(c.x, c.y)
        val d = r.p(-18f, 130f); lineTo(d.x, d.y); close()
    }
    drawPath(torso, body)
    drawLine(if (flash) White else Trim, r.p(-16f, 100f), r.p(17f, 100f), 3f * u)
    drawLine(light, r.p(-18f, 128f), r.p(18f, 126f), 6f * u, StrokeCap.Round) // shoulders
    // Helmet with a glowing visor slit.
    drawCircle(body, 15f * u, r.p(2f, 146f))
    drawLine(if (flash) White else NeonRed, r.p(-2f, 147f), r.p(15f, 147f), 3.5f * u, StrokeCap.Round)
    glow(NeonRed, r.p(10f, 147f), 26f * u, 0.6f)
    // Rifle: held across, raised when aiming.
    val gunY = if (aiming) 112f else 98f
    val gunEnd = r.p(if (aiming) 62f else 46f, gunY + (if (aiming) 0f else -10f))
    drawLine(light, r.p(10f, 116f), r.p(18f, gunY), 7f * u, StrokeCap.Round)
    drawLine(Color(0xFF5A5E78), r.p(6f, gunY), gunEnd, 7f * u)
    drawLine(if (flash) White else Trim, r.p(20f, gunY + 3f), r.p(34f, gunY + 3f), 2f * u)
    if (e.state == EState.Fire && e.shotTimer > 0.07f) { glow(NeonRed, gunEnd, 34f * u, 0.9f); drawCircle(White, 5f * u, gunEnd) }
}

private fun DrawScope.drawBlade(e: Enemy, r: Rig, flash: Boolean, t: Float) {
    val u = r.u
    val cloth = if (flash) White else Ninja
    val crouch = if (e.state == EState.Windup || e.state == EState.Approach) 10f else 0f
    val run = if (e.state == EState.Approach) sin(e.walkPhase * 3f) else if (abs(e.vx) > 5f) sin(e.walkPhase * 3f) * 0.6f else 0f
    // Legs in a wide stance.
    drawLine(cloth, r.p(0f, 70f - crouch), r.p(-18f - run * 16f, 0f), 10f * u, StrokeCap.Round)
    drawLine(cloth, r.p(0f, 70f - crouch), r.p(20f + run * 16f, 0f), 10f * u, StrokeCap.Round)
    // Body, leaning in.
    val lean = if (e.state == EState.Slash) 16f else crouch
    drawLine(cloth, r.p(0f, 70f - crouch), r.p(lean, 128f - crouch), 18f * u, StrokeCap.Round)
    // Scarf trailing behind.
    val scarf = Path().apply {
        val a = r.p(lean - 4f, 132f - crouch); moveTo(a.x, a.y)
        for (k in 1..5) { val q = r.p(lean - 4f - k * 14f, 132f - crouch + sin(t * 9f + k) * 5f - k * 2f); lineTo(q.x, q.y) }
    }
    drawPath(scarf, if (flash) White else NeonRed, style = Stroke(4f * u, cap = StrokeCap.Round))
    // Head with a mask and a single glowing eye.
    drawCircle(cloth, 13f * u, r.p(lean + 2f, 146f - crouch))
    drawCircle(if (flash) White else Katana, 2.5f * u, r.p(lean + 9f, 148f - crouch))
    // Energy katana: guard (upright), windup (raised back, with a glint), slash (sweeping arc).
    val hand = r.p(lean + 14f, 106f - crouch)
    val ang = when (e.state) {
        EState.Windup -> -2.4f
        EState.Slash -> -2.4f + 4.0f * min(1f, e.stateT / 0.16f)
        EState.Recover -> 1.9f
        EState.Stagger -> 2.6f
        else -> -0.25f // guard: blade up in front
    }
    val tip = Offset(hand.x + sin(ang) * 92f * u * r.f, hand.y - cos(ang) * 92f * u)
    drawLine(Katana, hand, tip, 12f * u, StrokeCap.Round, alpha = 0.3f)
    drawLine(Katana, hand, tip, 5f * u, StrokeCap.Round, alpha = 0.85f)
    drawLine(White, hand, tip, 2f * u, StrokeCap.Round)
    if (e.state == EState.Windup) {
        val g = 0.5f + 0.5f * sin(e.stateT * 40f)
        glow(White, tip, 30f * u, g)
        drawLine(White, Offset(tip.x - 14f * u, tip.y), Offset(tip.x + 14f * u, tip.y), 2f * u, alpha = g)
        drawLine(White, Offset(tip.x, tip.y - 14f * u), Offset(tip.x, tip.y + 14f * u), 2f * u, alpha = g)
    }
    if (e.state == EState.Slash) {
        val arc = Path().apply {
            for (k in 0..8) {
                val a = -2.4f + 4.0f * min(1f, e.stateT / 0.16f) * k / 8f
                val q = Offset(hand.x + sin(a) * 96f * u * r.f, hand.y - cos(a) * 96f * u)
                if (k == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
            }
        }
        drawPath(arc, Katana, alpha = 0.5f, style = Stroke(10f * u, cap = StrokeCap.Round))
    }
    drawLine(cloth, r.p(lean + 2f, 124f - crouch), hand, 7f * u, StrokeCap.Round)
}

private fun DrawScope.drawHound(e: Enemy, r: Rig, flash: Boolean) {
    val u = r.u
    val hide = if (flash) White else Hide
    val crouch = if (e.state == EState.Crouch) 12f else 0f
    val leap = e.state == EState.Leap
    val g = e.walkPhase * 4f
    val bodyY = 44f - crouch
    // Four legs: scissor gait, tucked in a leap.
    val legs = listOf(-24f to 0f, -16f to 3.1f, 22f to 1.5f, 30f to 4.6f)
    for ((lx, ph) in legs) {
        val swing = if (leap) 0.9f else sin(g + ph) * 0.6f
        val hip = r.p(lx, bodyY)
        val foot = if (leap) r.p(lx - 18f, bodyY - 22f) else r.p(lx + sin(swing) * 18f, 0f)
        drawLine(hide, hip, foot, 5f * u, StrokeCap.Round)
    }
    drawOval(hide, r.p(-34f, bodyY + 14f).let { Offset(minOf(it.x, r.p(36f, 0f).x), it.y) }, Size(70f * u, 26f * u))
    drawLine(if (flash) White else Trim, r.p(-24f, bodyY + 12f), r.p(22f, bodyY + 12f), 2.5f * u)
    // Head, jaw and eye.
    val head = r.p(44f, bodyY + 18f + (if (leap) 6f else 0f))
    drawCircle(hide, 13f * u, head)
    drawLine(hide, head, r.p(60f, bodyY + 10f), 8f * u, StrokeCap.Round)
    drawCircle(if (flash) White else NeonRed, 3f * u, r.p(50f, bodyY + 22f))
    glow(NeonRed, r.p(50f, bodyY + 22f), 22f * u, 0.7f)
    // Tail antenna.
    drawLine(hide, r.p(-34f, bodyY + 10f), r.p(-50f, bodyY + 30f), 2.5f * u)
    drawCircle(NeonRed, 2f * u, r.p(-50f, bodyY + 30f))
}

private fun DrawScope.drawDrone(e: Enemy, r: Rig, flash: Boolean, t: Float) {
    val u = r.u
    val x = r.sx; val y = r.sy
    val shell = if (flash) White else Color(0xFF8E86B0)
    drawOval(shell, Offset(x - 26f * u, y - 9f * u), Size(52f * u, 18f * u))
    drawOval(if (flash) White else Color(0xFF4A4460), Offset(x - 20f * u, y - 13f * u), Size(40f * u, 9f * u))
    for (side in listOf(-1f, 1f)) {
        val rx = x + side * 30f * u
        drawLine(Color(0xFF4A4460), Offset(x + side * 18f * u, y - 4f * u), Offset(rx, y - 10f * u), 3f * u)
        drawOval(Color(0xFFB8B0D0), Offset(rx - 16f * u, y - 13f * u), Size(32f * u, 5f * u), alpha = 0.35f + 0.2f * sin(t * 40f + side))
    }
    val charging = e.state == EState.Aim
    val eye = Offset(x + r.f * 12f * u, y + 1f * u)
    glow(NeonRed, eye, (if (charging) 60f else 32f) * u, if (charging) 1f else 0.7f)
    drawCircle(Color(0xFFFFB0A8), (if (charging) 6f else 3.5f) * u, eye)
}

/** Laser sights of aiming troopers: drawn in the crisp overlay so they read as thin lines. */
fun DrawScope.drawLaserSights(s: NeonState) {
    val u = s.u
    for (e in s.combat.enemies) {
        if (!e.alive || e.kind != EnemyKind.Trooper || e.state != EState.Aim) continue
        val from = Offset((e.x + e.facing * 70f - s.camera) * u, s.groundY - (e.y + 128f) * u)
        val to = Offset((e.aimX - s.camera) * u, s.groundY - e.aimY * u)
        val k = min(1f, e.stateT / 0.6f)
        drawLine(NeonRed, from, to, 1.5f * u, alpha = 0.3f + 0.6f * k)
        drawCircle(NeonRed, 4f * u * (1f + k), to, alpha = 0.6f)
    }
}

/** Holo-barriers that lock an arena. */
fun DrawScope.drawBarriers(s: NeonState) {
    val a = s.combat.activeArena ?: return
    val u = s.u
    for (bx in listOf(a.x0 + 30f, a.x1 - 30f)) {
        val x = (bx - s.camera) * u
        val top = s.groundY - 420f * u
        drawRect(Brush.verticalGradient(listOf(NeonRed.copy(alpha = 0.05f), NeonRed.copy(alpha = 0.45f)), startY = top, endY = s.groundY), Offset(x - 8f * u, top), Size(16f * u, 420f * u))
        var k = 0f
        while (k < 420f) {
            val yy = s.groundY - ((k + s.t * 120f) % 420f) * u
            drawLine(NeonRed, Offset(x - 14f * u, yy), Offset(x + 14f * u, yy), 2f * u, alpha = 0.7f)
            k += 60f
        }
        drawLine(NeonRed, Offset(x, top), Offset(x, s.groundY), 3f * u, alpha = 0.9f)
    }
}

/** Dash afterimages: cyan echoes of the ronin. */
fun DrawScope.drawGhosts(s: NeonState) {
    val r = s.runner
    if (r.ghostAge > 0.25f) return
    val u = s.u * RUNNER_SCALE
    for (i in 0 until 5) {
        val gx = r.ghosts[i * 2]; val gy = r.ghosts[i * 2 + 1]
        if (gx == 0f) continue
        val sx = (gx - s.camera) * s.u
        val sy = s.groundY - gy * s.u
        val a = (0.45f - i * 0.08f) * (1f - r.ghostAge / 0.25f)
        drawRoundRect(NeonCyan, Offset(sx - 16f * u, sy - 150f * u), Size(32f * u, 150f * u), androidx.compose.ui.geometry.CornerRadius(14f * u), alpha = max(0f, a))
    }
}

/** Bolts, enemy fire, sparks and health cells, on the hero layer. */
fun DrawScope.drawCombatFx(s: NeonState) {
    val u = s.u
    val c = s.combat
    fun sx(x: Float) = (x - s.camera) * u
    fun sy(y: Float) = s.groundY - y * u
    for (b in c.bolts) {
        val x = sx(b.x); val y = sy(b.y)
        val tail = Offset(x - b.dir * 46f * u, y)
        glow(NeonRed, Offset(x - b.dir * 20f * u, y), 34f * u, 0.7f)
        drawLine(NeonRed, tail, Offset(x, y), 8f * u, StrokeCap.Round, alpha = 0.6f)
        drawLine(Color(0xFFFFE0D8), tail, Offset(x, y), 3f * u, StrokeCap.Round)
    }
    for (b in c.bullets) {
        val x = sx(b.x); val y = sy(b.y)
        val col = if (b.reflected) NeonCyan else if (b.orb) NeonPink else Trim
        if (b.orb && !b.reflected) {
            glow(col, Offset(x, y), 30f * u, 0.9f)
            drawCircle(White, 6f * u, Offset(x, y))
        } else {
            val len = 40f * u
            val sp = max(1f, kotlin.math.sqrt(b.vx * b.vx + b.vy * b.vy))
            val tail = Offset(x - b.vx / sp * len, y + b.vy / sp * len)
            glow(col, Offset(x, y), 26f * u, 0.8f)
            drawLine(col, tail, Offset(x, y), 7f * u, StrokeCap.Round, alpha = 0.7f)
            drawLine(White, tail, Offset(x, y), 2.5f * u, StrokeCap.Round)
        }
    }
    for (p in c.pickups) {
        val x = sx(p.x); val y = sy(p.y + 6f * sin(s.t * 5f))
        glow(NeonLime, Offset(x, y), 40f * u, 0.8f)
        drawRoundRect(NeonLime, Offset(x - 10f * u, y - 14f * u), Size(20f * u, 28f * u), androidx.compose.ui.geometry.CornerRadius(4f * u))
        drawLine(White, Offset(x - 6f * u, y), Offset(x + 6f * u, y), 3f * u)
        drawLine(White, Offset(x, y - 6f * u), Offset(x, y + 6f * u), 3f * u)
    }
    for (p in c.sparks) {
        val k = p.life / p.maxLife
        drawLine(if (p.hot) NeonAmber else White, Offset(sx(p.x), sy(p.y)), Offset(sx(p.x) - p.vx * 0.02f * u, sy(p.y) + p.vy * 0.02f * u), 2.5f * u, StrokeCap.Round, alpha = k)
    }
}

