package com.zain.neonascii.game

import androidx.compose.ui.graphics.Color
import com.zain.neonascii.city.NeonAmber
import com.zain.neonascii.city.NeonCyan
import com.zain.neonascii.city.NeonPink
import com.zain.neonascii.city.NeonViolet
import kotlin.math.sin
import kotlin.random.Random

// Level space: x in u from the level start, y in u UP from the street line.

enum class SolidKind { Crate, Dumpster, Vending }

/** Blocks movement from every side. Sits on [bottom] (0 = the street). */
class Solid(val x: Float, val bottom: Float, val w: Float, val h: Float, val kind: SolidKind, val seed: Int) {
    val top get() = bottom + h
    val right get() = x + w
}

enum class LedgeKind { Awning, FireEscape, Catwalk, HoverPad, StallRoof }

/** One-way platform: land on it from above, jump through it from below, drop through with down. */
class Ledge(
    val x: Float,
    private val baseY: Float,
    val w: Float,
    val kind: LedgeKind,
    val seed: Int,
    val color: Color = NeonCyan,
    private val bob: Float = 0f, // hover pads drift up and down
) {
    val right get() = x + w
    fun yAt(t: Float) = if (bob == 0f) baseY else baseY + bob * sin(t * 1.1f + seed % 7)
}

enum class EnemyKind { Trooper, Blade, Hound, Drone }

/** Where an enemy starts: on the street (or a hover height for drones), patrolling [patrol] u each way. */
class EnemySpawn(val kind: EnemyKind, val x: Float, val y: Float, val patrol: Float)

/**
 * A fight scene: holo-barriers lock [x0]..[x1] when the runner walks in, and [waves] of enemies
 * come one after another until all are down.
 */
class Arena(val x0: Float, val x1: Float, val waves: List<List<EnemySpawn>>) {
    var cleared = false
}

class Shard(val x: Float, val y: Float) {
    var taken = false
    var takenAt = 0f
}

/** Decorative structures that frame the platforms (fire-escape frames, storefront walls, stalls). */
enum class DecorKind { FireEscapeFrame, Storefront, RamenStall, CatwalkTruss, Vent }

class Decor(val x: Float, val w: Float, val h: Float, val kind: DecorKind, val seed: Int, val color: Color, val text: String = "")

class Level(seed: Int = 77) {
    val solids = mutableListOf<Solid>()
    val ledges = mutableListOf<Ledge>()
    val shards = mutableListOf<Shard>()
    /** Enemies that patrol the street outside arenas. */
    val spawns = mutableListOf<EnemySpawn>()
    val arenas = mutableListOf<Arena>()
    /** Respawn points: the start and the entrance of every arena. */
    val checkpoints = mutableListOf(220f)
    /** Walkable range; an active arena narrows it to its barriers. */
    var minX = 60f
    var maxX = 0f
    val decor = mutableListOf<Decor>()
    val length: Float
    val exitX: Float
    val startX = 220f

    init {
        val rnd = Random(seed)
        fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        val signs = listOf("RAMEN", "ネオン", "BAR 24", "電脳", "NOODLE", "酒場")
        val colors = listOf(NeonPink, NeonCyan, NeonViolet, NeonAmber)
        fun color() = colors[rnd.nextInt(colors.size)]

        fun crates(x: Float): Float {
            when (rnd.nextInt(3)) {
                0 -> { solids += Solid(x, 0f, 64f, 64f, SolidKind.Crate, rnd.nextInt()); shards += Shard(x + 32f, 160f) }
                1 -> {
                    solids += Solid(x, 0f, 64f, 64f, SolidKind.Crate, rnd.nextInt())
                    solids += Solid(x + 64f, 0f, 64f, 64f, SolidKind.Crate, rnd.nextInt())
                    solids += Solid(x + 32f, 64f, 64f, 64f, SolidKind.Crate, rnd.nextInt())
                    shards += Shard(x + 64f, 230f)
                }
                else -> {
                    solids += Solid(x, 0f, 64f, 64f, SolidKind.Crate, rnd.nextInt())
                    solids += Solid(x + 64f, 0f, 64f, 128f, SolidKind.Crate, rnd.nextInt())
                    solids += Solid(x + 128f, 0f, 64f, 192f, SolidKind.Crate, rnd.nextInt())
                    shards += Shard(x + 160f, 300f)
                }
            }
            return 200f
        }

        fun dumpsterAwning(x: Float): Float {
            val c = color()
            decor += Decor(x - 40f, 300f, 260f, DecorKind.Storefront, rnd.nextInt(), c, signs[rnd.nextInt(signs.size)])
            solids += Solid(x + 20f, 0f, 150f, 74f, SolidKind.Dumpster, rnd.nextInt())
            ledges += Ledge(x - 30f, 200f, 260f, LedgeKind.Awning, rnd.nextInt(), c)
            shards += Shard(x + 100f, 300f)
            return 300f
        }

        fun fireEscape(x: Float): Float {
            val c = color()
            decor += Decor(x, 200f, 480f, DecorKind.FireEscapeFrame, rnd.nextInt(), c)
            ledges += Ledge(x, 150f, 200f, LedgeKind.FireEscape, rnd.nextInt(), c)
            ledges += Ledge(x + 60f, 290f, 200f, LedgeKind.FireEscape, rnd.nextInt(), c)
            ledges += Ledge(x, 430f, 200f, LedgeKind.FireEscape, rnd.nextInt(), c)
            shards += Shard(x + 100f, 520f)
            shards += Shard(x + 230f, 360f)
            return 300f
        }

        fun catwalk(x: Float): Float {
            solids += Solid(x, 0f, 74f, 150f, SolidKind.Vending, rnd.nextInt())
            val len = r(420f, 560f)
            decor += Decor(x + 110f, len, 300f, DecorKind.CatwalkTruss, rnd.nextInt(), color())
            ledges += Ledge(x + 110f, 300f, len, LedgeKind.Catwalk, rnd.nextInt(), NeonAmber)
            var sx = x + 180f
            while (sx < x + 110f + len - 40f) { shards += Shard(sx, 390f); sx += 130f }
            return 110f + len + 60f
        }

        fun hoverPads(x: Float): Float {
            val heights = floatArrayOf(150f, 280f, 190f)
            heights.forEachIndexed { i, hgt ->
                val px = x + i * 230f
                ledges += Ledge(px, hgt, 120f, LedgeKind.HoverPad, rnd.nextInt(), color(), bob = 55f)
                shards += Shard(px + 60f, hgt + 120f)
            }
            return 3 * 230f + 40f
        }

        fun ramenStall(x: Float): Float {
            decor += Decor(x, 280f, 170f, DecorKind.RamenStall, rnd.nextInt(), NeonAmber, signs[rnd.nextInt(signs.size)])
            ledges += Ledge(x - 10f, 170f, 300f, LedgeKind.StallRoof, rnd.nextInt(), NeonAmber)
            decor += Decor(x + 330f, 60f, 0f, DecorKind.Vent, rnd.nextInt(), Color.White)
            shards += Shard(x + 140f, 270f)
            return 420f
        }

        /** A flat street section with a little cover, locked by barriers; three waves that escalate. */
        fun arena(x: Float, index: Int): Float {
            val w = 1100f
            solids += Solid(x + 330f, 0f, 64f, 64f, SolidKind.Crate, rnd.nextInt())
            solids += Solid(x + w - 400f, 0f, 64f, 64f, SolidKind.Crate, rnd.nextInt())
            solids += Solid(x + w - 336f, 0f, 64f, 64f, SolidKind.Crate, rnd.nextInt())
            ledges += Ledge(x + 470f, 230f, 220f, LedgeKind.Catwalk, rnd.nextInt(), NeonAmber)
            decor += Decor(x + 470f, 220f, 230f, DecorKind.CatwalkTruss, rnd.nextInt(), color())
            fun at(f: Float) = x + w * f
            val waves = when (index) {
                0 -> listOf(
                    listOf(EnemySpawn(EnemyKind.Trooper, at(0.85f), 0f, 80f), EnemySpawn(EnemyKind.Hound, at(0.7f), 0f, 120f)),
                    listOf(EnemySpawn(EnemyKind.Blade, at(0.8f), 0f, 100f), EnemySpawn(EnemyKind.Drone, at(0.5f), 330f, 160f)),
                    listOf(EnemySpawn(EnemyKind.Trooper, at(0.15f), 0f, 60f), EnemySpawn(EnemyKind.Trooper, at(0.9f), 0f, 60f), EnemySpawn(EnemyKind.Hound, at(0.6f), 0f, 150f)),
                )
                1 -> listOf(
                    listOf(EnemySpawn(EnemyKind.Blade, at(0.85f), 0f, 100f), EnemySpawn(EnemyKind.Hound, at(0.2f), 0f, 120f), EnemySpawn(EnemyKind.Hound, at(0.75f), 0f, 120f)),
                    listOf(EnemySpawn(EnemyKind.Drone, at(0.3f), 340f, 140f), EnemySpawn(EnemyKind.Drone, at(0.75f), 360f, 140f), EnemySpawn(EnemyKind.Trooper, at(0.6f), 230f, 60f)),
                    listOf(EnemySpawn(EnemyKind.Blade, at(0.15f), 0f, 80f), EnemySpawn(EnemyKind.Blade, at(0.88f), 0f, 80f)),
                )
                else -> listOf(
                    listOf(EnemySpawn(EnemyKind.Trooper, at(0.88f), 0f, 60f), EnemySpawn(EnemyKind.Trooper, at(0.6f), 230f, 60f), EnemySpawn(EnemyKind.Drone, at(0.4f), 340f, 160f)),
                    listOf(EnemySpawn(EnemyKind.Blade, at(0.82f), 0f, 100f), EnemySpawn(EnemyKind.Blade, at(0.18f), 0f, 100f), EnemySpawn(EnemyKind.Hound, at(0.5f), 0f, 160f)),
                    listOf(EnemySpawn(EnemyKind.Trooper, at(0.1f), 0f, 50f), EnemySpawn(EnemyKind.Trooper, at(0.92f), 0f, 50f), EnemySpawn(EnemyKind.Blade, at(0.7f), 0f, 100f), EnemySpawn(EnemyKind.Drone, at(0.5f), 360f, 200f)),
                )
            }
            arenas += Arena(x, x + w, waves)
            checkpoints += x - 60f
            return w
        }

        val builders = listOf(::crates, ::dumpsterAwning, ::fireEscape, ::catwalk, ::hoverPads, ::ramenStall)
        var x = 700f
        decor += Decor(450f, 60f, 0f, DecorKind.Vent, rnd.nextInt(), Color.White)
        var last = -1
        var arenaIndex = 0
        val kinds = EnemyKind.entries
        for (i in 0 until 15) {
            if (i == 3 || i == 8 || i == 13) {
                x += arena(x, arenaIndex++)
                x += r(220f, 320f)
                continue
            }
            var pick = rnd.nextInt(builders.size)
            if (pick == last) pick = (pick + 1) % builders.size
            last = pick
            x += builders[pick](x)
            // A patrolling enemy in the gap after each structure (none in the very first stretch).
            if (i >= 1) {
                val kind = kinds[rnd.nextInt(kinds.size)]
                spawns += EnemySpawn(kind, x + r(90f, 170f), if (kind == EnemyKind.Drone) r(260f, 360f) else 0f, r(60f, 120f))
            }
            if (rnd.nextFloat() < 0.4f) decor += Decor(x + 60f, 60f, 0f, DecorKind.Vent, rnd.nextInt(), Color.White)
            x += r(220f, 360f)
        }
        exitX = x + 200f
        length = exitX + 700f
        maxX = length - 60f
    }

    fun reset() {
        shards.forEach { it.taken = false }
        arenas.forEach { it.cleared = false }
        minX = 60f; maxX = length - 60f
    }
}
