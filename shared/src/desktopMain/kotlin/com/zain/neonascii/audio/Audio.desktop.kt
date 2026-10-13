package com.zain.neonascii.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

private const val BLOCK = 512

actual fun startAudio(synth: Synth) {
    Thread({
        runCatching {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 2, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format, BLOCK * 4 * 4)
            line.start()
            val f = FloatArray(BLOCK * 2)
            val b = ByteArray(BLOCK * 4)
            while (true) {
                synth.render(f, BLOCK)
                for (i in f.indices) {
                    val s = (f[i] * 32767f).toInt().coerceIn(-32768, 32767)
                    b[2 * i] = s.toByte(); b[2 * i + 1] = (s shr 8).toByte()
                }
                line.write(b, 0, b.size) // blocks: paces the loop to real time
            }
        }
    }, "neon-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
}
