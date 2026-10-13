package com.zain.neonascii.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Held/pressed controls, written by the keyboard, the touch buttons or the autopilot. */
class Input {
    var left = false
    var right = false
    var down = false
    var jumpHeld = false
    /** Edge-triggered; the runner consumes it. */
    var jumpPressed = false

    fun pressJump() {
        if (!jumpHeld) jumpPressed = true
        jumpHeld = true
    }

    fun releaseJump() { jumpHeld = false }

    /** Edge-triggered saber swing, blaster shot and dash; the runner consumes them. */
    var attackPressed = false
    var shootPressed = false
    var dashPressed = false

    fun clear() {
        left = false; right = false; down = false; jumpHeld = false; jumpPressed = false
        attackPressed = false; shootPressed = false; dashPressed = false
    }
}

interface RunnerEvents {
    fun onStep(loudness: Float)
    fun onJump(double: Boolean)
    fun onLand(power: Float)
    fun onSwing(combo: Int)
    fun onShoot()
    fun onDash()
}

/** How long a saber swing lasts. */
const val SWING_TIME = 0.3f
const val SHOT_POSE_TIME = 0.35f
const val MAX_HP = 5

private const val MAX_SPEED = 340f
private const val ACCEL = 2600f
private const val AIR_ACCEL = 1700f
private const val FRICTION = 3000f
private const val JUMP_V = 1080f
private const val G_RISE_HELD = 1650f
private const val G_RISE = 3600f
private const val G_FALL = 3300f
private const val MAX_FALL = 1600f
private const val DASH_TIME = 0.17f
private const val DASH_SPEED = 980f

/** The player: a hooded ronin with a saber and a blaster. Units are u; y is the height of the feet. */
class Runner(startX: Float) {
    val width = 54f
    val height = 186f

    var x = startX
    var y = 0f
    var vx = 0f
    var vy = 0f
    var facing = 1
    var onGround = true; private set
    private var standingOn: Ledge? = null

    // Health.
    var hp = MAX_HP
    /** Seconds of invulnerability left after a hit (the sprite blinks). */
    var invuln = 0f
    /** Seconds of knockback stun left: no control. */
    var stun = 0f
    val dead get() = hp <= 0

    // Animation.
    var phase = 0f; private set
    var landSquash = 0f; private set
    var doubleJumpGlow = 0f; private set
    private var coyote = 0f
    private var buffer = 0f
    private var airJumps = 1
    private var dropTimer = 0f

    /** Seconds since the swing started, or -1 when not swinging. */
    var swingT = -1f; private set
    /** 1, 2 or 3: slash, upswing, spinning finisher. */
    var comboStep = 1; private set
    /** Bumped every swing so an enemy is hit at most once per swing. */
    var swingId = 0; private set
    private var comboWindow = 0f
    private var queuedAttack = false
    /** Seconds left with the blaster arm raised after a shot. */
    var aimT = 0f; private set
    private var shotCooldown = 0f
    /** The blade angle of the last few frames, for the swing trail. */
    val bladeTrail = FloatArray(5)

    /** Seconds left in a dash (also invulnerable), and where the afterimages were. */
    var dashT = 0f; private set
    private var dashCooldown = 0f
    private var airDashUsed = false
    val ghosts = FloatArray(10) // 5 x,y pairs
    var ghostAge = 1f; private set

    val speedNorm get() = min(1f, abs(vx) / MAX_SPEED)

    fun respawn(atX: Float) {
        x = atX; y = 0f; vx = 0f; vy = 0f; facing = 1; onGround = true; standingOn = null
        hp = MAX_HP; invuln = 1.5f; stun = 0f; swingT = -1f; dashT = 0f
    }

    /** Takes a hit from the side [fromX]; returns false while invulnerable. */
    fun hurt(fromX: Float): Boolean {
        if (invuln > 0f || dashT > 0f || dead) return false
        hp--
        invuln = 1.1f
        stun = 0.28f
        val away = if (x >= fromX) 1f else -1f
        vx = away * 460f
        vy = 520f
        onGround = false; standingOn = null
        swingT = -1f
        return true
    }

    /** A stomp or other upward kick. */
    fun bounce(power: Float = 820f) {
        vy = power; onGround = false; standingOn = null; airJumps = 1; airDashUsed = false
    }

    fun update(dt: Float, input: Input, level: Level, t: Float, events: RunnerEvents) {
        // Ride moving pads; walk off ledges.
        standingOn?.let { l ->
            if (x < l.x - 4f || x > l.right + 4f) { standingOn = null; onGround = false } else y = l.yAt(t)
        }
        invuln = max(0f, invuln - dt)
        stun = max(0f, stun - dt)
        val control = stun <= 0f && !dead

        val dir = if (control) (if (input.right) 1 else 0) - (if (input.left) 1 else 0) else 0
        if (dir != 0 && swingT < 0f) facing = dir
        if (dashT <= 0f) {
            val accel = when {
                !control -> 600f
                !onGround -> AIR_ACCEL
                dir == 0 -> FRICTION
                else -> ACCEL
            }
            vx = approach(vx, dir * MAX_SPEED, accel * dt)
        }

        // Dash: a short burst that ignores gravity and dodges damage.
        dashCooldown -= dt
        if (input.dashPressed) {
            input.dashPressed = false
            if (control && dashCooldown <= 0f && (onGround || !airDashUsed)) {
                dashT = DASH_TIME; dashCooldown = 0.45f
                if (!onGround) airDashUsed = true
                if (dir != 0) facing = dir
                vx = facing * DASH_SPEED; vy = 0f
                events.onDash()
            }
        }
        if (dashT > 0f) {
            dashT -= dt
            vx = facing * DASH_SPEED; vy = 0f
            if (dashT <= 0f) vx = facing * MAX_SPEED
        }
        ghostAge += dt
        if (dashT > 0f || ghostAge < 0.05f) {
            for (i in ghosts.size - 1 downTo 2) ghosts[i] = ghosts[i - 2]
            ghosts[0] = x; ghosts[1] = y
            if (dashT > 0f) ghostAge = 0f
        }

        if (input.jumpPressed) { buffer = 0.13f; input.jumpPressed = false }
        buffer -= dt
        coyote = if (onGround) 0.1f else coyote - dt
        if (buffer > 0f && control && dashT <= 0f) {
            if (coyote > 0f) {
                vy = JUMP_V; onGround = false; standingOn = null; coyote = 0f; buffer = 0f
                events.onJump(false)
            } else if (airJumps > 0) {
                vy = JUMP_V * 0.9f; airJumps--; buffer = 0f; doubleJumpGlow = 1f
                events.onJump(true)
            }
        }
        if (control && input.down && onGround && standingOn != null) {
            standingOn = null; onGround = false; dropTimer = 0.22f; y -= 2f
        }
        dropTimer -= dt
        doubleJumpGlow = max(0f, doubleJumpGlow - dt * 2.5f)

        // Saber combo: press again late in a swing (or just after) for the next step.
        if (input.attackPressed) {
            input.attackPressed = false
            if (control) {
                if (swingT < 0f) startSwing(if (comboWindow > 0f) comboStep % 3 + 1 else 1, events)
                else if (swingT > SWING_TIME * 0.45f) queuedAttack = true
            }
        }
        if (swingT >= 0f) {
            swingT += dt
            if (swingT > SWING_TIME) {
                swingT = -1f
                comboWindow = 0.28f
                if (queuedAttack) { queuedAttack = false; startSwing(comboStep % 3 + 1, events) }
            }
        } else {
            comboWindow = max(0f, comboWindow - dt)
            if (comboWindow <= 0f) comboStep = 1
        }
        shotCooldown -= dt
        aimT = max(0f, aimT - dt)
        if (input.shootPressed) {
            input.shootPressed = false
            if (control && shotCooldown <= 0f) { shotCooldown = 0.18f; aimT = SHOT_POSE_TIME; events.onShoot() }
        }
        landSquash = max(0f, landSquash - dt)

        if (dashT <= 0f) {
            val g = when {
                vy > 0f && input.jumpHeld && control -> G_RISE_HELD
                vy > 0f -> G_RISE
                else -> G_FALL
            }
            vy = max(vy - g * dt, -MAX_FALL)
        }

        // Horizontal move against solids.
        x += vx * dt
        val half = width / 2f
        for (s in level.solids) {
            if (y >= s.top - 0.5f || y + height <= s.bottom) continue
            if (x + half > s.x && x - half < s.right) {
                x = if (vx > 0f || x < s.x + s.w / 2f) s.x - half else s.right + half
                vx = 0f
            }
        }
        x = x.coerceIn(level.minX, level.maxX)

        // Vertical move: street, solid tops, one-way ledges; head bumps on raised solids.
        val prevY = y
        val fallSpeed = -vy
        y += vy * dt
        var landed = false
        if (vy <= 0f) {
            if (y <= 0f) { y = 0f; landed = true; standingOn = null }
            for (s in level.solids) {
                if (x + half <= s.x || x - half >= s.right) continue
                if (prevY >= s.top - 0.5f && y <= s.top) { y = s.top; landed = true; standingOn = null }
            }
            if (dropTimer <= 0f) {
                for (l in level.ledges) {
                    if (x < l.x || x > l.right) continue
                    val ly = l.yAt(t)
                    if (prevY >= ly - 6f && y <= ly) { y = ly; landed = true; standingOn = l }
                }
            }
        } else {
            for (s in level.solids) {
                if (s.bottom <= 0f || x + half <= s.x || x - half >= s.right) continue
                if (prevY + height <= s.bottom + 0.5f && y + height > s.bottom) { y = s.bottom - height; vy = 0f }
            }
        }
        if (landed) {
            if (!onGround) {
                landSquash = 0.14f
                events.onLand((fallSpeed / MAX_FALL).coerceIn(0.15f, 1f))
            }
            onGround = true; vy = 0f; airJumps = 1; airDashUsed = false
        } else if (standingOn == null) {
            onGround = false
        }

        for (i in bladeTrail.size - 1 downTo 1) bladeTrail[i] = bladeTrail[i - 1]
        bladeTrail[0] = saberAngle(this)

        // Run cycle and footsteps.
        if (onGround && speedNorm > 0.05f && dashT <= 0f) {
            val before = floor(phase / PI.toFloat())
            phase += dt * (7f + 7f * speedNorm)
            if (floor(phase / PI.toFloat()) != before) events.onStep(0.4f + 0.6f * speedNorm)
        }
    }

    private fun startSwing(step: Int, events: RunnerEvents) {
        comboStep = step
        swingT = 0f
        swingId++
        comboWindow = 0f
        if (step == 3 && onGround) vy = 420f // a little hop into the spinning finisher
        events.onSwing(step)
    }

    private fun approach(v: Float, target: Float, delta: Float) =
        if (v < target) min(v + delta, target) else max(v - delta, target)
}
