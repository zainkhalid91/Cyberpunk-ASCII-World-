package com.zain.neonascii.city

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.roundToInt

// Performance helpers. Creating a gradient shader per glow and re-drawing thousands of windows
// every frame is what kept phones under 60 fps; these replace both with cached bitmaps.

private object GlowSprite {
    val image: ImageBitmap by lazy {
        val n = 128
        val img = ImageBitmap(n, n)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(n.toFloat(), n.toFloat())) {
            val c = Offset(n / 2f, n / 2f)
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0.45f), Color.Transparent), c, n / 2f), n / 2f, c)
        }
        img.markImmutable()
        img
    }
    val size = IntSize(128, 128)
    private val tints = HashMap<Color, ColorFilter>()
    fun tint(color: Color) = tints.getOrPut(color) { ColorFilter.tint(color, BlendMode.Modulate) }
}

/** A soft radial glow of [color]; the cheap replacement for a per-frame radial gradient. */
fun DrawScope.glow(color: Color, center: Offset, radius: Float, alpha: Float = 1f) =
    glowOval(color, center, radius, radius, alpha)

fun DrawScope.glowOval(color: Color, center: Offset, rx: Float, ry: Float, alpha: Float = 1f) {
    if (alpha <= 0.004f || rx < 1f || ry < 1f) return
    drawImage(
        GlowSprite.image,
        srcOffset = IntOffset.Zero, srcSize = GlowSprite.size,
        dstOffset = IntOffset((center.x - rx).roundToInt(), (center.y - ry).roundToInt()),
        dstSize = IntSize((rx * 2f).roundToInt(), (ry * 2f).roundToInt()),
        alpha = alpha.coerceAtMost(1f),
        colorFilter = GlowSprite.tint(color.copy(alpha = 1f)),
        filterQuality = FilterQuality.Low,
    )
}

/**
 * Bitmaps of static scenery (building bodies + windows, clouds), rendered once at [scale] of
 * screen resolution and redrawn as a single image each frame. Rebuilt when the scale (u) changes.
 * At most [perFrame] are built per frame so nothing hitches; until then callers draw directly.
 */
class SpriteCache(private val scale: Float = 0.5f, private val perFrame: Int = 6) {
    private val map = HashMap<Any, ImageBitmap>()
    private var unit = -1f
    private var budget = 0

    fun beginFrame(u: Float) {
        if (u != unit) { map.clear(); unit = u }
        budget = perFrame
    }

    /** [draw] paints the sprite in screen pixels within (0,0)-(wPx,hPx). */
    fun get(key: Any, wPx: Float, hPx: Float, draw: DrawScope.() -> Unit): ImageBitmap? {
        map[key]?.let { return it }
        if (budget <= 0) return null
        budget--
        val img = ImageBitmap(max(1, (wPx * scale).roundToInt()), max(1, (hPx * scale).roundToInt()))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(img.width.toFloat(), img.height.toFloat())) {
            scale(scale, scale, pivot = Offset.Zero) { draw() }
        }
        img.markImmutable()
        map[key] = img
        return img
    }
}

/** Draws a cached sprite at its full on-screen size. */
fun DrawScope.drawSprite(img: ImageBitmap, x: Float, y: Float, wPx: Float, hPx: Float) {
    drawImage(
        img,
        srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(wPx.roundToInt(), hPx.roundToInt()),
        filterQuality = FilterQuality.Low,
    )
}

/** Lets the platform reuse the pixels when drawing instead of copying them every frame. */
expect fun ImageBitmap.markImmutable()

/** Many straight lines in one native call: [pts] holds x0,y0,x1,y1 for each line; [count] floats are used. */
expect fun DrawScope.drawLineBatch(pts: FloatArray, count: Int, color: Color, alpha: Float, width: Float)
