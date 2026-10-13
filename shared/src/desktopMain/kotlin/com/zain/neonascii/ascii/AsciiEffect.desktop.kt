package com.zain.neonascii.ascii

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

actual fun createAsciiEffect(atlas: GlyphAtlas): AsciiEffect? = SkiaAsciiEffect(atlas)

private class SkiaAsciiEffect(private val atlas: GlyphAtlas) : AsciiEffect {
    private val effect = RuntimeEffect.makeForShader(ASCII_SHADER)
    private val glyphs = Image.makeFromBitmap(atlas.image.asSkiaBitmap())
        .makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.DEFAULT)

    override fun renderEffect(u: AsciiUniforms): RenderEffect {
        // The image filter snapshots the builder, so a fresh builder per frame is the safe pattern.
        val b = RuntimeShaderBuilder(effect)
        b.uniform("resolution", u.width, u.height)
        b.uniform("cell", u.cellW, u.cellH)
        b.uniform("glyphCount", atlas.count.toFloat())
        b.uniform("time", u.time)
        b.uniform("splitX", u.splitX)
        b.uniform("groundY", u.groundY)
        b.uniform("glitch", u.glitch)
        b.uniform("shock", u.shockX, u.shockY, u.shockAge)
        b.uniform("lights", u.lights)
        b.uniform("lightCount", u.lightCount.toFloat())
        b.uniform("ambient", u.ambient)
        b.uniform("opaque", if (u.opaque) 1f else 0f)
        b.child("atlas", glyphs)
        return ImageFilter.makeRuntimeShader(b, "content", null).asComposeRenderEffect()
    }
}
