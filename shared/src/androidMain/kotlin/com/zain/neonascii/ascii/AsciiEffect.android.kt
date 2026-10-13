package com.zain.neonascii.ascii

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect

actual fun createAsciiEffect(atlas: GlyphAtlas): AsciiEffect? = AndroidAsciiEffect(atlas)

private class AndroidAsciiEffect(private val atlas: GlyphAtlas) : AsciiEffect {
    private val shader = RuntimeShader(ASCII_SHADER).apply {
        val glyphs = BitmapShader(atlas.image.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        glyphs.filterMode = BitmapShader.FILTER_MODE_NEAREST
        setInputShader("atlas", glyphs)
    }

    override fun renderEffect(u: AsciiUniforms): RenderEffect {
        shader.setFloatUniform("resolution", u.width, u.height)
        shader.setFloatUniform("cell", u.cellW, u.cellH)
        shader.setFloatUniform("glyphCount", atlas.count.toFloat())
        shader.setFloatUniform("time", u.time)
        shader.setFloatUniform("splitX", u.splitX)
        shader.setFloatUniform("groundY", u.groundY)
        shader.setFloatUniform("glitch", u.glitch)
        shader.setFloatUniform("shock", u.shockX, u.shockY, u.shockAge)
        shader.setFloatUniform("lights", u.lights)
        shader.setFloatUniform("lightCount", u.lightCount.toFloat())
        shader.setFloatUniform("ambient", u.ambient)
        shader.setFloatUniform("opaque", if (u.opaque) 1f else 0f)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}
