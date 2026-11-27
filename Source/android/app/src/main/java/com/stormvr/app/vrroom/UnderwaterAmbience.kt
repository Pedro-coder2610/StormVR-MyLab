package com.stormvr.app.vrroom

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Ambiência procedural simples para o modo Abismo.
 * Mantém um ruído de água abafado e bolhas ocasionais, sem sustos ou picos de volume.
 */
internal class UnderwaterAmbience {
    companion object {
        private const val SAMPLE_RATE = 22050
        private const val BLOCK_FRAMES = 512
        private const val TWO_PI = (2.0 * PI).toFloat()

        const val CUE_BUTTON = 1
        const val CUE_PHOTO = 2
        const val CUE_PIPE = 3
        const val CUE_TREASURE = 4
        const val CUE_SURFACE = 5
        const val CUE_WARNING = 6
    }

    @Volatile private var running = false
    @Volatile private var activeTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    private val cueQueue = ConcurrentLinkedQueue<Int>()

    /** Sons curtos de interação, enfileirados a partir da thread do jogo. */
    fun playCue(type: Int) {
        if (running) cueQueue.offer(type)
    }

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
            output.setVolume(0.46f)
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
        var sonarAge = -1f
        var sonarPhase = 0f
        var nextSonarAt = SAMPLE_RATE * 3L
        var cueType = 0
        var cueAge = 0f
        var cuePhase = 0f
        var randomState = 0x13579BDF

        fun randomUnit(): Float {
            randomState = randomState * 1664525 + 1013904223
            return ((randomState ushr 8) and 0xFFFF) / 65535f
        }

        try {
            while (running && activeTrack === output) {
                cueQueue.poll()?.let { requested ->
                    cueType = requested
                    cueAge = 0f
                    cuePhase = 0f
                }
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

                    // Ping de sonar periódico, curto e suave, para a cabine não parecer silenciosa.
                    if (frame >= nextSonarAt) {
                        sonarAge = 0f
                        sonarPhase = 0f
                        nextSonarAt = frame + SAMPLE_RATE * 8L
                    }
                    var sonar = 0f
                    if (sonarAge >= 0f) {
                        val st = sonarAge / 0.62f
                        if (st >= 1f) sonarAge = -1f else {
                            sonarPhase += TWO_PI * (980f - 260f * st) / SAMPLE_RATE
                            sonar = sin(sonarPhase) * exp(-st * 5.0f) * 0.12f
                            sonarAge += 1f / SAMPLE_RATE
                        }
                    }

                    // Efeitos pontuais: clique, obturador, vazamento, descoberta, recarga e alerta de O2.
                    var effect = 0f
                    if (cueType != 0) {
                        val duration = when (cueType) {
                            CUE_BUTTON -> 0.10f
                            CUE_PHOTO -> 0.28f
                            CUE_PIPE -> 0.90f
                            CUE_TREASURE -> 1.35f
                            CUE_SURFACE -> 0.95f
                            else -> 0.62f
                        }
                        val ct = cueAge / duration
                        if (ct >= 1f) {
                            cueType = 0
                        } else {
                            val attack = (1f - exp(-ct * 55f)).coerceIn(0f, 1f)
                            val decay = exp(-ct * if (cueType == CUE_PIPE) 2.4f else 5.5f)
                            val env = attack * decay
                            val tone = when (cueType) {
                                CUE_BUTTON -> sin(cuePhase) * 0.13f
                                CUE_PHOTO -> sin(cuePhase) * 0.16f + white * exp(-ct * 38f) * 0.11f
                                CUE_PIPE -> sin(cuePhase) * 0.10f + sin(cuePhase * 0.47f) * 0.07f
                                CUE_TREASURE -> sin(cuePhase) * 0.16f
                                CUE_SURFACE -> sin(cuePhase) * 0.12f + sin(cuePhase * 1.5f) * 0.05f
                                else -> sin(cuePhase) * 0.12f
                            }
                            effect = tone * env
                            val frequency = when (cueType) {
                                CUE_BUTTON -> 760f
                                CUE_PHOTO -> 1480f - 620f * ct
                                CUE_PIPE -> 92f - 35f * ct
                                CUE_TREASURE -> when {
                                    ct < 0.34f -> 523.25f
                                    ct < 0.67f -> 659.25f
                                    else -> 783.99f
                                }
                                CUE_SURFACE -> if (ct < 0.5f) 440f else 587.33f
                                else -> if ((ct * 5f).toInt() % 2 == 0) 720f else 540f
                            }
                            cuePhase += TWO_PI * frequency / SAMPLE_RATE
                            cueAge += 1f / SAMPLE_RATE
                        }
                    }

                    val water = (slowNoise * 2.8f + (midNoise - slowNoise) * 1.6f) * 0.82f
                    val lowHull = sin(rumblePhaseA) * 0.045f + sin(rumblePhaseB) * 0.022f
                    val sample = (water + lowHull + bubble + sonar + effect).coerceIn(-0.88f, 0.88f)
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
