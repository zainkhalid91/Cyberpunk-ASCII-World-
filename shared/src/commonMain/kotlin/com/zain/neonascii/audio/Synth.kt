package com.zain.neonascii.audio

import com.zain.neonascii.res.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan

const val SAMPLE_RATE = 48000
private const val SR = SAMPLE_RATE.toFloat()

/** Sample rate of the bundled recordings. */
private const val CLIP_RATE = 32000f

/** Starts a platform audio thread that pulls interleaved stereo blocks from [synth] forever. */
expect fun startAudio(synth: Synth)

object NeonAudio {
    val synth = Synth()
    private var started = false

    /** Set by the Android activity while it is in the background. */
    var paused: Boolean
        get() = synth.paused
        set(value) { synth.paused = value }

    fun ensureStarted() {
        if (started) return
        started = true
        startAudio(synth)
    }

    private var loaded = false

    /** Reads the bundled CC0 recordings (raw 32 kHz s16le PCM) and hands them to the mixer. */
    suspend fun loadRecordings() {
        if (loaded) return
        loaded = true
        withContext(Dispatchers.Default) {
            synth.loadRecordings(
                rainBed = pcm(Res.readBytes("files/rain_bed.pcm")),
                thunder = pcm(Res.readBytes("files/thunder.pcm")),
            )
        }
    }

    private fun pcm(bytes: ByteArray) = ShortArray(bytes.size / 2) { i ->
        ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
    }
}

/**
 * The mixer, kept deliberately calm: a slow detuned-saw pad with a stereo echo, a soft rain bed
 * (a CC0 recording slowed to 0.8x and low-passed), gentle drips that fire when a nearby drop
 * actually splashes on screen, rare recorded thunder, quiet footsteps and the data-pickup chime.
 *
 * The UI thread only writes the @Volatile fields and event counters; the audio thread owns all
 * other state.
 */
class Synth {
    @Volatile var paused = false
    @Volatile var enabled = true
    @Volatile var rainAmount = 1f
    /** 0..1 after a lightning strike: the rain swells a little and opens up. */
    @Volatile var downpour = 0f

    @Volatile private var bed: SampleLoop? = null
    @Volatile private var thunderClip: ShortArray? = null

    fun loadRecordings(rainBed: ShortArray, thunder: ShortArray) {
        // Slowed: deeper, calmer drops that match the slow rain on screen.
        bed = SampleLoop(rainBed, channels = 2, srcRate = CLIP_RATE, speed = 0.8f)
        thunderClip = thunder
    }

    // Events: the UI bumps a counter, the audio thread notices the change.
    @Volatile private var thunderSerial = 0
    @Volatile private var thunderPower = 1f
    @Volatile private var thunderDelay = 0f
    @Volatile private var thunderPan = 0f
    @Volatile private var boomSerial = 0
    @Volatile private var stepSerial = 0
    @Volatile private var stepLoud = 0f
    @Volatile private var landSerial = 0
    @Volatile private var landPower = 0f
    @Volatile private var pickupSerial = 0
    @Volatile private var dripSerial = 0
    @Volatile private var dripPan = 0f
    @Volatile private var dripSize = 0f

    fun thunder(power: Float, delaySeconds: Float, pan: Float) {
        thunderPower = power; thunderDelay = delaySeconds; thunderPan = pan
        thunderSerial++
    }

    fun boom() { boomSerial++ }

    /** A quiet footstep; [loudness] 0..1. */
    fun step(loudness: Float) { stepLoud = loudness; stepSerial++ }
    fun land(power: Float) { landPower = power; landSerial++ }
    fun pickup() { pickupSerial++ }

    @Volatile private var pewSerial = 0
    @Volatile private var swingSerial = 0
    @Volatile private var explodeSerial = 0
    @Volatile private var explodePan = 0f

    /** Blaster shot: a short falling chirp. */
    fun pew() { pewSerial++ }
    @Volatile private var swingPitch = 1f

    /** Saber swing: a hum that bends up and back down; each combo step a little higher. */
    fun swing(combo: Int = 1) { swingPitch = 1f + (combo - 1) * 0.18f; swingSerial++ }

    // ---- combat blips: a small voice pool fed by a single-writer ring of events.
    private val qF0 = FloatArray(32); private val qF1 = FloatArray(32); private val qDecay = FloatArray(32)
    private val qDur = FloatArray(32); private val qAmp = FloatArray(32); private val qWave = IntArray(32); private val qPan = FloatArray(32)
    @Volatile private var qWrite = 0
    private var qRead = 0

    /** A pitched blip gliding [f0] -> [f1] Hz; wave 0 = sine, 1 = soft square, 2 = two inharmonic sines (metal). */
    private fun blip(f0: Float, f1: Float, decay: Float, dur: Float, amp: Float, wave: Int, pan: Float) {
        val i = qWrite % 32
        qF0[i] = f0; qF1[i] = f1; qDecay[i] = decay; qDur[i] = dur; qAmp[i] = amp; qWave[i] = wave; qPan[i] = pan
        qWrite++
    }

    fun enemyShot(pan: Float, orb: Boolean) = if (orb) blip(520f, 380f, 9f, 0.3f, 0.07f, 0, pan) else blip(1100f, 260f, 18f, 0.2f, 0.07f, 1, pan)
    fun hit(pan: Float, heavy: Boolean) { blip(if (heavy) 140f else 190f, 60f, 14f, 0.25f, if (heavy) 0.3f else 0.2f, 0, pan); blip(900f, 500f, 40f, 0.08f, 0.05f, 1, pan) }
    fun clang(pan: Float) = blip(1250f, 1180f, 9f, 0.4f, 0.09f, 2, pan)
    fun deflect(pan: Float) = blip(2300f, 1500f, 16f, 0.18f, 0.07f, 0, pan)
    fun hurt() { blip(120f, 45f, 6f, 0.5f, 0.35f, 0, 0f); blip(420f, 180f, 10f, 0.25f, 0.08f, 1, 0f) }
    fun stomp(pan: Float) = blip(180f, 520f, 12f, 0.16f, 0.14f, 0, pan)
    fun dash() = blip(300f, 900f, 14f, 0.15f, 0.05f, 0, 0f)
    /** A tin can hitting the street. */
    fun clink(pan: Float, strength: Float) = blip(2400f + strength * 500f, 2250f, 30f, 0.14f, 0.025f + 0.035f * strength, 2, pan)
    fun barrier(locked: Boolean) = if (locked) blip(330f, 660f, 3f, 0.6f, 0.08f, 1, 0f) else blip(660f, 990f, 4f, 0.6f, 0.08f, 0, 0f)
    /** A drone going down: a soft thump and a metallic ping. */
    fun explode(pan: Float) { explodePan = pan; explodeSerial++ }

    /** 0..1 how much of the sky-train is in view, and where; drives a soft low rumble. */
    @Volatile var trainAmount = 0f
    @Volatile var trainPan = 0f

    /** A drop splashing near the listener; [pan] -1..1, [size] 0..1. */
    fun drip(pan: Float, size: Float) { dripPan = pan; dripSize = size; dripSerial++ }

    // ---------------------------------------------------------------- audio-thread state

    private var seed = 0x2545F491
    private fun noise(): Float {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        return (seed and 0xFFFFFF) / 8388608f - 1f
    }
    private fun rand01() = (noise() + 1f) * 0.5f

    private var master = 0f

    // Rain bed.
    private val rainFilter = Array(2) { Svf() }
    private var bedGain = 0f
    private var rainCutoff = 2000f

    // Drips: four voices of a soft falling "plink".
    private val dripT = FloatArray(4) { -1f }
    private val dripF = FloatArray(4)
    private val dripPhase = FloatArray(4)
    private val dripAmp = FloatArray(4)
    private val dripP = FloatArray(4)
    private var nextDrip = 0
    private var seenDrip = 0

    // Pad: five voices, two detuned saws each, gliding between chords.
    private val chords = arrayOf(
        floatArrayOf(73.42f, 110.0f, 174.61f, 261.63f, 329.63f), // Dm9
        floatArrayOf(58.27f, 116.54f, 174.61f, 220.0f, 293.66f), // Bbmaj7
        floatArrayOf(87.31f, 130.81f, 220.0f, 261.63f, 392.0f),  // Fmaj7
        floatArrayOf(65.41f, 130.81f, 196.0f, 246.94f, 329.63f), // Cmaj7
    )
    private val voiceFreq = chords[0].copyOf()
    private val phaseA = FloatArray(5) { it * 0.21f }
    private val phaseB = FloatArray(5) { it * 0.37f }
    private val padFilter = Array(2) { Svf() }
    private var chordIndex = 0
    private var chordTimer = 0
    private var lfo = 0f
    private var padFade = 0f

    // Stereo echo shared by the pad, chime and drips.
    private val delayL = FloatArray((SR * 0.43f).toInt())
    private val delayR = FloatArray((SR * 0.57f).toInt())
    private var delayPosL = 0; private var delayPosR = 0

    // Recorded thunder: two voices so a new strike doesn't cut off the previous roll.
    private var seenThunder = 0
    private var thunderWait = -1
    private var thunderPow = 0f
    private var thunderP = 0f
    private val clipPos = FloatArray(2) { -1f }
    private val clipGain = FloatArray(2)
    private val clipPan = FloatArray(2)
    private val clipFilter = Array(2) { Svf() }
    private val clipCutoff = FloatArray(2)
    private var nextClipVoice = 0

    // Runner and tap.
    private var seenStep = 0; private var stepT = -1f; private var stepAmp = 0f
    private val stepFilter = Svf(); private var stepPhase = 0f
    private var seenLand = 0; private var landT = -1f; private var landAmp = 0f
    private val landFilter = Svf(); private var landPhase = 0f
    private var seenPickup = 0; private var pickupT = -1f; private var pickupPhase = 0f
    private var seenBoom = 0; private var boomT = -1f; private var boomPhase = 0f
    private var seenPew = 0; private var pewT = -1f; private var pewPhase = 0f
    private var seenSwing = 0; private var swingT = -1f; private var swingPhaseA = 0f; private var swingPhaseB = 0f
    private val swingFilter = Svf()
    private val bT = FloatArray(8) { -1f }; private val bPh = FloatArray(8); private val bPh2 = FloatArray(8)
    private val bF0 = FloatArray(8); private val bF1 = FloatArray(8); private val bDecay = FloatArray(8)
    private val bDur = FloatArray(8); private val bAmp = FloatArray(8); private val bWave = IntArray(8); private val bPan = FloatArray(8)
    private var bNext = 0
    // Sky-train: a deep hum plus the soft double clack of wheels over rail joints.
    private var trainGain = 0f; private var trainPh1 = 0f; private var trainPh2 = 0f
    private var clackClock = 0f; private var clackT = -1f; private var clackPh = 0f
    private val trainFilter = Svf()
    private var seenExplode = 0; private var explodeT = -1f; private var explodePhase = 0f; private var pingPhase = 0f; private var explodeP = 0f

    /** Fills [out] with [frames] interleaved stereo samples. */
    fun render(out: FloatArray, frames: Int) {
        if (thunderSerial != seenThunder) {
            seenThunder = thunderSerial
            thunderWait = (thunderDelay * SR).toInt().coerceAtLeast(1)
            thunderPow = thunderPower; thunderP = thunderPan
        }
        if (boomSerial != seenBoom) { seenBoom = boomSerial; boomT = 0f; boomPhase = 0f }
        if (pewSerial != seenPew) { seenPew = pewSerial; pewT = 0f; pewPhase = 0f }
        if (swingSerial != seenSwing) { seenSwing = swingSerial; swingT = 0f }
        val w = qWrite
        while (qRead < w) {
            val i = qRead % 32; qRead++
            val v = bNext; bNext = (bNext + 1) % bT.size
            bT[v] = 0f; bPh[v] = 0f; bPh2[v] = 0f
            bF0[v] = qF0[i]; bF1[v] = qF1[i]; bDecay[v] = qDecay[i]; bDur[v] = qDur[i]; bAmp[v] = qAmp[i]; bWave[v] = qWave[i]; bPan[v] = qPan[i]
        }
        if (explodeSerial != seenExplode) { seenExplode = explodeSerial; explodeT = 0f; explodePhase = 0f; pingPhase = 0f; explodeP = explodePan }
        if (stepSerial != seenStep) { seenStep = stepSerial; stepT = 0f; stepPhase = 0f; stepAmp = stepLoud; stepFilter.set(500f + rand01() * 500f, 0.9f) }
        if (landSerial != seenLand) { seenLand = landSerial; landT = 0f; landPhase = 0f; landAmp = landPower; landFilter.set(400f + rand01() * 250f, 0.8f) }
        if (pickupSerial != seenPickup) { seenPickup = pickupSerial; pickupT = 0f; pickupPhase = 0f }
        if (dripSerial != seenDrip) {
            seenDrip = dripSerial
            val v = nextDrip; nextDrip = (nextDrip + 1) % dripT.size
            dripT[v] = 0f; dripPhase[v] = 0f
            dripF[v] = 900f + rand01() * 900f - dripSize * 400f
            dripAmp[v] = 0.025f + 0.035f * dripSize
            dripP[v] = dripPan
        }
        val masterTarget = if (enabled && !paused) 0.9f else 0f
        val rain = rainAmount
        val swell = downpour

        for (i in 0 until frames) {
            master += (masterTarget - master) * 0.0002f
            var l = 0f
            var r = 0f

            // ---- soft rain bed (silent until the recording has loaded)
            val bedLoop = bed
            if (bedLoop != null) {
                bedGain += (0.38f + 0.18f * rain + 0.25f * swell - bedGain) * 0.00004f
                rainCutoff += (1700f + 1100f * swell - rainCutoff) * 0.0001f
                rainFilter[0].set(rainCutoff, 0.6f); rainFilter[1].set(rainCutoff * 1.05f, 0.6f)
                bedLoop.next()
                l += rainFilter[0].lowpass(bedLoop.left) * bedGain
                r += rainFilter[1].lowpass(bedLoop.right) * bedGain
            }

            // ---- pad
            if (chordTimer-- <= 0) {
                chordTimer = (SR * 9f).toInt()
                chordIndex = (chordIndex + 1) % chords.size
            }
            val target = chords[chordIndex]
            lfo += 0.07f / SR
            if (lfo > 1f) lfo -= 1f
            padFade = minOf(1f, padFade + 1f / (SR * 5f))
            var padL = 0f; var padR = 0f
            for (v in 0 until 5) {
                voiceFreq[v] += (target[v] - voiceFreq[v]) * 0.00004f // slow glide between chords
                phaseA[v] += voiceFreq[v] * 0.9965f / SR; if (phaseA[v] >= 1f) phaseA[v] -= 1f
                phaseB[v] += voiceFreq[v] * 1.0035f / SR; if (phaseB[v] >= 1f) phaseB[v] -= 1f
                val a = phaseA[v] * 2f - 1f
                val b = phaseB[v] * 2f - 1f
                padL += a * 0.7f + b * 0.3f
                padR += a * 0.3f + b * 0.7f
            }
            val cutoff = 520f + 380f * sin(lfo * 2f * PI.toFloat())
            padFilter[0].set(cutoff, 1.1f); padFilter[1].set(cutoff * 1.04f, 1.1f)
            padL = padFilter[0].lowpass(padL) * 0.05f * padFade
            padR = padFilter[1].lowpass(padR) * 0.05f * padFade

            // ---- drips: a sine "plink" that falls in pitch, like a drop into a puddle
            var dripL = 0f; var dripR = 0f
            for (v in dripT.indices) {
                val t = dripT[v]
                if (t < 0f) continue
                val f = dripF[v] * (1f + 0.6f * exp(-t * 60f))
                dripPhase[v] += f / SR
                val x = sin(dripPhase[v] * 2f * PI.toFloat()) * exp(-t * 38f) * dripAmp[v]
                pan(dripP[v])
                dripL += x * panL; dripR += x * panR
                dripT[v] = t + 1f / SR
                if (dripT[v] > 0.15f) dripT[v] = -1f
            }

            // ---- pickup chime
            var chime = 0f
            if (pickupT >= 0f) {
                val freq = if (pickupT < 0.07f) 1318.5f else 1975.5f
                pickupPhase += freq / SR
                chime = sin(pickupPhase * 2f * PI.toFloat()) * exp(-pickupT * 7f) * 0.09f
                pickupT += 1f / SR
                if (pickupT > 0.7f) pickupT = -1f
            }

            // ---- echo
            val wetInL = padL + dripL * 0.6f + chime * 0.5f
            val wetInR = padR + dripR * 0.6f + chime * 0.5f
            val echoL = delayL[delayPosL]; val echoR = delayR[delayPosR]
            delayL[delayPosL] = wetInL + echoR * 0.45f
            delayR[delayPosR] = wetInR + echoL * 0.45f
            delayPosL = (delayPosL + 1) % delayL.size
            delayPosR = (delayPosR + 1) % delayR.size
            l += padL + dripL + chime + echoL * 0.35f
            r += padR + dripR + chime + echoR * 0.35f

            // ---- thunder
            if (thunderWait > 0) {
                thunderWait--
                if (thunderWait == 0) {
                    thunderWait = -1
                    if (thunderClip != null) {
                        val v = nextClipVoice; nextClipVoice = 1 - v
                        clipPos[v] = 0f
                        clipGain[v] = 0.25f + 0.55f * thunderPow
                        clipPan[v] = thunderP * 0.6f
                        clipCutoff[v] = 300f + 6000f * thunderPow * thunderPow // distant strikes sound muffled
                    }
                }
            }
            val clip = thunderClip
            if (clip != null) {
                for (v in 0..1) {
                    val p = clipPos[v]
                    if (p < 0f) continue
                    val idx = p.toInt()
                    val f = p - idx
                    val raw = (clip[idx] * (1f - f) + clip[idx + 1] * f) / 32768f
                    clipFilter[v].set(clipCutoff[v], 0.7f)
                    val x = clipFilter[v].lowpass(raw) * clipGain[v]
                    pan(clipPan[v])
                    l += x * panL; r += x * panR
                    val nextPos = p + CLIP_RATE / SR
                    clipPos[v] = if (nextPos < clip.size - 2) nextPos else -1f
                }
            }

            // ---- runner: soft, low footsteps and landings
            var fx = 0f
            if (stepT >= 0f) {
                stepPhase += 75f / SR
                val thump = sin(stepPhase * 2f * PI.toFloat()) * exp(-stepT * 50f)
                val splash = stepFilter.lowpass(noise()) * exp(-stepT * 45f) * 0.5f
                fx += (thump + splash) * stepAmp * 0.12f
                stepT += 1f / SR
                if (stepT > 0.16f) stepT = -1f
            }
            if (landT >= 0f) {
                landPhase += (55f + 30f * exp(-landT * 20f)) / SR
                val thump = sin(landPhase * 2f * PI.toFloat()) * exp(-landT * 16f)
                val splash = landFilter.lowpass(noise()) * exp(-landT * 14f) * 0.6f
                fx += (thump + splash) * landAmp * 0.2f
                landT += 1f / SR
                if (landT > 0.4f) landT = -1f
            }
            l += fx; r += fx

            // ---- blaster pew
            if (pewT >= 0f) {
                val f = 330f + 1500f * exp(-pewT * 22f)
                pewPhase += f / SR
                val w = sin(pewPhase * 2f * PI.toFloat())
                val x = (w + 0.3f * (if (w > 0f) 1f else -1f)) * exp(-pewT * 16f) * 0.09f
                l += x; r += x
                pewT += 1f / SR
                if (pewT > 0.25f) pewT = -1f
            }
            // ---- saber swing
            if (swingT >= 0f) {
                val bend = (1f + 0.6f * sin(PI.toFloat() * (swingT / 0.34f))) * swingPitch
                swingPhaseA += 98f * bend / SR; if (swingPhaseA >= 1f) swingPhaseA -= 1f
                swingPhaseB += 147.5f * bend / SR; if (swingPhaseB >= 1f) swingPhaseB -= 1f
                swingFilter.set(500f + 900f * bend, 1.5f)
                val raw = (swingPhaseA * 2f - 1f) * 0.6f + (swingPhaseB * 2f - 1f) * 0.4f
                val env = sin(PI.toFloat() * (swingT / 0.34f).coerceIn(0f, 1f))
                val x = swingFilter.lowpass(raw) * env * 0.12f
                l += x; r += x
                swingT += 1f / SR
                if (swingT > 0.34f) swingT = -1f
            }
            // ---- combat blips
            for (v in bT.indices) {
                val bt = bT[v]
                if (bt < 0f) continue
                val k = (bt / bDur[v]).coerceIn(0f, 1f)
                val f = bF0[v] + (bF1[v] - bF0[v]) * k
                bPh[v] += f / SR; if (bPh[v] >= 1f) bPh[v] -= 1f
                val sn = sin(bPh[v] * 2f * PI.toFloat())
                val raw = when (bWave[v]) {
                    1 -> sn + 0.25f * (if (sn > 0f) 1f else -1f)
                    2 -> { bPh2[v] += f * 1.507f / SR; if (bPh2[v] >= 1f) bPh2[v] -= 1f; sn * 0.6f + sin(bPh2[v] * 2f * PI.toFloat()) * 0.5f }
                    else -> sn
                }
                val x = raw * exp(-bt * bDecay[v]) * bAmp[v]
                pan(bPan[v] * 0.7f)
                l += x * panL * 1.4f; r += x * panR * 1.4f
                bT[v] = bt + 1f / SR
                if (bT[v] > bDur[v]) bT[v] = -1f
            }

            // ---- drone down
            if (explodeT >= 0f) {
                explodePhase += (50f + 70f * exp(-explodeT * 12f)) / SR
                pingPhase += 920f / SR
                val x = sin(explodePhase * 2f * PI.toFloat()) * exp(-explodeT * 7f) * 0.3f +
                    sin(pingPhase * 2f * PI.toFloat()) * exp(-explodeT * 20f) * 0.05f
                pan(explodeP * 0.7f)
                l += x * panL * 1.4f; r += x * panR * 1.4f
                explodeT += 1f / SR
                if (explodeT > 0.8f) explodeT = -1f
            }

            // ---- sky-train rumble
            trainGain += (trainAmount - trainGain) * 0.00003f
            if (trainGain > 0.002f) {
                trainPh1 += 41f / SR; if (trainPh1 >= 1f) trainPh1 -= 1f
                trainPh2 += 61.5f / SR; if (trainPh2 >= 1f) trainPh2 -= 1f
                var x = sin(trainPh1 * 2f * PI.toFloat()) * 0.6f + sin(trainPh2 * 2f * PI.toFloat()) * 0.4f
                clackClock += 1f / SR
                if (clackClock > 0.62f) clackClock -= 0.62f
                if (clackT < 0f && (clackClock < 1f / SR || abs(clackClock - 0.11f) < 0.5f / SR)) { clackT = 0f; clackPh = 0f }
                if (clackT >= 0f) {
                    clackPh += 90f / SR
                    x += sin(clackPh * 2f * PI.toFloat()) * exp(-clackT * 40f) * 0.9f
                    clackT += 1f / SR
                    if (clackT > 0.1f) clackT = -1f
                }
                trainFilter.set(220f, 0.7f)
                val y = trainFilter.lowpass(x) * trainGain * 0.16f
                pan(trainPan * 0.6f)
                l += y * panL * 1.4f; r += y * panR * 1.4f
            }

            // ---- tap boom
            if (boomT >= 0f) {
                val f = 35f + 55f * exp(-boomT * 5f)
                boomPhase += f / SR
                val s = sin(boomPhase * 2f * PI.toFloat()) * exp(-boomT * 4f) * 0.35f
                l += s; r += s
                boomT += 1f / SR
                if (boomT > 1.2f) boomT = -1f
            }

            out[2 * i] = softClip(l * master)
            out[2 * i + 1] = softClip(r * master)
        }
    }

    private var panL = 0f
    private var panR = 0f

    /** Equal-power pan into [panL]/[panR]; [p] in -1 (left) .. 1 (right). */
    private fun pan(p: Float) {
        val a = (p.coerceIn(-1f, 1f) + 1f) * 0.25f * PI.toFloat()
        panL = cos(a); panR = sin(a)
    }

    private fun softClip(x: Float) = x / (1f + abs(x))
}

/**
 * Loops a mono or interleaved-stereo clip forever at any rate (linear interpolation), crossfading
 * the tail into the head so there is no audible seam.
 */
private class SampleLoop(private val data: ShortArray, private val channels: Int, srcRate: Float, speed: Float = 1f) {
    private val frames = data.size / channels
    private val fade = minOf(srcRate / 2f, frames / 4f)
    private val loopLen = frames - fade - 2f
    private val step = srcRate / SR * speed
    private var pos = 0f
    var left = 0f; private set
    var right = 0f; private set

    private fun at(frame: Int, ch: Int) = data[frame * channels + minOf(ch, channels - 1)] / 32768f

    private fun sample(p: Float, ch: Int): Float {
        val i = p.toInt()
        val f = p - i
        return at(i, ch) * (1f - f) + at(i + 1, ch) * f
    }

    private fun tap(ch: Int): Float {
        val s = sample(pos, ch)
        if (pos >= fade) return s
        val k = pos / fade
        return s * k + sample(loopLen + pos, ch) * (1f - k)
    }

    fun next() {
        left = tap(0)
        right = tap(1)
        pos += step
        if (pos >= loopLen) pos -= loopLen
    }
}

/** Topology-preserving state-variable filter (Zavalishin), low-pass output. */
private class Svf {
    private var ic1 = 0f; private var ic2 = 0f
    private var a1 = 0f; private var a2 = 0f; private var a3 = 0f; private var k = 1f
    private var lastF = -1f; private var lastQ = -1f

    fun set(freq: Float, q: Float) {
        if (freq == lastF && q == lastQ) return
        lastF = freq; lastQ = q
        val g = tan(PI * (freq.coerceIn(20f, SR * 0.45f) / SR)).toFloat()
        k = 1f / q
        a1 = 1f / (1f + g * (g + k)); a2 = g * a1; a3 = g * a2
    }

    fun lowpass(x: Float): Float {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1; ic2 = 2f * v2 - ic2
        return v2
    }
}
