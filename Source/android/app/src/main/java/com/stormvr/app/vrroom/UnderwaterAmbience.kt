package com.stormvr.app.vrroom

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Ambiência procedural simples para o modo Abismo.
 * Mantém um ruído de água abafado e bolhas ocasionais, sem sustos ou picos de volume.
 */
internal class UnderwaterAmbience {
    companion object {
        private const val SAMPLE_RATE = 22050
        private const val BLOCK_FRAMES = 512
        private const val TWO_PI = (2.0 * PI).toFloat()
    }

    @Volatile private var running = false
    @Volatile private var activeTrack: AudioTrack? = null
    private var audioThread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return

        val output = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, BLOCK_FRAMES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return
        }

        if (output.state != AudioTrack.STATE_INITIALIZED) {
            output.release()
            return
        }

        try {
            output.setVolume(0.30f)
            output.play()
        } catch (_: Exception) {
            output.release()
            return
        }

        activeTrack = output
        running = true
        audioThread = Thread({ stream(output) }, "Abismo-UnderwaterAudio").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        val thread = audioThread
        val output = activeTrack
        audioThread = null
        activeTrack = null
        thread?.interrupt()

        try {
            output?.pause()
            output?.flush()
            output?.stop()
            output?.release()
        } catch (_: Exception) {
        }
    }

    private fun stream(output: AudioTrack) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Exception) {
        }

        val pcm = ShortArray(BLOCK_FRAMES)
        var frame = 0L
        var slowNoise = 0f
        var midNoise = 0f
        var rumblePhaseA = 0f
        var rumblePhaseB = 0f
        var bubbleAge = -1f
        var bubblePhase = 0f
        var nextBubbleAt = SAMPLE_RATE * 5L
        var randomState = 0x13579BDF

        fun randomUnit(): Float {
            randomState = randomState * 1664525 + 1013904223
            return ((randomState ushr 8) and 0xFFFF) / 65535f
        }

        try {
            while (running && activeTrack === output) {
                for (i in pcm.indices) {
                    val white = randomUnit() * 2f - 1f
                    slowNoise += (white - slowNoise) * 0.0018f
                    midNoise += (white - midNoise) * 0.018f

                    rumblePhaseA += TWO_PI * 43f / SAMPLE_RATE
                    rumblePhaseB += TWO_PI * 67f / SAMPLE_RATE
                    if (rumblePhaseA >= TWO_PI) rumblePhaseA -= TWO_PI
                    if (rumblePhaseB >= TWO_PI) rumblePhaseB -= TWO_PI

                    if (frame >= nextBubbleAt) {
                        bubbleAge = 0f
                        bubblePhase = 0f
                        nextBubbleAt = frame + SAMPLE_RATE * (7L + (randomUnit() * 7f).toLong())
                    }

                    var bubble = 0f
                    if (bubbleAge >= 0f) {
                        val t = bubbleAge / 0.30f
                        if (t >= 1f) {
                            bubbleAge = -1f
                        } else {
                            bubblePhase += TWO_PI * (185f + 115f * t) / SAMPLE_RATE
                            val envelope = exp(-t * 5.2f) * (1f - exp(-t * 28f))
                            bubble = sin(bubblePhase) * envelope * 0.16f
                            bubbleAge += 1f / SAMPLE_RATE
                        }
                    }

                    val water = (slowNoise * 2.8f + (midNoise - slowNoise) * 1.6f) * 0.62f
                    val lowHull = sin(rumblePhaseA) * 0.025f + sin(rumblePhaseB) * 0.012f
                    val sample = (water + lowHull + bubble).coerceIn(-0.72f, 0.72f)
                    pcm[i] = (sample * Short.MAX_VALUE).toInt().toShort()
                    frame++
                }

                val written = output.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
            }
        } catch (_: Exception) {
        } finally {
            if (activeTrack === output) running = false
        }
    }
}
