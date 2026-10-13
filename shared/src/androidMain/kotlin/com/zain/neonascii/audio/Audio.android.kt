package com.zain.neonascii.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

private const val BLOCK = 512

actual fun startAudio(synth: Synth) {
    Thread({
        runCatching {
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, BLOCK * 2 * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val buf = FloatArray(BLOCK * 2)
            var playing = false
            while (true) {
                if (synth.paused) {
                    // Stop the track in the background instead of streaming silence.
                    if (playing) { track.pause(); track.flush(); playing = false }
                    Thread.sleep(50)
                    continue
                }
                if (!playing) { track.play(); playing = true }
                synth.render(buf, BLOCK)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        }
    }, "neon-audio").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
}
