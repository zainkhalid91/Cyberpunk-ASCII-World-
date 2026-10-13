package com.zain.neonascii.ascii

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/**
 * A one-row strip of glyphs, one per cell: the brightness ramp from emptiest (space) to densest,
 * then the fixed [LINE_GLYPHS] the shader uses for edges and water, then the [MATERIAL_GLYPHS].
 */
class GlyphAtlas(val image: ImageBitmap, val glyphs: String, val cellW: Int, val cellH: Int) {
    /** Glyphs in the brightness ramp (the line and material glyphs start at this index). */
    val count: Int get() = glyphs.length - LINE_GLYPHS.length - MATERIAL_GLYPHS.length
}

/** Edge and water glyphs, in the order the shader indexes them: | - / \ ~ _ */
const val LINE_GLYPHS = "|-/\\~_"

/** Per-material sets, after the line glyphs: walls and haze ". : ; =", then neon "o O 0 @". */
const val MATERIAL_GLYPHS = ".:;=oO0@"

// Rounded, texture-like glyphs only: slashes, brackets and carets read as noise.
private const val CANDIDATES = " .,:;-~=+*icoarszxeu0OQ8%&@$#WM"

/**
 * Renders the candidates, measures how much ink each one really has in this font, and keeps
 * [rampSize] glyphs spread evenly from empty to full. Measuring instead of hard-coding a ramp keeps
 * the brightness mapping right whatever font the platform supplies.
 */
fun buildGlyphAtlas(
    measurer: TextMeasurer,
    fontFamily: FontFamily,
    cellW: Int,
    cellH: Int,
    rampSize: Int = 16,
): GlyphAtlas {
    val px = Density(1f, 1f) // 1sp == 1px, so the font is sized in pixels
    val style = TextStyle(color = Color.White, fontFamily = fontFamily, fontSize = (cellH * 0.86f).sp)

    fun render(chars: String): ImageBitmap {
        val image = ImageBitmap(cellW * chars.length, cellH)
        CanvasDrawScope().draw(px, LayoutDirection.Ltr, Canvas(image), Size(image.width.toFloat(), image.height.toFloat())) {
            chars.forEachIndexed { i, ch ->
                if (ch == ' ') return@forEachIndexed
                val layout = measurer.measure(ch.toString(), style, density = px)
                val left = i * cellW.toFloat()
                clipRect(left, 0f, left + cellW, cellH.toFloat()) {
                    drawText(
                        layout,
                        topLeft = Offset(
                            left + (cellW - layout.size.width) / 2f,
                            (cellH - layout.size.height) / 2f,
                        ),
                    )
                }
            }
        }
        return image
    }

    val probe = render(CANDIDATES)
    val pixels = IntArray(probe.width * probe.height)
    probe.readPixels(pixels)
    val ink = FloatArray(CANDIDATES.length)
    for (y in 0 until probe.height) {
        for (x in 0 until probe.width) {
            ink[x / cellW] += ((pixels[y * probe.width + x] ushr 24) and 0xFF) / 255f
        }
    }

    val sorted = CANDIDATES.indices.sortedBy { ink[it] }
    val maxInk = ink[sorted.last()]
    val used = HashSet<Int>()
    val ramp = StringBuilder(" ")
    used += CANDIDATES.indexOf(' ')
    for (k in 1 until rampSize) {
        val target = maxInk * k / (rampSize - 1)
        val pick = sorted.filter { it !in used }.minByOrNull { kotlin.math.abs(ink[it] - target) } ?: break
        used += pick
        ramp.append(CANDIDATES[pick])
    }
    val ordered = ramp.toString().toList().sortedBy { ink[CANDIDATES.indexOf(it)] }.joinToString("")
    val all = ordered + LINE_GLYPHS + MATERIAL_GLYPHS
    return GlyphAtlas(render(all), all, cellW, cellH)
}
