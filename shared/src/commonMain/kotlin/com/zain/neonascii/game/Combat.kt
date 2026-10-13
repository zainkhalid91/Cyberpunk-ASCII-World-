package com.zain.neonascii.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Everything here is in level space: x in u from the level start, y in u up from the street.

enum class EState { Patrol, Aim, Fire, Cooldown, Approach, Windup, Slash, Recover, Crouch, Leap, Stagger }

class Enemy(val kind: EnemyKind, var x: Float, var y: Float, val homeX: Float, val patrol: Float, val arena: Arena?, seed: Int) {
    var hp = when (kind) { EnemyKind.Trooper -> 3; EnemyKind.Blade -> 4; EnemyKind.Hound -> 1; EnemyKind.Drone -> 1 }
    var vx = 0f
    var vy = 0f
    var facing = -1
    var state = EState.Patrol
    var stateT = 0f
    var hitFlash = 0f
    var alive = true
    var onGround = true
    var lastSwingHit = -1
    val phase = (seed and 0xFFFF) / 65536f * 6.28f
    var shotsLeft = 0
    var shotTimer = 1.5f + phase * 0.3f
    var aimX = 0f
    var aimY = 0f
    var walkPhase = 0f
    /** Seconds since it teleported in (for the arrival effect). */
    var age = 0f
    val baseY = y

    val height: Float get() = when (kind) { EnemyKind.Trooper -> 172f; EnemyKind.Blade -> 166f; EnemyKind.Hound -> 72f; EnemyKind.Drone -> 30f }
    val halfW: Float get() = when (kind) { EnemyKind.Trooper -> 26f; EnemyKind.Blade -> 24f; EnemyKind.Hound -> 42f; EnemyKind.Drone -> 32f }
    val centerY: Float get() = if (kind == EnemyKind.Drone) y else y + height * 0.5f

    fun enter(s: EState) { state = s; stateT = 0f }
}

/** A bolt fired by an enemy (or one the saber sent back). */
class EnemyBullet(var x: Float, var y: Float, var vx: Float, var vy: Float, val orb: Boolean) {
    var reflected = false
    var life = 2.4f
}

/** The runner's blaster bolt. */
class Projectile(var x: Float, val y: Float, val dir: Int) { var life = 0.9f }

class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, val maxLife: Float, val hot: Boolean) { var life = maxLife }

/** A health cell dropped by an enemy (Mega Man style). */
class Pickup(var x: Float, var y: Float) {
    var vy = 520f
    var life = 12f
}

interface CombatEvents {
    fun enemyShot(x: Float, orb: Boolean)
    fun enemyHit(x: Float, heavy: Boolean)
    fun clang(x: Float)
    fun deflect(x: Float)
    fun playerHurt(x: Float)
    fun enemyDown(x: Float, y: Float)
    fun stomp(x: Float)
    fun heal()
    fun barrier(locked: Boolean)
    fun warpIn(x: Float, y: Float)
}

/**
 * Enemies, bullets, hits and fight scenes. Combat feel borrowed from the classics: Contra-style
 * troopers that telegraph and fire bursts, Prince of Persia-style duelists who guard and punish,
 * Mario stomps, Mega Man X dash and health cells, and hit-stop, shake and a slow-motion last kill.
 */
class CombatWorld(private val level: Level, private val runner: Runner, private val ev: CombatEvents) {
    private val rnd = Random(91)
    val enemies = mutableListOf<Enemy>()
    val bullets = mutableListOf<EnemyBullet>()
    val bolts = mutableListOf<Projectile>()
    val sparks = mutableListOf<Spark>()
    val pickups = mutableListOf<Pickup>()

    var activeArena: Arena? = null; private set
    var wave = 0; private set
    private var waveDelay = 0f
    var kills = 0; private set
    var combo = 0; private set
    private var comboTimer = 0f

    /** Freeze frames after a hit, screen shake (u), and slow motion after an arena's last kill. */
    var hitStop = 0f
    var shake = 0f
    var slowMo = 0f
    /** Brief flash after arenas lock/clear, for the HUD banner. */
    var bannerT = 0f; private set
    var bannerText = ""; private set

    init { reset() }

    fun reset() {
        enemies.clear(); bullets.clear(); bolts.clear(); sparks.clear(); pickups.clear()
        activeArena = null; wave = 0; kills = 0; combo = 0
        for (s in level.spawns) enemies += Enemy(s.kind, s.x, s.y, s.x, s.patrol, null, rnd.nextInt())
    }

    /** After a death: the current fight starts over, and anything near the checkpoint is cleared away. */
    fun restartArena() {
        val a = activeArena ?: return
        enemies.removeAll { it.arena == a }
        bullets.clear()
        activeArena = null
        level.minX = 60f; level.maxX = level.length - 60f
    }

    fun fireBolt() {
        val s = RUNNER_SCALE
        bolts += Projectile(runner.x + runner.facing * 60f * s, runner.y + 98f * s, runner.facing)
    }

    fun update(dt: Float, t: Float) {
        if (dt <= 0f) return
        updateArena(dt)
        comboTimer -= dt
        if (comboTimer <= 0f) combo = 0
        bannerT = max(0f, bannerT - dt)

        for (e in enemies) if (e.alive) updateEnemy(e, dt, t)
        updateBolts(dt)
        updateBullets(dt)
        saberHits()
        stompsAndContact()
        updatePickups(dt)
        for (p in sparks) { p.life -= dt; p.x += p.vx * dt; p.y += p.vy * dt; p.vy -= 900f * dt }
        sparks.removeAll { it.life <= 0f }
        enemies.removeAll { !it.alive && it.arena != null }
    }

    // ---------------------------------------------------------------- fight scenes

    private fun updateArena(dt: Float) {
        val a = activeArena
        if (a == null) {
            val enter = level.arenas.firstOrNull { !it.cleared && runner.x > it.x0 + 140f && runner.x < it.x1 - 140f } ?: return
            activeArena = enter
            wave = 0
            level.minX = enter.x0 + 30f; level.maxX = enter.x1 - 30f
            ev.barrier(true)
            banner("⚠ AMBUSH")
            spawnWave(enter, 0)
            return
        }
        if (enemies.any { it.alive && it.arena == a }) return
        if (wave < a.waves.size - 1) {
            waveDelay += dt
            if (waveDelay > 1.1f) { waveDelay = 0f; wave++; spawnWave(a, wave); banner("WAVE ${wave + 1}/${a.waves.size}") }
        } else {
            a.cleared = true
            activeArena = null
            level.minX = 60f; level.maxX = level.length - 60f
            ev.barrier(false)
            banner("SECTOR CLEAR")
        }
    }

    private fun banner(text: String) { bannerText = text; bannerT = 2f }

    private fun spawnWave(a: Arena, index: Int) {
        for (s in a.waves[index]) {
            val e = Enemy(s.kind, s.x, s.y, s.x, s.patrol, a, rnd.nextInt())
            e.facing = if (runner.x < e.x) -1 else 1
            enemies += e
            ev.warpIn(s.x, s.y + e.height * 0.5f)
            repeat(14) {
                val ang = rnd.nextFloat() * 6.28f
                sparks += Spark(s.x + cos(ang) * 20f, s.y + e.height * 0.5f + sin(ang) * 60f, cos(ang) * 160f, sin(ang) * 260f, 0.4f, false)
            }
        }
    }

    // ---------------------------------------------------------------- enemy behaviour

    private fun updateEnemy(e: Enemy, dt: Float, t: Float) {
        e.age += dt
        e.hitFlash = max(0f, e.hitFlash - dt)
        e.stateT += dt
        val dx = runner.x - e.x
        val adx = abs(dx)
        val dy = (runner.y + 90f) - e.centerY
        if (adx > 1700f && e.kind != EnemyKind.Drone) return // asleep until the runner is near
        val toward = if (dx >= 0f) 1 else -1
        val busy = runner.dead

        when (e.kind) {
            EnemyKind.Drone -> {
                e.x = e.homeX + sin(t * 0.6f + e.phase) * e.patrol
                e.y = e.baseY + sin(t * 2.1f + e.phase * 3f) * 14f
                e.facing = if (cos(t * 0.6f + e.phase) >= 0f) 1 else -1
                if (adx < 760f && !busy) {
                    e.shotTimer -= dt
                    if (e.shotTimer < 0.45f && e.state != EState.Aim) e.enter(EState.Aim) // eye brightens: telegraph
                    if (e.shotTimer <= 0f) {
                        e.shotTimer = 2.6f + rnd.nextFloat()
                        e.enter(EState.Patrol)
                        shootAt(e, e.x, e.y - 8f, 330f, orb = true)
                    }
                }
                return
            }
            EnemyKind.Trooper -> when (e.state) {
                EState.Patrol -> {
                    patrolWalk(e, 70f, dt)
                    if (!busy && adx < 720f && abs(dy) < 260f && e.age > 0.6f) { e.vx = 0f; e.facing = toward; e.enter(EState.Aim) }
                }
                EState.Aim -> {
                    e.facing = toward
                    // The laser sight drifts onto the runner: dodge or deflect.
                    e.aimX += (runner.x - e.aimX) * min(1f, dt * 5f)
                    e.aimY += (runner.y + 95f - e.aimY) * min(1f, dt * 5f)
                    if (e.stateT > 0.6f) { e.shotsLeft = 3; e.shotTimer = 0f; e.enter(EState.Fire) }
                }
                EState.Fire -> {
                    e.shotTimer -= dt
                    if (e.shotTimer <= 0f && e.shotsLeft > 0) {
                        e.shotsLeft--; e.shotTimer = 0.13f
                        shootAt(e, e.x + e.facing * 44f, e.y + 104f, 760f, orb = false, tx = e.aimX, ty = e.aimY)
                    }
                    if (e.shotsLeft == 0 && e.shotTimer <= 0f) e.enter(EState.Cooldown)
                }
                EState.Cooldown -> if (e.stateT > 1.1f) e.enter(EState.Patrol)
                EState.Stagger -> if (e.stateT > 0.22f) e.enter(EState.Cooldown)
                else -> e.enter(EState.Patrol)
            }
            EnemyKind.Blade -> when (e.state) {
                EState.Patrol -> {
                    patrolWalk(e, 90f, dt)
                    if (!busy && adx < 560f && abs(dy) < 220f) e.enter(EState.Approach)
                }
                EState.Approach -> {
                    e.facing = toward
                    e.vx = toward * 270f
                    if (adx < 125f) { e.vx = 0f; e.enter(EState.Windup) }
                    if (adx > 850f || busy) e.enter(EState.Patrol)
                }
                EState.Windup -> { e.vx = 0f; e.facing = toward; if (e.stateT > 0.42f) { e.vx = e.facing * 360f; e.enter(EState.Slash) } }
                EState.Slash -> {
                    e.vx *= 0.9f
                    // The slash connects in front of the duelist.
                    val rel = (runner.x - e.x) * e.facing
                    if (rel in -10f..150f && abs(dy) < 140f && runner.hurt(e.x)) onPlayerHurt(e.x)
                    if (e.stateT > 0.16f) { e.vx = 0f; e.enter(EState.Recover) }
                }
                EState.Recover -> if (e.stateT > 0.65f) e.enter(EState.Approach)
                EState.Stagger -> { e.vx *= 0.9f; if (e.stateT > 0.35f) e.enter(EState.Recover) }
                else -> e.enter(EState.Patrol)
            }
            EnemyKind.Hound -> when (e.state) {
                EState.Patrol -> {
                    patrolWalk(e, 130f, dt)
                    if (!busy && adx < 470f && abs(dy) < 220f && e.onGround && e.age > 0.5f) { e.vx = 0f; e.facing = toward; e.enter(EState.Crouch) }
                }
                EState.Crouch -> { e.vx = 0f; if (e.stateT > 0.32f) { e.vx = toward * (300f + min(adx, 460f) * 0.6f); e.vy = 780f; e.onGround = false; e.enter(EState.Leap) } }
                EState.Leap -> if (e.onGround && e.stateT > 0.1f) { e.vx = 0f; e.enter(EState.Cooldown) }
                EState.Cooldown -> if (e.stateT > 0.7f) e.enter(EState.Patrol)
                EState.Stagger -> if (e.stateT > 0.25f) e.enter(EState.Cooldown)
                else -> e.enter(EState.Patrol)
            }
        }
        groundPhysics(e, dt, t)
    }

    private fun patrolWalk(e: Enemy, speed: Float, dt: Float) {
        if (e.x > e.homeX + e.patrol) e.facing = -1
        if (e.x < e.homeX - e.patrol) e.facing = 1
        e.vx = e.facing * speed
        e.walkPhase += dt * speed * 0.06f
    }

    private fun groundPhysics(e: Enemy, dt: Float, t: Float) {
        if (abs(e.vx) > 5f) e.walkPhase += dt * abs(e.vx) * 0.05f
        val prevY = e.y
        e.vy -= 3000f * dt
        // Sideways: blocked by solids (turn around while patrolling).
        val nx = e.x + e.vx * dt
        val blocked = level.solids.any { s -> e.y < s.top - 4f && e.y + e.height > s.bottom && nx + e.halfW > s.x && nx - e.halfW < s.right }
        if (blocked) { if (e.state == EState.Patrol) e.facing = -e.facing; e.vx = 0f } else e.x = nx
        e.arena?.let { e.x = e.x.coerceIn(it.x0 + 40f, it.x1 - 40f) }
        e.y += e.vy * dt
        var support = 0f
        for (s in level.solids) if (e.x > s.x && e.x < s.right && prevY >= s.top - 1f) support = max(support, s.top)
        for (l in level.ledges) {
            val ly = l.yAt(t)
            if (e.x > l.x && e.x < l.right && prevY >= ly - 6f) support = max(support, ly)
        }
        if (e.y <= support) { e.y = support; e.vy = 0f; e.onGround = true } else e.onGround = false
        // Patrollers on a ledge turn at its edge instead of walking off.
        if (e.onGround && support > 0f && e.state == EState.Patrol) {
            val onLedge = level.ledges.firstOrNull { e.x > it.x && e.x < it.right && abs(it.yAt(t) - support) < 2f }
            if (onLedge != null && (e.x + e.facing * 30f < onLedge.x || e.x + e.facing * 30f > onLedge.right)) e.facing = -e.facing
        }
    }

    private fun shootAt(e: Enemy, fx: Float, fy: Float, speed: Float, orb: Boolean, tx: Float = runner.x, ty: Float = runner.y + 95f) {
        val dx = tx - fx
        val dy = ty - fy
        val len = max(1f, sqrt(dx * dx + dy * dy))
        bullets += EnemyBullet(fx, fy, dx / len * speed, dy / len * speed, orb)
        ev.enemyShot(fx, orb)
    }

    // ---------------------------------------------------------------- the runner's attacks

    private fun updateBolts(dt: Float) {
        for (b in bolts) {
            if (b.life <= 0f) continue
            b.x += b.dir * 1500f * dt
            b.life -= dt
            for (e in enemies) {
                if (!e.alive || abs(e.x - b.x) > e.halfW + 10f || b.y < e.centerY - e.height * 0.55f - 10f || b.y > e.centerY + e.height * 0.55f + 10f) continue
                b.life = 0f
                if (guarding(e, b.x)) { ev.clang(e.x); sparksAt(b.x, b.y, 8, true) } else damage(e, 1, b.x, heavy = false)
                break
            }
            if (level.solids.any { b.x in it.x..it.right && b.y in it.bottom..it.top }) b.life = 0f
        }
        bolts.removeAll { it.life <= 0f }
    }

    /** A duelist blocks attacks from the front while it is not attacking or recovering. */
    private fun guarding(e: Enemy, fromX: Float): Boolean =
        e.kind == EnemyKind.Blade && e.state in setOf(EState.Patrol, EState.Approach, EState.Windup) &&
            (fromX - e.x) * e.facing > 0f

    private fun saberHits() {
        val sw = runner.swingT
        if (sw < 0.03f || sw > SWING_TIME * 0.85f) return
        val pts = saberPoints(runner)
        val cx = runner.x + runner.facing * 50f
        val cy = runner.y + 100f
        for (e in enemies) {
            if (!e.alive || e.lastSwingHit == runner.swingId) continue
            val reach = 60f + e.halfW
            val near = (0..1).any { i ->
                val px = pts[i * 2]; val py = pts[i * 2 + 1]
                (px - e.x) * (px - e.x) + (py - e.centerY) * (py - e.centerY) < reach * reach
            } || (abs(cx - e.x) < 70f + e.halfW && abs(cy - e.centerY) < 90f + e.height * 0.4f)
            if (!near) continue
            e.lastSwingHit = runner.swingId
            val finisher = runner.comboStep == 3
            if (guarding(e, runner.x) && !finisher) {
                // CLANG: blocked. Push both apart; the finisher breaks guard.
                ev.clang(e.x)
                sparksAt((runner.x + e.x) / 2f, e.centerY + 20f, 12, true)
                e.vx = runner.facing * 220f
                runner.vx = -runner.facing * 240f
                hitStop = max(hitStop, 0.05f); shake = max(shake, 5f)
            } else {
                damage(e, if (finisher) 2 else 1, runner.x, heavy = finisher)
            }
        }
        // Deflect: a swing sends nearby enemy bolts back where they came from.
        for (b in bullets) {
            if (b.reflected) continue
            val close = (0..1).any { i ->
                val px = pts[i * 2]; val py = pts[i * 2 + 1]
                (px - b.x) * (px - b.x) + (py - b.y) * (py - b.y) < 85f * 85f
            } || (abs(cx - b.x) < 80f && abs(cy - b.y) < 110f)
            if (!close) continue
            b.reflected = true
            b.vx = runner.facing * max(abs(b.vx), 500f) * 1.5f
            b.vy = -b.vy * 0.3f
            b.life = 1.6f
            ev.deflect(b.x)
            sparksAt(b.x, b.y, 6, false)
        }
    }

    private fun updateBullets(dt: Float) {
        for (b in bullets) {
            b.life -= dt
            b.x += b.vx * dt; b.y += b.vy * dt
            if (b.y < 0f || level.solids.any { b.x in it.x..it.right && b.y in it.bottom..it.top }) { b.life = 0f; sparksAt(b.x, max(b.y, 2f), 4, true); continue }
            if (b.reflected) {
                for (e in enemies) {
                    if (!e.alive) continue
                    if (abs(e.x - b.x) < e.halfW + 12f && abs(e.centerY - b.y) < e.height * 0.55f + 12f) { damage(e, 2, b.x, heavy = true); b.life = 0f; break }
                }
            } else if (abs(runner.x - b.x) < 28f && b.y > runner.y + 10f && b.y < runner.y + runner.height * 1.1f) {
                b.life = 0f
                if (runner.hurt(b.x)) onPlayerHurt(b.x)
            }
        }
        bullets.removeAll { it.life <= 0f }
    }

    private fun stompsAndContact() {
        if (runner.dead) return
        for (e in enemies) {
            if (!e.alive || e.age < 0.4f) continue
            val dx = runner.x - e.x
            val overlapX = abs(dx) < e.halfW + 22f
            val top = if (e.kind == EnemyKind.Drone) e.y + 14f else e.y + e.height
            // Mario stomp: falling onto an enemy's head.
            if (overlapX && runner.vy < -60f && runner.y in (top - 30f)..(top + 26f)) {
                runner.bounce()
                ev.stomp(e.x)
                damage(e, if (e.kind == EnemyKind.Hound || e.kind == EnemyKind.Drone) 3 else 1, runner.x, heavy = true)
                continue
            }
            // Touching a hound or a duelist hurts.
            val touching = abs(dx) < e.halfW + 16f && runner.y < top - 10f && runner.y + runner.height > e.y + 10f
            if (touching && (e.kind == EnemyKind.Hound || e.kind == EnemyKind.Blade) && runner.hurt(e.x)) onPlayerHurt(e.x)
        }
    }

    private fun damage(e: Enemy, amount: Int, fromX: Float, heavy: Boolean) {
        e.hp -= amount
        e.hitFlash = 0.14f
        val away = if (e.x >= fromX) 1f else -1f
        combo++; comboTimer = 1.7f
        hitStop = max(hitStop, if (heavy) 0.09f else 0.055f)
        shake = max(shake, if (heavy) 13f else 7f)
        sparksAt(e.x - away * e.halfW * 0.5f, e.centerY, if (heavy) 14 else 8, true)
        if (e.hp <= 0) {
            kill(e)
        } else {
            ev.enemyHit(e.x, heavy)
            if (e.kind != EnemyKind.Drone) {
                e.vx = away * (if (heavy) 420f else 220f)
                if (heavy) { e.vy = 380f; e.onGround = false }
                e.enter(EState.Stagger)
            }
        }
    }

    private fun kill(e: Enemy) {
        e.alive = false
        kills++
        ev.enemyDown(e.x, e.centerY)
        repeat(26) {
            val a = rnd.nextFloat() * 6.283f
            val sp = 200f + rnd.nextFloat() * 520f
            sparks += Spark(e.x, e.centerY, cos(a) * sp, sin(a) * sp + 150f, 0.4f + rnd.nextFloat() * 0.5f, rnd.nextBoolean())
        }
        if (runner.hp < MAX_HP && rnd.nextFloat() < 0.3f) pickups += Pickup(e.x, e.centerY)
        // The last enemy of an arena's last wave: slow motion.
        val a = e.arena
        if (a != null && wave == a.waves.size - 1 && enemies.none { it.alive && it.arena == a }) { slowMo = 0.9f; shake = max(shake, 16f) }
    }

    private fun sparksAt(x: Float, y: Float, n: Int, hot: Boolean) {
        repeat(n) {
            val a = rnd.nextFloat() * 6.283f
            val sp = 150f + rnd.nextFloat() * 380f
            sparks += Spark(x, y, cos(a) * sp, sin(a) * sp, 0.2f + rnd.nextFloat() * 0.25f, hot)
        }
    }

    private fun onPlayerHurt(fromX: Float) {
        ev.playerHurt(fromX)
        hitStop = max(hitStop, 0.1f)
        shake = max(shake, 16f)
        combo = 0
    }

    private fun updatePickups(dt: Float) {
        for (p in pickups) {
            p.life -= dt
            p.vy -= 1600f * dt
            p.y += p.vy * dt
            var floor = 0f
            for (s in level.solids) if (p.x > s.x && p.x < s.right && p.y >= s.top - 30f) floor = max(floor, s.top)
            if (p.y < floor + 20f) { p.y = floor + 20f; p.vy = 0f }
            if (abs(runner.x - p.x) < 50f && p.y > runner.y - 20f && p.y < runner.y + runner.height) {
                p.life = 0f
                runner.hp = min(MAX_HP, runner.hp + 1)
                ev.heal()
            }
        }
        pickups.removeAll { it.life <= 0f }
    }

    /** Nearest living enemy ahead of the runner within [range], for the autopilot. */
    fun nearestAhead(range: Float): Enemy? = enemies.filter { it.alive && (it.x - runner.x) * runner.facing in -40f..range }
        .minByOrNull { abs(it.x - runner.x) }

}
