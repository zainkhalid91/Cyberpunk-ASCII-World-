package com.zain.neonascii.ascii

import androidx.compose.ui.graphics.RenderEffect

/**
 * The whole look in one pass. The scene is drawn normally into a layer; this shader cuts the layer
 * into a grid of cells, samples each cell's colour, picks a glyph from the atlas by brightness and
 * tints it with that colour. It also mirrors the city into the wet street, glitches whole rows of
 * cells, and pushes a bright ring through the grid when the user taps.
 *
 * Written in the SkSL subset that both Android (AGSL) and desktop Skia accept, so one source runs
 * on both platforms.
 */
const val ASCII_SHADER = """
uniform shader content;
uniform shader atlas;
uniform float2 resolution;
uniform float2 cell;
uniform float glyphCount;
uniform float time;
uniform float splitX;
uniform float groundY;
uniform float glitch;
uniform float3 shock;
uniform float3 lights[12];  // x, y, radius in pixels
uniform float lightCount;
uniform float ambient;      // 1 = everything lit; lower = darkness eats dim cells
uniform float opaque;       // 1 = fill every cell; 0 = keep the layer's transparency

float lu(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// The scene, plus a rippling reflection of the skyline below the street line.
float4 scene(float2 p) {
    if (p.y <= groundY) return content.eval(p);
    float d = p.y - groundY;
    float wob = sin(p.y * 0.11 + time * 2.7) * (1.5 + d * 0.05) + sin(p.y * 0.37 - time * 4.3) * 1.2;
    float2 q = float2(p.x + wob, groundY - d * 1.35 - 1.0);
    float4 refl = content.eval(q);
    float4 ground = content.eval(p);
    float fade = 1.0 - clamp(d / max(resolution.y - groundY, 1.0), 0.0, 1.0) * 0.55;
    return float4(ground.rgb + refl.rgb * 0.5 * fade, 1.0);
}

float4 ascii(float2 p) {
    float2 ci = floor(p / cell);
    float2 c0 = (ci + 0.5) * cell;
    // Four taps per cell; keep some of the brightest so windows smaller than a cell still register.
    float2 o = cell * 0.25;
    float3 s0 = scene(c0 + float2(-o.x, -o.y)).rgb;
    float3 s1 = scene(c0 + float2(o.x, -o.y)).rgb;
    float3 s2 = scene(c0 + float2(-o.x, o.y)).rgb;
    float3 s3 = scene(c0 + float2(o.x, o.y)).rgb;
    float3 col = mix((s0 + s1 + s2 + s3) * 0.25, max(max(s0, s1), max(s2, s3)), 0.45);

    // Edge direction: the gradient inside the cell (from the four taps) plus across the
    // neighbouring cells. Strong edges get line glyphs that follow them, so outlines read as
    // drawn strokes instead of noise.
    float l0 = lu(s0); float l1 = lu(s1); float l2 = lu(s2); float l3 = lu(s3);
    float gx = (l1 + l3) - (l0 + l2);
    float gy = (l2 + l3) - (l0 + l1);
    float nx = lu(scene(c0 + float2(cell.x, 0.0)).rgb) - lu(scene(c0 - float2(cell.x, 0.0)).rgb);
    float ny = lu(scene(c0 + float2(0.0, cell.y)).rgb) - lu(scene(c0 - float2(0.0, cell.y)).rgb);
    gx += nx * 0.6;
    gy += ny * 0.6;
    float lmax = max(max(l0, l1), max(l2, l3));
    // Only edges of things bigger than a cell: a lone window inside one cell has no gradient
    // across its neighbours and keeps its brightness glyph.
    float edge = min(length(float2(gx, gy)), length(float2(nx, ny)) * 2.5) / (0.1 + lmax);

    // Tap shockwave: a bright ring of glyphs expanding from the touch point.
    float boost = 0.0;
    if (shock.z >= 0.0) {
        float r = shock.z * resolution.y * 0.9;
        float k = (length(c0 - shock.xy) - r) / (resolution.y * 0.05);
        boost = exp(-k * k) * clamp(1.0 - shock.z / 1.3, 0.0, 1.0) * 0.9;
    }

    float luma = dot(col, float3(0.299, 0.587, 0.114));
    float peak = max(col.r, max(col.g, col.b));
    // Black point + gain + gamma: empty sky stays sparse, building faces become dense glyph fields.
    float lum = pow(clamp((mix(luma, peak, 0.4) - 0.035) * 1.85, 0.0, 1.0), 0.72);

    // Lighting: away from lights, darkness swallows dim cells, so the city is pockets of glyphs
    // in black, with only neon and lit windows surviving.
    // Below the street line each light also shines from its reflection, so the wet street
    // carries pools of neon under every lamp and sign.
    float light = ambient;
    float below = step(groundY, c0.y);
    float2 mp = float2(c0.x, mix(c0.y, groundY - (c0.y - groundY) * 1.35, below));
    for (int i = 0; i < 12; i++) {
        float3 L = lights[i];
        float r = max(L.z, 1.0);
        float d = length(c0 - L.xy) / r;
        float dm = length(mp - L.xy) / r;
        float li = max(max(0.0, 1.0 - d * d), below * 0.75 * max(0.0, 1.0 - dm * dm));
        light += step(float(i), lightCount - 0.5) * li;
    }
    light = clamp(light, 0.0, 1.0);
    lum = max(0.0, lum - (1.0 - light) * 0.42) + boost;
    lum += (hash21(ci + floor(time * 5.0)) - 0.5) * 0.05;   // slow glyph shimmer
    lum = clamp(lum, 0.0, 0.999);
    float g = floor(lum * glyphCount);

    bool water = opaque > 0.5 && c0.y > groundY + cell.y * 0.5;
    if (water) {
        // The wet street is water: sparse horizontal dashes whose gaps drift with the ripples.
        float wave = sin(ci.x * 0.55 + ci.y * 1.7 - time * 1.6) + sin(ci.x * 0.21 - ci.y * 0.9 + time * 0.9);
        float gate = hash21(float2(floor(ci.x / 3.0), ci.y) + floor(time * 0.7 + ci.y * 0.13));
        if (lum * 1.6 < gate * 0.8 + 0.08) g = 0.0;
        else if (wave > 1.1) g = glyphCount + 4.0;              // ~
        else if (lum < 0.3 && wave < -1.0) g = glyphCount + 5.0; // _
        else g = glyphCount + 1.0;                              // -
    } else if (edge > 0.95 && lum > 0.12 && lum < 0.9) {
        float ax = abs(gx); float ay = abs(gy);
        if (ay < ax * 0.42) g = glyphCount;                 // vertical edge: |
        else if (ax < ay * 0.42) g = glyphCount + 1.0;      // horizontal edge: -
        else if (gx * gy < 0.0) g = glyphCount + 2.0;       // /
        else g = glyphCount + 3.0;                          // backslash
    } else if (opaque > 0.5 && lum > 0.06) {
        // Materials get their own glyph sets, so you can tell what a thing is by its characters:
        // saturated neon is round and glowing, dim walls, haze and rain are quiet punctuation,
        // and warm light (windows, lamps) keeps the full letter ramp.
        float minc = min(col.r, min(col.g, col.b));
        float sat = (peak - minc) / max(peak, 0.001);
        if (sat > 0.5 && lum > 0.5) g = glyphCount + 10.0 + floor(clamp((lum - 0.5) * 2.0, 0.0, 0.999) * 4.0);
        else if (lum < 0.42) g = glyphCount + 6.0 + floor(clamp(lum / 0.42, 0.0, 0.999) * 4.0);
    }

    float2 f = p - ci * cell;
    float m = atlas.eval(float2(g * cell.x + f.x, f.y)).a;

    // Colour arrives with light; unlit cells fade to a cold blue-grey.
    float3 hue = col / max(peak, 0.03);
    float3 grey = float3(0.55, 0.64, 0.78) * dot(hue, float3(0.34));
    hue = mix(grey, hue, 0.3 + 0.7 * smoothstep(0.25, 0.85, light));
    float3 glyphCol = mix(hue, float3(1.0), 0.12) * (0.36 + 0.9 * lum);
    glyphCol = mix(glyphCol, float3(0.45, 1.0, 1.0) * (0.6 + lum), clamp(boost, 0.0, 1.0) * 0.7);
    if (opaque < 0.5) {
        float a = (scene(c0 - o).a + scene(c0 + o).a) * 0.5;
        return float4(glyphCol * m * a, m * a);
    }
    return float4(glyphCol * m + col * 0.025, 1.0);
}

half4 main(float2 p) {
    float2 q = p;
    if (glitch > 0.0) {
        float band = floor(p.y / (cell.y * 2.0));
        float slot = floor(time * 24.0);
        if (hash21(float2(band, slot)) < glitch * 0.45) {
            q.x += floor((hash21(float2(band + 7.1, slot)) - 0.5) * 18.0 * glitch) * cell.x;
        }
    }
    if (q.x >= splitX) return half4(scene(q));
    return half4(ascii(q));
}
"""

/** Per-frame inputs for [ASCII_SHADER]. Reused across frames to avoid allocation. */
class AsciiUniforms {
    var width = 1f
    var height = 1f
    var cellW = 8f
    var cellH = 14f
    var time = 0f
    /** Columns at or right of this x are shown raw. Huge = all ASCII, negative = all raw. */
    var splitX = 1e9f
    var groundY = 0f
    var glitch = 0f
    var shockX = 0f
    var shockY = 0f
    /** Seconds since the last tap, or -1 when there is no shockwave. */
    var shockAge = -1f
    /** Up to 12 lights as x, y, radius (screen pixels). */
    val lights = FloatArray(36)
    var lightCount = 0
    var ambient = 1f
    var opaque = true
}

interface AsciiEffect {
    fun renderEffect(u: AsciiUniforms): RenderEffect
}

/** Returns null where runtime shaders are unavailable. */
expect fun createAsciiEffect(atlas: GlyphAtlas): AsciiEffect?
