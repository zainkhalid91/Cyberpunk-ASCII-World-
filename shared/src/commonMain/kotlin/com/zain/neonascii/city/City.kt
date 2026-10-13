package com.zain.neonascii.city

import androidx.compose.ui.graphics.Color
import kotlin.random.Random

// All city geometry is in "u": 1u = 1/1000 of the screen height, so the skyline frames the same
// on a tall phone and a wide monitor.

val NeonPink = Color(0xFFFF2BD6)
val NeonCyan = Color(0xFF22F3FF)
val NeonViolet = Color(0xFF9D4DFF)
val NeonAmber = Color(0xFFFFB13B)
val NeonRed = Color(0xFFFF3355)
val NeonLime = Color(0xFFB6FF3B)

private val signColors = listOf(NeonPink, NeonCyan, NeonViolet, NeonAmber, NeonRed, NeonCyan, NeonPink)
private val signWords = listOf("ネオン", "酒場", "HOTEL", "BAR", "電脳", "OPEN", "ラーメン", "2088", "CYBER", "夜市", "KAFE", "寿司")
private val shopWords = listOf("RAMEN 24H", "ネオン BAR", "NOODLES", "電脳 SHOP", "PACHINKO", "SUSHI", "ARCADE", "CLINIC")

class Sign(
    val side: Int,          // -1 = hangs off the left wall, 1 = off the right wall
    val y: Float,           // top, in u above the ground
    val w: Float,
    val h: Float,
    val color: Color,
    val text: String,
    val seed: Int,
)

class Billboard(val dx: Float, val y: Float, val w: Float, val h: Float, val seed: Int)

class Storefront(val color: Color, val text: String, val seed: Int)

class Building(
    val x: Float,
    val w: Float,
    val h: Float,
    val seed: Int,
    val setback: Float,     // height of a narrower block on the roof (0 = none)
    val antenna: Float,     // antenna height (0 = none)
    val rim: Color,         // light catching one edge
    val signs: List<Sign>,
    val billboard: Billboard?,
    val storefront: Storefront?,
)

class Layer(
    val parallax: Float,
    val body: Color,
    val windowSize: Float,
    val litRatio: Float,
    val windowAlpha: Float,
    val haze: Float,        // how much horizon haze is laid over this layer
    val buildings: List<Building>,
)

class Lamp(val x: Float, val color: Color, val seed: Int)

/** A puff of a storm cloud, relative to the cloud centre, in u. */
class Puff(val dx: Float, val dy: Float, val rx: Float, val ry: Float)

/** [y] is a fraction of the street line's height; clouds drift on their own as well as with the camera. */
class Cloud(val x: Float, val y: Float, val parallax: Float, val drift: Float, val front: Boolean, val puffs: List<Puff>)

class City(seed: Int = 2088) {
    val worldWidth = 4200f
    val layers: List<Layer>
    val lamps: List<Lamp>
    val clouds: List<Cloud>

    init {
        val rnd = Random(seed)
        fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

        fun layer(
            parallax: Float, body: Color, wMin: Float, wMax: Float, hMin: Float, hMax: Float,
            gapMin: Float, gapMax: Float, windowSize: Float, lit: Float, windowAlpha: Float, haze: Float,
            signChance: Float, billboardChance: Float, storefronts: Boolean,
        ): Layer {
            val list = mutableListOf<Building>()
            var x = r(0f, 60f)
            while (x < worldWidth) {
                val w = r(wMin, wMax)
                val h = r(hMin, hMax)
                val bSeed = rnd.nextInt()
                val signs = buildList {
                    if (rnd.nextFloat() < signChance) {
                        val n = 1 + rnd.nextInt(2)
                        repeat(n) {
                            val word = signWords[rnd.nextInt(signWords.size)]
                            val sh = 26f + word.length * 22f
                            add(
                                Sign(
                                    side = if (rnd.nextBoolean()) -1 else 1,
                                    y = r(sh + 40f, (h - 30f).coerceAtLeast(sh + 60f)),
                                    w = 26f, h = sh,
                                    color = signColors[rnd.nextInt(signColors.size)],
                                    text = word, seed = rnd.nextInt(),
                                )
                            )
                        }
                    }
                }
                val billboard = if (w > 110f && h > 420f && rnd.nextFloat() < billboardChance) {
                    val bw = w * r(0.62f, 0.8f)
                    Billboard(dx = (w - bw) / 2f, y = h * r(0.55f, 0.8f), w = bw, h = bw * 0.56f, seed = rnd.nextInt())
                } else null
                val store = if (storefronts) {
                    Storefront(signColors[rnd.nextInt(signColors.size)], shopWords[rnd.nextInt(shopWords.size)], rnd.nextInt())
                } else null
                list += Building(
                    x = x, w = w, h = h, seed = bSeed,
                    setback = if (rnd.nextFloat() < 0.45f) r(30f, 110f) else 0f,
                    antenna = if (rnd.nextFloat() < 0.5f) r(30f, 120f) else 0f,
                    rim = if (rnd.nextBoolean()) NeonPink else NeonCyan,
                    signs = signs, billboard = billboard, storefront = store,
                )
                x += w + r(gapMin, gapMax)
            }
            return Layer(parallax, body, windowSize, lit, windowAlpha, haze, list)
        }

        layers = listOf(
            layer(0.08f, Color(0xFF262C44), 50f, 130f, 230f, 560f, 0f, 30f, 2.6f, 0.13f, 0.55f, 0.65f, 0f, 0f, false),
            layer(0.22f, Color(0xFF20263C), 60f, 150f, 280f, 720f, 5f, 40f, 3.6f, 0.2f, 0.7f, 0.45f, 0.15f, 0.1f, false),
            layer(0.5f, Color(0xFF1A1F32), 85f, 190f, 330f, 860f, 10f, 60f, 5f, 0.3f, 0.9f, 0.22f, 0.55f, 0.3f, false),
            layer(1f, Color(0xFF121522), 170f, 330f, 950f, 1350f, 260f, 620f, 8f, 0.22f, 1f, 0f, 0.35f, 0f, true),
        )
        clouds = buildList {
            fun cloud(front: Boolean) {
                val scale = if (front) r(1.1f, 1.6f) else r(0.7f, 1.15f)
                val width = r(260f, 460f) * scale
                val puffs = List(5 + rnd.nextInt(4)) {
                    Puff(
                        dx = r(-width / 2f, width / 2f),
                        dy = r(-30f, 12f) * scale,
                        rx = r(70f, 150f) * scale,
                        ry = r(26f, 58f) * scale,
                    )
                }
                add(
                    Cloud(
                        x = r(0f, 3000f),
                        y = if (front) r(0.16f, 0.36f) else r(0.04f, 0.26f),
                        parallax = if (front) 0.06f else 0.03f,
                        drift = if (front) r(10f, 16f) else r(5f, 9f),
                        front = front,
                        puffs = puffs,
                    )
                )
            }
            repeat(11) { cloud(front = false) }
            repeat(7) { cloud(front = true) }
        }
        lamps = buildList {
            var x = r(80f, 200f)
            while (x < worldWidth) {
                add(Lamp(x, if (rnd.nextFloat() < 0.6f) NeonAmber else NeonCyan, rnd.nextInt()))
                x += r(260f, 420f)
            }
        }
    }
}

/** Cheap deterministic hash to [0, 1). */
fun hash(a: Int, b: Int, c: Int = 0): Float {
    var h = a * 374761393 + b * 668265263 + c * 1274126177
    h = (h xor (h ushr 13)) * 1103515245
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777216f
}

/** Neon flicker: steady most of the time, with the occasional stuttering burst. */
fun flicker(seed: Int, t: Float): Float {
    val slot = (t * 14f).toInt()
    val burst = hash(seed, (t / 3.2f).toInt(), 7) < 0.16f
    if (burst && hash(seed, slot, 3) < 0.5f) return 0.12f
    return 0.9f + 0.1f * hash(seed, slot, 5)
}
