package com.zain.neonascii.city

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.zain.neonascii.NeonState
import com.zain.neonascii.game.drawBursts
import com.zain.neonascii.game.drawEnemyHalos
import com.zain.neonascii.game.drawCables
import com.zain.neonascii.game.drawForeground
import com.zain.neonascii.game.drawKoi
import com.zain.neonascii.game.drawLevel
import com.zain.neonascii.game.drawRunner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Caches measured sign text per size; signs redraw every frame. */
class TextCache(private val measurer: TextMeasurer, private val font: FontFamily) {
    private val map = HashMap<String, TextLayoutResult>()
    private val px = Density(1f, 1f)

    fun get(text: String, sizePx: Float): TextLayoutResult {
        val size = sizePx.toInt().coerceAtLeast(4)
        return map.getOrPut("$size|$text") {
            measurer.measure(text, TextStyle(color = Color.White, fontFamily = font, fontSize = size.sp), density = px)
        }
    }
}

private val Transparent = Color(0x00000000)
private val HazeColor = Color(0xFF1E2A44)
private val billboardWords = listOf("NEON", "未来", "ASCII", "2088", "夢", "電脳")
private val billboardPalette = listOf(NeonPink, NeonViolet, NeonCyan, NeonAmber, NeonPink)

/** Draws the full scene that the ASCII shader then turns into glyphs. */
fun DrawScope.drawCity(s: NeonState, text: TextCache) {
    val u = s.u
    val gy = s.groundY
    val w = size.width
    val h = size.height
    val t = s.t
    s.sprites.beginFrame(s.spriteU)

    // Sky.
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF010208), 0.45f to Color(0xFF060A1C), 0.8f to Color(0xFF16203A), 1f to Color(0xFF3A3252),
            startY = 0f, endY = gy,
        ),
        size = Size(w, gy),
    )
    if (s.flash > 0f) drawRect(Color(0xFFB9A8FF), size = Size(w, gy), alpha = s.flash * 0.55f)

    // Moon behind drifting clouds.
    val moon = Offset(w * 0.7f, gy * 0.2f)
    glow(Color(0xFFB8C8FF), moon, 260f * u, 0.32f)
    drawCircle(Color(0xFFEAF0FF), 58f * u, moon, alpha = 0.92f)
    drawClouds(s, front = false)

    val layers = s.city.layers
    drawLayer(s, layers[0], 0, text)
    haze(gy, w, u, layers[0].haze)
    drawSearchlights(s)
    drawLayer(s, layers[1], 1, text)
    haze(gy, w, u, layers[1].haze)
    drawClouds(s, front = true)
    drawKoi(s)
    s.bolts.forEach { drawBolt(it, u) }
    s.cars.forEach { if (it.scale < 0.72f) drawCar(it, u, gy, t) }
    drawLayer(s, layers[2], 2, text)
    haze(gy, w, u, layers[2].haze)
    s.cars.forEach { if (it.scale >= 0.72f) drawCar(it, u, gy, t) }
    s.ambience.drawTrain(this, s)

    // Street.
    drawRect(Color(0xFF05070E), Offset(0f, gy), Size(w, h - gy))
    drawRect(NeonCyan, Offset(0f, gy), Size(w, 2f * u), alpha = 0.18f)
    val laneY = gy + (h - gy) * 0.6f
    val dash = 130f
    var k = -((s.camera % dash) + dash)
    while (k * u < w) {
        drawRect(NeonAmber, Offset(k * u, laneY), Size(55f * u, 3f * u), alpha = 0.22f)
        k += dash
    }

    s.ambience.drawPuddles(this, s)
    drawLamps(s, groundPass = true)
    drawLayer(s, layers[3], 3, text)
    drawCables(s)
    drawLamps(s, groundPass = false)
    s.ambience.drawWalkers(this, s, haloOnly = true)
    s.ambience.drawFog(this, s, front = false)
    s.ambience.drawCop(this, s)

    // The play plane: level, runner, effects, then silhouettes in front.
    drawLevel(s, text)
    drawEnemyHalos(s)
    drawRunner(s, haloOnly = true)
    drawBursts(s)
    s.rain.drawFar(this, u)
    drawForeground(s, text)
    s.ambience.drawFlyers(this, s)
    s.ambience.drawFog(this, s, front = true)

    s.pointer?.let { p ->
        glow(Color(0xFFBFF6FF), p, 200f * u, 0.4f)
    }
    if (s.flash > 0f) drawRect(Color(0xFFD9CCFF), alpha = s.flash * 0.16f)
}

/**
 * Storm clouds: dark masses with a faint city glow underneath that burst into light from inside
 * when lightning fires nearby. In ASCII the burst turns a cloud into a block of dense bright glyphs.
 */
private fun DrawScope.drawClouds(s: NeonState, front: Boolean) {
    val u = s.u
    val gy = s.groundY
    val spanU = size.width / u + 1000f
    val body = if (front) Color(0xFF080B18) else Color(0xFF0E1224)
    for (c in s.city.clouds) {
        if (c.front != front) continue
        val sxU = ((c.x - s.camera * c.parallax - s.t * c.drift) % spanU + spanU) % spanU - 500f
        val cx = sxU * u
        val cy = gy * c.y
        val light = s.cloudLight(cx)
        val ext = cloudExtent(c)
        val sw = ext.width * u
        val sh = ext.height * u
        val uq = s.spriteU
        val sprite = s.sprites.get(c, ext.width * uq, ext.height * uq) { drawCloudBody(c, -ext.left * uq, -ext.top * uq, uq, body) }
        if (sprite != null) drawSprite(sprite, cx + ext.left * u, cy + ext.top * u, sw, sh) else drawCloudBody(c, cx, cy, u, body)
        if (light > 0.02f) {
            for (p in c.puffs) {
                val rx = p.rx * u
                val ry = p.ry * u
                val center = Offset(cx + p.dx * u, cy + p.dy * u)
                glowOval(Color(0xFF9D7BFF), center, rx * 1.1f, ry * 1.1f, 0.7f * light)
                glowOval(Color(0xFFF1EAFF), center, rx * 0.8f, ry * 0.8f, 0.95f * light)
            }
        }
    }
}

private fun cloudExtent(c: Cloud): androidx.compose.ui.geometry.Rect {
    var l = 0f; var t = 0f; var r = 0f; var b = 0f
    for (p in c.puffs) {
        l = minOf(l, p.dx - p.rx); r = maxOf(r, p.dx + p.rx)
        t = minOf(t, p.dy - p.ry); b = maxOf(b, p.dy + p.ry * 1.4f)
    }
    return androidx.compose.ui.geometry.Rect(l - 4f, t - 4f, r + 4f, b + 4f)
}

private fun DrawScope.drawCloudBody(c: Cloud, cx: Float, cy: Float, u: Float, body: Color) {
    for (p in c.puffs) {
        val rx = p.rx * u
        val ry = p.ry * u
        val center = Offset(cx + p.dx * u, cy + p.dy * u)
        // City glow catching the underside.
        drawOval(Color(0xFF3A3E62), Offset(center.x - rx, center.y - ry * 0.6f), Size(rx * 2f, ry * 2f), alpha = 0.35f)
        drawOval(body, Offset(center.x - rx, center.y - ry), Size(rx * 2f, ry * 2f), alpha = 0.93f)
    }
}

private fun DrawScope.haze(gy: Float, w: Float, u: Float, amount: Float) {
    if (amount <= 0f) return
    val top = gy - 760f * u
    drawRect(
        Brush.verticalGradient(listOf(Transparent, HazeColor.copy(alpha = amount)), startY = top, endY = gy),
        Offset(0f, top), Size(w, gy - top),
    )
}

/** Screen-left position of a world-x in u, wrapped around the looping strip. */
private fun wrapX(worldX: Float, camera: Float, worldWidth: Float, screenU: Float): Float {
    var sx = ((worldX - camera) % worldWidth + worldWidth) % worldWidth
    if (sx > screenU + 400f) sx -= worldWidth
    return sx
}

private fun DrawScope.drawLayer(s: NeonState, layer: Layer, index: Int, text: TextCache) {
    val u = s.u
    val wu = size.width / u
    val cam = s.camera * layer.parallax
    for (b in layer.buildings) {
        val sx = wrapX(b.x, cam, s.city.worldWidth, wu)
        if (sx + b.w + 60f < 0f || sx - 60f > wu) continue
        drawBuilding(s, b, layer, index, sx * u, text)
    }
}

private fun DrawScope.drawBuilding(s: NeonState, b: Building, layer: Layer, index: Int, left: Float, text: TextCache) {
    val u = s.u
    val gy = s.groundY
    val t = s.t
    val w = b.w * u
    val h = b.h * u
    val top = gy - h
    val roof = top - b.setback * u

    // Body, rim light and windows don't change: one cached image per building.
    // Rendered at a stepped scale (spriteU) and stretched, so zooming rebuilds it only a few times.
    val spriteH = h + b.setback * u
    val uq = s.spriteU
    val qH = (b.h + b.setback) * uq
    val sprite = s.sprites.get(b, b.w * uq, qH) { drawBuildingStatic(b, layer, 0f, qH, uq) }
    if (sprite != null) drawSprite(sprite, left, roof, w, spriteH) else drawBuildingStatic(b, layer, left, gy, u)

    if (b.antenna > 0f) {
        val ax = left + w * (0.35f + 0.3f * hash(b.seed, 1))
        val tip = Offset(ax, roof - b.antenna * u)
        drawLine(layer.body, Offset(ax, roof), tip, strokeWidth = 2.5f * u)
        if ((t * 1.3f + hash(b.seed, 2) * 3f) % 1.6f < 0.3f) {
            glow(NeonRed, tip, 22f * u, 0.6f)
            drawCircle(NeonRed, 3f * u, tip)
        }
    }
    if (index in 1..2 && hash(b.seed, 9) < 0.28f) {
        val fl = flicker(b.seed, t)
        drawLine(b.rim, Offset(left, top), Offset(left + w, top), strokeWidth = 7f * u, alpha = 0.22f * fl)
        drawLine(b.rim, Offset(left, top), Offset(left + w, top), strokeWidth = 2f * u, alpha = 0.9f * fl)
    }

    b.billboard?.let { drawBillboard(it, left, gy, u, t, text) }
    for (sign in b.signs) drawSign(s, sign, left, w, text)
    b.storefront?.let { drawStorefront(it, left, w, gy, u, t, text) }
}

/** Body, setback, rim light and lit windows, standing on [gy]. */
private fun DrawScope.drawBuildingStatic(b: Building, layer: Layer, left: Float, gy: Float, u: Float) {
    val w = b.w * u
    val h = b.h * u
    val top = gy - h
    drawRect(layer.body, Offset(left, top), Size(w, h + 1f))
    if (b.setback > 0f) {
        val sw = w * 0.62f
        drawRect(layer.body, Offset(left + (w - sw) / 2f, top - b.setback * u), Size(sw, b.setback * u + 1f))
    }
    val rimLeft = hash(b.seed, 8) < 0.5f
    drawRect(b.rim, Offset(if (rimLeft) left else left + w - 1.8f * u, top), Size(1.8f * u, h), alpha = 0.28f * layer.windowAlpha)

    val ws = layer.windowSize * u
    val wh = ws * 1.5f
    val gap = ws * 0.95f
    val margin = ws * 1.4f
    val bottomReserve = if (b.storefront != null) 120f * u else margin
    val cols = ((w - 2f * margin + gap) / (ws + gap)).toInt()
    val rows = ((h - margin - bottomReserve) / (wh + gap)).toInt()
    if (cols <= 0) return
    val startX = left + (w - (cols * (ws + gap) - gap)) / 2f
    for (r in 0 until rows) {
        if (hash(b.seed, r, 11) < 0.14f) continue // a dark floor
        val wy = top + margin + r * (wh + gap)
        for (c in 0 until cols) {
            val id = r * 157 + c
            if (hash(b.seed, id, 1) > layer.litRatio) continue
            val cv = hash(b.seed, c, r + 999)
            val color = when {
                cv < 0.5f -> Color(0xFFFFC777)
                cv < 0.8f -> Color(0xFFFFE9C4)
                cv < 0.93f -> Color(0xFFD8E2FF)
                else -> NeonCyan
            }
            drawRect(color, Offset(startX + c * (ws + gap), wy), Size(ws, wh), alpha = layer.windowAlpha * (0.45f + 0.55f * hash(b.seed, c, r + 77)))
        }
    }
}

private fun DrawScope.drawBillboard(bb: Billboard, left: Float, gy: Float, u: Float, t: Float, text: TextCache) {
    val x = left + bb.dx * u
    val y = gy - bb.y * u
    val bw = bb.w * u
    val bh = bb.h * u
    val phase = (t / 4f + hash(bb.seed, 1) * 6f).toInt()
    val c1 = billboardPalette[phase % billboardPalette.size]
    val c2 = billboardPalette[(phase + 1) % billboardPalette.size]
    val fl = flicker(bb.seed, t)
    val center = Offset(x + bw / 2f, y + bh / 2f)
    glow(c1, center, bw, 0.32f * fl)
    drawRect(Brush.horizontalGradient(listOf(c1, c2), startX = x, endX = x + bw), Offset(x, y), Size(bw, bh), alpha = 0.85f * fl)
    for (k in 0 until 3) {
        val yy = y + ((t * 0.35f + k / 3f) % 1f) * bh * 0.94f
        drawRect(Color.Black, Offset(x, yy), Size(bw, bh * 0.06f), alpha = 0.35f)
    }
    val layout = text.get(billboardWords[phase % billboardWords.size], bh * 0.5f)
    drawText(layout, Color.White, Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f), alpha = fl)
    drawRect(Color.White, Offset(x, y), Size(bw, bh), alpha = 0.45f * fl, style = Stroke(1.5f * u))
}

private fun DrawScope.drawSign(s: NeonState, sign: Sign, left: Float, bw: Float, text: TextCache) {
    val u = s.u
    val sw = sign.w * u
    val sh = sign.h * u
    val x = if (sign.side < 0) left - sw * 0.85f else left + bw - sw * 0.15f
    val y = s.groundY - sign.y * u
    val fl = flicker(sign.seed, s.t)
    if (fl < 0.5f && x > 0f && x < size.width) s.onSignFlicker(x + sw / 2f, y + sh)
    val center = Offset(x + sw / 2f, y + sh / 2f)
    val glowR = sh * 0.75f
    glow(sign.color, center, glowR, 0.34f * fl)
    drawRect(Color(0xFF0A0512), Offset(x, y), Size(sw, sh))
    drawRect(sign.color, Offset(x, y), Size(sw, sh), alpha = fl, style = Stroke(1.8f * u))
    val charPx = sw * 0.66f
    sign.text.forEachIndexed { i, ch ->
        val layout = text.get(ch.toString(), charPx)
        drawText(layout, sign.color, Offset(center.x - layout.size.width / 2f, y + 10f * u + i * 22f * u - layout.size.height * 0.12f), alpha = fl)
    }
}

private fun DrawScope.drawStorefront(sf: Storefront, left: Float, bw: Float, gy: Float, u: Float, t: Float, text: TextCache) {
    val sh = 82f * u
    val w = bw * 0.78f
    val x = left + (bw - w) / 2f
    val y = gy - sh
    val fl = flicker(sf.seed, t)
    glowOval(sf.color, Offset(x + w / 2f, gy + 25f * u), w * 0.75f, 55f * u, 0.45f)
    drawRect(sf.color, Offset(x, y), Size(w, sh), alpha = 0.5f)
    for (m in 1 until 4) drawRect(Color(0xFF080413), Offset(x + w * m / 4f - 2f * u, y), Size(4f * u, sh))
    val layout = text.get(sf.text, 26f * u)
    val signY = y - 48f * u
    val signW = layout.size.width + 24f * u
    val signX = x + (w - signW) / 2f
    val center = Offset(signX + signW / 2f, signY + 20f * u)
    glow(sf.color, center, signW * 0.7f, 0.4f * fl)
    drawRect(Color(0xFF0A0512), Offset(signX, signY), Size(signW, 40f * u))
    drawRect(sf.color, Offset(signX, signY), Size(signW, 40f * u), alpha = fl, style = Stroke(2f * u))
    drawText(layout, sf.color, Offset(signX + 12f * u, center.y - layout.size.height / 2f), alpha = fl)
}

private fun DrawScope.drawLamps(s: NeonState, groundPass: Boolean) {
    val u = s.u
    val gy = s.groundY
    val wu = size.width / u
    for (lamp in s.city.lamps) {
        val sx = wrapX(lamp.x, s.camera, s.city.worldWidth, wu)
        if (sx < -120f || sx > wu + 120f) continue
        val x = sx * u
        val head = Offset(x + 32f * u, gy - 236f * u)
        val on = if (hash(lamp.seed, 1) < 0.2f) flicker(lamp.seed, s.t) else 1f
        if (groundPass) {
            glowOval(lamp.color, Offset(head.x, gy + 20f * u), 160f * u, 40f * u, 0.4f * on)
            continue
        }
        val cone = Path().apply {
            moveTo(head.x - 8f * u, head.y)
            lineTo(head.x + 8f * u, head.y)
            lineTo(head.x + 110f * u, gy)
            lineTo(head.x - 110f * u, gy)
            close()
        }
        drawPath(cone, Brush.verticalGradient(listOf(lamp.color.copy(alpha = 0.24f * on), lamp.color.copy(alpha = 0.03f)), startY = head.y, endY = gy))
        drawLine(Color(0xFF140C22), Offset(x, gy), Offset(x, gy - 240f * u), strokeWidth = 5f * u)
        drawLine(Color(0xFF140C22), Offset(x, gy - 240f * u), Offset(head.x + 6f * u, gy - 240f * u), strokeWidth = 4f * u)
        glow(lamp.color, head, 70f * u, 0.6f * on)
        drawCircle(Color.White, 5f * u, head, alpha = on)
    }
}

private fun DrawScope.drawSearchlights(s: NeonState) {
    val u = s.u
    val len = size.height * 1.5f
    for (i in 0 until 2) {
        val base = Offset(size.width * (0.22f + 0.56f * i), s.groundY - 380f * u)
        val angle = -PI.toFloat() / 2f + 0.5f * sin(s.t * 0.23f + i * 2.1f)
        val spread = 0.05f
        val dir = Offset(cos(angle), sin(angle))
        val beam = Path().apply {
            moveTo(base.x, base.y)
            lineTo(base.x + cos(angle - spread) * len, base.y + sin(angle - spread) * len)
            lineTo(base.x + cos(angle + spread) * len, base.y + sin(angle + spread) * len)
            close()
        }
        drawPath(beam, Brush.linearGradient(listOf(Color(0x339FD8FF), Transparent), start = base, end = base + dir * len))
    }
}

private fun DrawScope.drawBolt(bolt: Bolt, u: Float) {
    val i = bolt.intensity
    if (i <= 0.01f) return
    fun path(points: List<Offset>) = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (p in 1 until points.size) lineTo(points[p].x, points[p].y)
    }
    val main = path(bolt.main)
    drawPath(main, Color(0xFF9D7BFF), alpha = 0.3f * i, style = Stroke(14f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(main, Color(0xFFE2D8FF), alpha = 0.7f * i, style = Stroke(5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(main, Color.White, alpha = i, style = Stroke(2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    for (b in bolt.branches) {
        val p = path(b)
        drawPath(p, Color(0xFF9D7BFF), alpha = 0.25f * i, style = Stroke(8f * u, cap = StrokeCap.Round))
        drawPath(p, Color.White, alpha = 0.75f * i, style = Stroke(1.4f * u, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawCar(c: Car, u: Float, gy: Float, t: Float) {
    val sc = c.scale * u
    val x = c.x * u
    val y = gy - c.lane * u
    val len = 48f * sc
    val hgt = 10f * sc
    val front = x + c.dir * len / 2f
    val reach = c.dir * 170f * sc
    val cone = Path().apply {
        moveTo(front, y - 2f * sc)
        lineTo(front + reach, y - 26f * sc)
        lineTo(front + reach, y + 30f * sc)
        close()
    }
    drawPath(cone, Brush.horizontalGradient(listOf(Color(0x66FFF4D6), Transparent), startX = front, endX = front + reach))
    glowOval(c.glow, Offset(x, y + hgt * 1.1f), len * 0.7f, hgt * 1.1f, 0.55f)
    drawRoundRect(Color(0xFF221838), Offset(x - len / 2f, y - hgt / 2f), Size(len, hgt), CornerRadius(hgt / 2f))
    drawRoundRect(Color(0xFF30264E), Offset(x - len * 0.22f, y - hgt * 1.05f), Size(len * 0.44f, hgt * 0.75f), CornerRadius(hgt * 0.3f))
    drawRect(NeonCyan, Offset(x - len * 0.18f, y - hgt * 0.9f), Size(len * 0.36f, hgt * 0.22f), alpha = 0.7f)
    val rear = Offset(x - c.dir * len / 2f, y)
    glow(NeonRed, rear, 16f * sc, 0.7f)
    drawCircle(Color.White, 2.4f * sc, Offset(front, y))
    if (c.police) {
        val red = (t * 6f).toInt() % 2 == 0
        val top = Offset(x, y - hgt * 1.15f)
        val col = if (red) NeonRed else Color(0xFF3B6BFF)
        glow(col, top, 26f * sc, 0.8f)
    }
}
